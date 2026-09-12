package com.tessera.fleet.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import com.tessera.fleet.routing.GeoMath;
import com.tessera.fleet.support.TestFixtures;

/**
 * Guards the committed trajectory dataset.
 *
 * <p>The whole point of pre-generating the fleet's motion is that it can be
 * checked once and then trusted. These are the same physical invariants
 * {@link FleetDatasetGenerator} enforces before writing — re-run here against the
 * file that actually ships, so a regenerated dataset from a broken model (or a
 * hand-edited file) fails the build rather than quietly reintroducing the
 * teleporting fleet this replaced.
 */
class FleetDatasetPlausibilityTest {

    private static FleetDataset dataset;

    @BeforeAll
    static void load() throws Exception {
        dataset = FleetDataset.load(
                new DefaultResourceLoader().getResource(TestFixtures.DATASET_RESOURCE));
    }

    @Test
    void datasetIsNonTrivial() {
        assertThat(dataset.vehicleCount()).isGreaterThanOrEqualTo(8);
        assertThat(dataset.tickCount()).isGreaterThanOrEqualTo(600);
        assertThat(dataset.tickMillis()).isEqualTo(1000L);
        assertThat(dataset.area()).isNotBlank();
    }

    @Test
    void noVehicleEverTeleports() {
        double tickSeconds = dataset.tickMillis() / 1000.0;
        double limit = FleetDatasetGenerator.V_MAX_MPS * tickSeconds * 1.05;
        double worst = 0;
        for (int t = 1; t < dataset.tickCount(); t++) {
            for (int v = 0; v < dataset.vehicleCount(); v++) {
                double d = GeoMath.haversineMeters(
                        dataset.latitude(t - 1, v), dataset.longitude(t - 1, v),
                        dataset.latitude(t, v), dataset.longitude(t, v));
                worst = Math.max(worst, d);
            }
        }
        assertThat(worst)
                .as("largest single-tick displacement (m)")
                .isLessThanOrEqualTo(limit);
    }

    @Test
    void speedAndAccelerationStayPhysical() {
        double accelLimit = Math.max(FleetDatasetGenerator.A_MAX, FleetDatasetGenerator.B_MAX) * 1.5;
        double worstAccel = 0;
        double worstSpeed = 0;
        for (int t = 1; t < dataset.tickCount(); t++) {
            for (int v = 0; v < dataset.vehicleCount(); v++) {
                double prev = dataset.speedKph(t - 1, v);
                double now = dataset.speedKph(t, v);
                worstAccel = Math.max(worstAccel, Math.abs(now - prev) / 3.6);
                worstSpeed = Math.max(worstSpeed, now);
            }
        }
        assertThat(worstAccel).as("largest tick-to-tick acceleration (m/s^2)")
                .isLessThanOrEqualTo(accelLimit);
        assertThat(worstSpeed).as("top speed (km/h)")
                .isLessThanOrEqualTo(FleetDatasetGenerator.V_MAX_MPS * 3.6 * 1.02);
    }

    @Test
    void reportedSpeedAgreesWithActualDisplacement() {
        // A vehicle that claims to be stopped must not be moving, and one that
        // claims a speed must not be moving wildly faster than that.
        double tickSeconds = dataset.tickMillis() / 1000.0;
        for (int t = 1; t < dataset.tickCount(); t++) {
            for (int v = 0; v < dataset.vehicleCount(); v++) {
                double moved = GeoMath.haversineMeters(
                        dataset.latitude(t - 1, v), dataset.longitude(t - 1, v),
                        dataset.latitude(t, v), dataset.longitude(t, v));
                double claimedMps = Math.max(dataset.speedKph(t - 1, v), dataset.speedKph(t, v)) / 3.6;
                assertThat(moved)
                        .as("tick %d vehicle %d moved %.1f m while reporting <= %.1f m/s",
                                t, v, moved, claimedMps)
                        .isLessThanOrEqualTo(claimedMps * tickSeconds + 1.5);
            }
        }
    }

    @Test
    void theFleetIsActuallyMoving() {
        int moving = 0;
        int samples = 0;
        for (int t = 1; t < dataset.tickCount(); t++) {
            for (int v = 0; v < dataset.vehicleCount(); v++) {
                double d = GeoMath.haversineMeters(
                        dataset.latitude(t - 1, v), dataset.longitude(t - 1, v),
                        dataset.latitude(t, v), dataset.longitude(t, v));
                samples++;
                if (d > 0.5) {
                    moving++;
                }
            }
        }
        assertThat(100.0 * moving / samples)
                .as("percentage of ticks in which a vehicle is moving")
                .isGreaterThanOrEqualTo(25.0);
    }

    @Test
    void theTrackLoopsWithoutADiscontinuity() {
        int last = dataset.tickCount() - 1;
        for (int v = 0; v < dataset.vehicleCount(); v++) {
            double seam = GeoMath.haversineMeters(
                    dataset.latitude(last, v), dataset.longitude(last, v),
                    dataset.latitude(0, v), dataset.longitude(0, v));
            assertThat(seam).as("loop seam for vehicle %d", v).isLessThanOrEqualTo(1.0);
            assertThat(dataset.speedKph(last, v)).isZero();
            assertThat(dataset.speedKph(0, v)).isZero();
        }
    }
}
