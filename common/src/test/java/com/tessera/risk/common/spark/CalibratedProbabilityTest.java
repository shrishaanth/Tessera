package com.tessera.risk.common.spark;

import java.util.List;

import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tessera.risk.common.model.ModelCalibration;

import static org.apache.spark.sql.functions.col;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The Spark expression and the plain-Java correction are written twice, once for
 * each runtime, so this asserts they are the same function.
 */
class CalibratedProbabilityTest {

    @Test
    @DisplayName("the Spark column agrees with the documented Java formula")
    void sparkMatchesJava() {
        SparkSession spark = SparkSession.builder()
                .appName("tessera-calibration-test").master("local[1]")
                .config("spark.ui.enabled", "false").getOrCreate();
        spark.sparkContext().setLogLevel("WARN");

        ModelCalibration calibration = new ModelCalibration("m", 0.6, 3_050, 30_766);
        double[] inputs = {0.0, 0.05, 0.32, 0.5, 0.6, 0.73, 0.99, 1.0};
        List<Row> rows = new java.util.ArrayList<>();
        for (double p : inputs) {
            rows.add(RowFactory.create(p));
        }
        StructType schema = new StructType().add("p", DataTypes.DoubleType);

        List<Row> out = spark.createDataFrame(rows, schema)
                .withColumn("c", CalibratedProbability.of(col("p"), calibration))
                .collectAsList();

        for (Row row : out) {
            double p = row.getDouble(0);
            assertThat(row.getDouble(1))
                    .as("corrected %s", p)
                    .isCloseTo(calibration.correct(p), within(1e-12));
        }
    }
}
