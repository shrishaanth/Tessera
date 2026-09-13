package com.tessera.risk.streaming.ml;

import java.io.IOException;
import java.util.Optional;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.spark.ml.PipelineModel;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tessera.risk.common.model.ModelCalibration;
import com.tessera.risk.common.spark.CalibratedProbability;
import com.tessera.risk.common.spark.FeatureSchema;
import com.tessera.risk.common.spark.FeatureVector;

import static org.apache.spark.ml.functions.vector_to_array;
import static org.apache.spark.sql.functions.col;

/**
 * Scores live windows with the persisted classifier (FR-3.6).
 *
 * <h2>Optional on purpose</h2>
 * The pipeline has to work before a model exists. A fresh clone has no trained
 * model — it cannot, since training needs an archive that has to be generated
 * first — and the streaming job refusing to start until one appears would make the
 * stack undemonstrable in its natural order. So the model is loaded if present and
 * the job runs without it otherwise, keeping the rule-based risk score, which is
 * why that score exists as well as the model.
 *
 * <h2>What makes the score meaningful</h2>
 * The features are derived by {@link FeatureVector}, the same code the batch job
 * used to build the training set, and the vector is assembled inside the persisted
 * pipeline rather than here. Between them, the two remove the ways a served model
 * usually goes quietly wrong: features computed by different arithmetic than they
 * were fitted to, or assembled in a different order.
 *
 * <p>The decision threshold travels inside the model too — training baked it into
 * the classifier's {@code thresholds} parameter — so the cutoff applied here is the
 * one whose precision and recall were actually measured.
 *
 * <h2>What the stored probability means</h2>
 * The classifier's own probability is inflated by the class weighting used in
 * training, and would read as roughly three times the real chance of an incident.
 * It is corrected with the {@link ModelCalibration} saved beside the model before
 * anything stores, alerts on or displays it, so {@link #POSITIVE_PROBABILITY} is a
 * true chance. The predicted label is not touched: the correction preserves order,
 * and the decision was measured on the raw scale.
 */
public final class RiskModel {

    private static final Logger log = LoggerFactory.getLogger(RiskModel.class);

    /** True chance of an incident in the next window, after calibration. */
    public static final String POSITIVE_PROBABILITY = "positiveProbability";

    private final PipelineModel model;
    private final ModelCalibration calibration;

    private RiskModel(PipelineModel model, ModelCalibration calibration) {
        this.model = model;
        this.calibration = calibration;
    }

    /**
     * Load the model, or return empty if none has been trained yet.
     *
     * <p>A missing model is an expected state, not a fault, so it is reported as
     * absence. A model that exists but cannot be read is a fault and is allowed to
     * propagate: silently scoring nothing because a file was corrupt would be the
     * worst of both.
     */
    public static Optional<RiskModel> load(SparkSession spark, String path) {
        try {
            Path modelPath = new Path(path);
            FileSystem fs = modelPath.getFileSystem(spark.sparkContext().hadoopConfiguration());
            if (!fs.exists(modelPath)) {
                log.warn("No model at {} — windows will carry the rule-based risk score "
                        + "only. Run the archive generator, batch job and training first.", path);
                return Optional.empty();
            }
        } catch (IOException e) {
            log.warn("Could not check for a model at {}: {}", path, e.getMessage());
            return Optional.empty();
        }

        // A model without its calibration is one trained before the correction
        // existed. Scoring with it would put inflated percentages back on the
        // dashboard, so it is refused rather than used: the job falls back to the
        // rule-based score and says why.
        Optional<ModelCalibration> calibration = readCalibration(spark, path);
        if (calibration.isEmpty()) {
            log.warn("Model at {} has no {}; it predates probability calibration and its "
                    + "percentages would be inflated. Scoring by rules only; retrain with "
                    + "the ml-training job.", path, ModelCalibration.FILE_NAME);
            return Optional.empty();
        }

        PipelineModel loaded = PipelineModel.load(path);
        ModelCalibration cal = calibration.get();
        log.info("Loaded risk model from {} ({}; flags windows at a {}% chance of an incident)",
                path, cal.model(), String.format("%.1f", 100 * cal.correctedThreshold()));
        return Optional.of(new RiskModel(loaded, cal));
    }

    private static Optional<ModelCalibration> readCalibration(SparkSession spark, String modelPath) {
        try {
            Path file = new Path(modelPath, ModelCalibration.FILE_NAME);
            FileSystem fs = file.getFileSystem(spark.sparkContext().hadoopConfiguration());
            if (!fs.exists(file)) {
                return Optional.empty();
            }
            try (java.io.InputStream in = fs.open(file)) {
                return Optional.of(ModelCalibration.fromJson(
                        new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
            }
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Could not read model calibration at {}: {}", modelPath, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Add {@code prediction}, {@code probability} and {@link #POSITIVE_PROBABILITY}
     * to a frame of vehicle windows.
     *
     * @param vehicleWindows windows carrying {@link FeatureSchema#AGGREGATE_COLUMNS}
     */
    public Dataset<Row> score(Dataset<Row> vehicleWindows) {
        Dataset<Row> withFeatures = FeatureVector.withFeatures(vehicleWindows);
        // Java gets no default argument for the element type.
        Column raw = vector_to_array(col(FeatureSchema.PROBABILITY), "float64").getItem(1);
        return model.transform(withFeatures)
                .withColumn(POSITIVE_PROBABILITY, CalibratedProbability.of(raw, calibration));
    }
}
