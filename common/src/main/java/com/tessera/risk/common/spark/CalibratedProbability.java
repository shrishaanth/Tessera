package com.tessera.risk.common.spark;

import org.apache.spark.sql.Column;

import com.tessera.risk.common.model.ModelCalibration;

import static org.apache.spark.sql.functions.greatest;
import static org.apache.spark.sql.functions.least;
import static org.apache.spark.sql.functions.lit;
import static org.apache.spark.sql.functions.when;

/**
 * {@link ModelCalibration#correct} as a Spark column expression.
 *
 * <p>Used by the training report, which checks the corrected probabilities against
 * what actually happened, and by the streaming job, which stores them. Both must
 * compute exactly what the plain-Java version documents; {@code
 * CalibratedProbabilityTest} asserts they agree.
 */
public final class CalibratedProbability {

    private CalibratedProbability() {
    }

    /** {@code p·k / (1 − p + p·k)} over a column of raw positive-class probabilities. */
    public static Column of(Column rawProbability, ModelCalibration calibration) {
        Column p = least(lit(1.0), greatest(lit(0.0), rawProbability));
        Column k = lit(calibration.priorOdds());
        Column denominator = lit(1.0).minus(p).plus(p.multiply(k));
        return when(denominator.equalTo(0.0), lit(1.0))
                .otherwise(p.multiply(k).divide(denominator));
    }
}
