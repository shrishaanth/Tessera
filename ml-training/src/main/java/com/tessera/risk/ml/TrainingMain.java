package com.tessera.risk.ml;

import java.util.ArrayList;
import java.util.List;

import org.apache.spark.ml.Pipeline;
import org.apache.spark.ml.PipelineModel;
import org.apache.spark.ml.PipelineStage;
import org.apache.spark.ml.Transformer;
import org.apache.spark.ml.classification.LogisticRegression;
import org.apache.spark.ml.classification.ProbabilisticClassificationModel;
import org.apache.spark.ml.classification.RandomForestClassificationModel;
import org.apache.spark.ml.classification.RandomForestClassifier;
import org.apache.spark.ml.evaluation.BinaryClassificationEvaluator;
import org.apache.spark.ml.feature.VectorAssembler;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.storage.StorageLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tessera.risk.common.model.RiskTier;
import com.tessera.risk.common.spark.FeatureSchema;

import static org.apache.spark.ml.functions.vector_to_array;
import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.lit;
import static org.apache.spark.sql.functions.not;
import static org.apache.spark.sql.functions.sum;
import static org.apache.spark.sql.functions.when;

/**
 * Trains, evaluates and persists the incident-risk classifier (FR-3.4, FR-3.5).
 *
 * <h2>The split is chronological, not random</h2>
 * The task is to predict the next few minutes from the last few. A random split
 * would train on Friday and test on Tuesday, which is not a question anyone needs
 * answered and which quietly makes the problem easier: the model gets to see how
 * each vehicle behaves throughout the whole period. Splitting on time asks the
 * honest question — fit on what has happened, score on what happens next.
 *
 * <h2>Why the threshold is tuned, and tuned on the training split</h2>
 * With one window in ten positive, a classifier at the default 0.5 cutoff predicts
 * almost nothing positive and recall collapses. That is not the model failing; it is
 * the cutoff being wrong for the base rate. So a threshold is chosen by maximising
 * F1 — on the <em>training</em> predictions, never the test ones. Choosing it on the
 * test split would be fitting to the held-out data, and the reported numbers would
 * be an optimistic fiction.
 *
 * <p>The chosen cutoff is then baked into the saved model through the
 * {@code thresholds} parameter, so the streaming job needs no separate metadata
 * file and cannot score with a different cutoff than was measured here.
 *
 * <h2>Three baselines, not one</h2>
 * "Beat always-predicting-no" is too low a bar to mean anything. The model is also
 * compared against flagging every risky-tier driver, and against a hand-written
 * single-feature rule on hard-braking rate whose threshold is tuned the same way
 * the model's is. If the model cannot beat a rule an engineer would write in an
 * afternoon, that is worth knowing rather than hiding.
 */
public final class TrainingMain {

    private static final Logger log = LoggerFactory.getLogger(TrainingMain.class);

    /** Probability of the positive class, extracted from the probability vector. */
    private static final String POSITIVE_PROBABILITY = "positiveProbability";
    private static final String WEIGHT = "classWeight";

    private TrainingMain() {
    }

    public static void main(String[] args) throws java.io.IOException {
        TrainingConfig config = TrainingConfig.fromEnvironment();
        log.info("Tessera Risk model training");
        log.info("  features  {}", config.featuresPath());
        log.info("  model     {}", config.modelPath());

        SparkSession spark = SparkSession.builder()
                .appName("tessera-risk-ml-training")
                .master(envString("SPARK_MASTER_URL", "local[*,4]"))
                .config("spark.sql.session.timeZone", "UTC")
                .config("spark.sql.shuffle.partitions", "8")
                .getOrCreate();

        Dataset<Row> features = spark.read().parquet(config.featuresPath());
        long rows = features.count();
        if (rows == 0) {
            throw new IllegalStateException(
                    "No training rows at " + config.featuresPath() + "; run the batch job first");
        }

        Split split = chronologicalSplit(features, config.trainFraction());
        log.info("split at {} — {} training rows ({} positive), {} test rows ({} positive)",
                split.cutoffMillis(),
                String.format("%,d", split.trainRows()), String.format("%,d", split.trainPositives()),
                String.format("%,d", split.testRows()), String.format("%,d", split.testPositives()));
        if (split.trainPositives() == 0 || split.testPositives() == 0) {
            throw new IllegalStateException(
                    "One side of the split holds no positive rows; the archive is too short to train on");
        }

        Dataset<Row> train = weighted(split.train()).persist(StorageLevel.MEMORY_AND_DISK());
        Dataset<Row> test = split.test().persist(StorageLevel.MEMORY_AND_DISK());

        List<Evaluation> results = new ArrayList<>();
        results.add(majorityBaseline(test));
        results.add(riskTierBaseline(test));
        results.add(hardBrakeRuleBaseline(train, test));

        // The ablation runs first so its numbers sit next to the tier baseline in
        // the table: tier-without-behaviour, then behaviour-without-tier, then both.
        Trained behaviourOnly = train("forest, behaviour only",
                randomForest(config, behaviourOnlyColumns()), train, test);
        results.add(behaviourOnly.evaluation());
        Trained forest = train("forest, all features", randomForest(config), train, test);
        results.add(forest.evaluation());
        Trained logistic = train("logistic regression", logisticRegression(config), train, test);
        results.add(logistic.evaluation());

        report(results);
        reportImportances(forest.model());

        // Whichever generalises better on the held-out split, by PR-AUC — the metric
        // that does not flatter a model for getting the large negative class right.
        Trained best = forest.evaluation().areaUnderPR() >= logistic.evaluation().areaUnderPR()
                ? forest : logistic;
        log.info("persisting {} (threshold {}) to {}",
                best.name(), String.format("%.2f", best.threshold()), config.modelPath());
        best.model().write().overwrite().save(config.modelPath());

        train.unpersist();
        test.unpersist();
        spark.stop();
    }

    // ---------------------------------------------------------------- splitting

    private record Split(Dataset<Row> train, Dataset<Row> test, long cutoffMillis,
                         long trainRows, long trainPositives,
                         long testRows, long testPositives) { }

    /**
     * Split on window start, so every training row precedes every test row.
     *
     * <p>The cutoff is a quantile of the observed time range rather than a fixed
     * date, so it follows however much archive happens to exist.
     */
    private static Split chronologicalSplit(Dataset<Row> features, double trainFraction) {
        double[] quantiles = features.stat().approxQuantile(
                FeatureSchema.WINDOW_START, new double[] {trainFraction}, 0.001);
        long cutoff = (long) quantiles[0];

        Dataset<Row> train = features.filter(col(FeatureSchema.WINDOW_START).lt(cutoff));
        Dataset<Row> test = features.filter(col(FeatureSchema.WINDOW_START).geq(cutoff));
        return new Split(train, test, cutoff,
                train.count(), positives(train), test.count(), positives(test));
    }

    private static long positives(Dataset<Row> rows) {
        return rows.filter(col(FeatureSchema.LABEL).equalTo(1.0)).count();
    }

    /**
     * Attach inverse-frequency class weights.
     *
     * <p>Without this the loss is dominated by the nine-in-ten negatives and the
     * cheapest way to reduce it is to predict negative always. Weighting makes one
     * positive worth as much as the class imbalance says it should be, which is what
     * lets the model learn a boundary at all rather than a constant.
     */
    private static Dataset<Row> weighted(Dataset<Row> train) {
        long total = train.count();
        long positives = positives(train);
        double positiveWeight = total / (2.0 * positives);
        double negativeWeight = total / (2.0 * (total - positives));
        return train.withColumn(WEIGHT,
                when(col(FeatureSchema.LABEL).equalTo(1.0), lit(positiveWeight))
                        .otherwise(lit(negativeWeight)));
    }

    // ---------------------------------------------------------------- baselines

    /** Always predict "no incident". The number a reader instinctively compares against. */
    private static Evaluation majorityBaseline(Dataset<Row> test) {
        return confusion("baseline: always negative", test, lit(false));
    }

    /** Flag every driver in the riskiest tier — what the roster alone buys you. */
    private static Evaluation riskTierBaseline(Dataset<Row> test) {
        double riskiest = RiskTier.RISKY.ordinal();
        return confusion("baseline: tier is RISKY", test,
                col(FeatureSchema.RISK_TIER_ORDINAL).geq(lit(riskiest)));
    }

    /**
     * A hand-written rule on hard-braking rate, with its threshold tuned on the
     * training split exactly as the model's is. This is the bar that actually
     * matters: it is what someone would build without machine learning.
     */
    private static Evaluation hardBrakeRuleBaseline(Dataset<Row> train, Dataset<Row> test) {
        double best = 0.0;
        double bestF1 = -1.0;
        for (double cut = 0.01; cut <= 0.20; cut += 0.01) {
            Evaluation candidate = confusion("rule", train,
                    col(FeatureSchema.HARD_BRAKE_RATE).geq(lit(cut)));
            if (candidate.f1() > bestF1) {
                bestF1 = candidate.f1();
                best = cut;
            }
        }
        log.info("hard-brake rule: threshold {} chosen on the training split",
                String.format("%.2f", best));
        return confusion(String.format("baseline: hardBrakeRate>=%.2f", best), test,
                col(FeatureSchema.HARD_BRAKE_RATE).geq(lit(best)));
    }

    // ---------------------------------------------------------------- training

    private record Trained(String name, PipelineModel model, double threshold,
                           Evaluation evaluation) { }

    /**
     * Feature columns with the driver's tier removed.
     *
     * <p>Used for the ablation. The tier is a legitimate and strong predictor — a
     * real operator knows a driver's record — but it is also the data-generating
     * process's own input, so a model can lean on it and appear to work while having
     * learned nothing about behaviour. Training once without it is the only way to
     * see whether the behavioural features carry information of their own.
     */
    private static String[] behaviourOnlyColumns() {
        List<String> columns = new ArrayList<>();
        for (String column : FeatureSchema.FEATURE_COLUMNS) {
            if (!column.equals(FeatureSchema.RISK_TIER_ORDINAL)) {
                columns.add(column);
            }
        }
        return columns.toArray(new String[0]);
    }

    private static Pipeline randomForest(TrainingConfig config) {
        return randomForest(config, FeatureSchema.FEATURE_COLUMNS);
    }

    private static Pipeline randomForest(TrainingConfig config, String[] featureColumns) {
        RandomForestClassifier classifier = new RandomForestClassifier()
                .setLabelCol(FeatureSchema.LABEL)
                .setFeaturesCol(FeatureSchema.FEATURES)
                .setWeightCol(WEIGHT)
                .setNumTrees(config.treeCount())
                .setMaxDepth(config.maxDepth())
                .setSeed(config.seed());
        return new Pipeline().setStages(
                new PipelineStage[] {assembler(featureColumns), classifier});
    }

    private static Pipeline logisticRegression(TrainingConfig config) {
        LogisticRegression classifier = new LogisticRegression()
                .setLabelCol(FeatureSchema.LABEL)
                .setFeaturesCol(FeatureSchema.FEATURES)
                .setWeightCol(WEIGHT)
                .setMaxIter(100)
                // A little regularisation: the rate features are strongly correlated
                // with each other, which makes unregularised coefficients large and
                // unstable without improving the fit.
                .setRegParam(0.01)
                .setElasticNetParam(0.0);
        return new Pipeline().setStages(
                new PipelineStage[] {assembler(FeatureSchema.FEATURE_COLUMNS), classifier});
    }

    /**
     * Assembles the feature vector in {@link FeatureSchema#FEATURE_COLUMNS} order.
     *
     * <p>Part of the saved pipeline rather than applied beforehand, so the persisted
     * model carries the column names and their order with it. The streaming job then
     * hands it a DataFrame and cannot assemble the vector differently than training
     * did — the one mistake that would silently invalidate every prediction.
     */
    private static VectorAssembler assembler(String[] featureColumns) {
        return new VectorAssembler()
                .setInputCols(featureColumns)
                .setOutputCol(FeatureSchema.FEATURES);
    }

    private static Trained train(String name, Pipeline pipeline,
                                 Dataset<Row> train, Dataset<Row> test) {
        log.info("fitting {}", name);
        PipelineModel model = pipeline.fit(train);

        Dataset<Row> trainScored = scored(model.transform(train))
                .persist(StorageLevel.MEMORY_AND_DISK());
        double threshold = bestThreshold(trainScored);
        trainScored.unpersist();
        log.info("  threshold {} chosen on the training split", String.format("%.2f", threshold));

        // Bake the cutoff into the model so the streaming job inherits it.
        applyThreshold(model, threshold);

        Dataset<Row> testScored = scored(model.transform(test))
                .persist(StorageLevel.MEMORY_AND_DISK());
        Evaluation evaluation = confusion(name, testScored,
                col(POSITIVE_PROBABILITY).geq(lit(threshold)),
                areaUnder(testScored, "areaUnderPR"),
                areaUnder(testScored, "areaUnderROC"));
        testScored.unpersist();

        return new Trained(name, model, threshold, evaluation);
    }

    /** Flatten the probability vector so the positive class can be thresholded. */
    private static Dataset<Row> scored(Dataset<Row> predictions) {
        return predictions.withColumn(POSITIVE_PROBABILITY,
                // Java gets no default argument for the element type, unlike Scala.
                vector_to_array(col(FeatureSchema.PROBABILITY), "float64").getItem(1));
    }

    /**
     * The cutoff maximising F1 on whatever split is passed in.
     *
     * <p>Always called with the training predictions. Tuning on the test split would
     * leak it into the model and the held-out numbers would stop being held out.
     */
    private static double bestThreshold(Dataset<Row> trainScored) {
        double best = 0.5;
        double bestF1 = -1.0;
        for (double threshold = 0.05; threshold <= 0.95; threshold += 0.05) {
            Evaluation candidate = confusion("candidate", trainScored,
                    col(POSITIVE_PROBABILITY).geq(lit(threshold)));
            if (candidate.f1() > bestF1) {
                bestF1 = candidate.f1();
                best = threshold;
            }
        }
        return best;
    }

    /**
     * Set the decision cutoff on the pipeline's classifier.
     *
     * <p>Spark's {@code thresholds} are per-class divisors, not a cutoff: it predicts
     * whichever class maximises {@code probability[i] / thresholds[i]}. Working that
     * through, {@code {1 - t, t}} yields "positive when the positive probability
     * exceeds t", which is what a cutoff of t is supposed to mean.
     */
    private static void applyThreshold(PipelineModel model, double threshold) {
        for (Transformer stage : model.stages()) {
            if (stage instanceof ProbabilisticClassificationModel<?, ?> classifier) {
                classifier.setThresholds(new double[] {1.0 - threshold, threshold});
            }
        }
    }

    private static double areaUnder(Dataset<Row> scored, String metric) {
        return new BinaryClassificationEvaluator()
                .setLabelCol(FeatureSchema.LABEL)
                .setRawPredictionCol(FeatureSchema.PROBABILITY)
                .setMetricName(metric)
                .evaluate(scored);
    }

    // ---------------------------------------------------------------- reporting

    private static Evaluation confusion(String name, Dataset<Row> rows, Column flagged) {
        return confusion(name, rows, flagged, Double.NaN, Double.NaN);
    }

    /** One pass over the data producing all four confusion counts. */
    private static Evaluation confusion(String name, Dataset<Row> rows, Column flagged,
                                        double areaUnderPR, double areaUnderROC) {
        Column positive = col(FeatureSchema.LABEL).equalTo(1.0);
        Row counts = rows.select(
                count(flagged.and(positive)).as("tp"),
                count(flagged.and(not(positive))).as("fp"),
                count(not(flagged).and(not(positive))).as("tn"),
                count(not(flagged).and(positive)).as("fn")).first();

        return new Evaluation(name,
                counts.getLong(0), counts.getLong(1), counts.getLong(2), counts.getLong(3),
                areaUnderPR, areaUnderROC);
    }

    private static Column count(Column condition) {
        return sum(when(condition, lit(1L)).otherwise(lit(0L)));
    }

    private static void report(List<Evaluation> results) {
        log.info("");
        log.info("held-out evaluation (chronological split)");
        log.info(Evaluation.tableHeader());
        for (Evaluation evaluation : results) {
            log.info(evaluation.toTableRow());
        }
        log.info("");
        for (Evaluation evaluation : results) {
            log.info(evaluation.confusionMatrix());
        }
    }

    /**
     * Feature importances, which are the closest thing to an explanation available.
     *
     * <p>Worth printing even when a feature turns out to matter very little: the hour
     * of day is expected to carry almost nothing, because the generator has no
     * diurnal cycle, and seeing that confirmed is a check on the whole pipeline
     * rather than a disappointment.
     */
    private static void reportImportances(PipelineModel model) {
        for (Transformer stage : model.stages()) {
            if (stage instanceof RandomForestClassificationModel forest) {
                double[] importances = forest.featureImportances().toArray();
                log.info("");
                log.info("random forest feature importances");
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < importances.length; i++) {
                    order.add(i);
                }
                order.sort((a, b) -> Double.compare(importances[b], importances[a]));
                for (int index : order) {
                    log.info(String.format("  %-22s %.4f",
                            FeatureSchema.FEATURE_COLUMNS[index], importances[index]));
                }
            }
        }
    }

    private static String envString(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }
}
