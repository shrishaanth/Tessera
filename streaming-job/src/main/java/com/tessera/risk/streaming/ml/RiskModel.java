package com.tessera.risk.streaming.ml;

import java.io.IOException;
import java.util.Optional;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.spark.ml.PipelineModel;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 */
public final class RiskModel {

    private static final Logger log = LoggerFactory.getLogger(RiskModel.class);

    /** Probability of the positive class, flattened out of the probability vector. */
    public static final String POSITIVE_PROBABILITY = "positiveProbability";

    private final PipelineModel model;

    private RiskModel(PipelineModel model) {
        this.model = model;
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

        PipelineModel loaded = PipelineModel.load(path);
        log.info("Loaded risk model from {}", path);
        return Optional.of(new RiskModel(loaded));
    }

    /**
     * Add {@code prediction}, {@code probability} and {@link #POSITIVE_PROBABILITY}
     * to a frame of vehicle windows.
     *
     * @param vehicleWindows windows carrying {@link FeatureSchema#AGGREGATE_COLUMNS}
     */
    public Dataset<Row> score(Dataset<Row> vehicleWindows) {
        Dataset<Row> withFeatures = FeatureVector.withFeatures(vehicleWindows);
        return model.transform(withFeatures)
                .withColumn(POSITIVE_PROBABILITY,
                        // Java gets no default argument for the element type.
                        vector_to_array(col(FeatureSchema.PROBABILITY), "float64").getItem(1));
    }
}
