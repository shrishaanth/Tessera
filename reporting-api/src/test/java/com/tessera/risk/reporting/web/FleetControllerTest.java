package com.tessera.risk.reporting.web;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import com.tessera.risk.reporting.FleetService;
import com.tessera.risk.reporting.hbase.SegmentMetricsRepository;
import com.tessera.risk.reporting.model.FleetSnapshot;
import com.tessera.risk.reporting.model.RiskLevel;
import com.tessera.risk.reporting.model.VehicleRisk;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests the HTTP contract the dashboard is written against.
 *
 * <p>The field names and the shape of this JSON are what a separate TypeScript
 * client depends on, and nothing in the build connects the two. A renamed field
 * compiles perfectly and breaks the dashboard at run time, so the names are pinned
 * here.
 *
 * <p>HBase is mocked out deliberately: this is about the web layer, and a test that
 * needed a cluster to check a field name would be run far less often.
 */
@WebMvcTest(FleetController.class)
class FleetControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private FleetService fleet;

    @MockBean
    private SegmentMetricsRepository segments;

    @Test
    @DisplayName("the fleet endpoint serves the fields the dashboard reads")
    void fleetSnapshotShape() throws Exception {
        when(fleet.snapshot()).thenReturn(new FleetSnapshot(
                1_767_225_600_000L, 1, true, List.of(scored())));

        mvc.perform(get("/api/fleet"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicleCount").value(1))
                .andExpect(jsonPath("$.modelScored").value(true))
                .andExpect(jsonPath("$.vehicles[0].vehicleId").value("VEH-001"))
                .andExpect(jsonPath("$.vehicles[0].driverName").value("A. Okafor"))
                .andExpect(jsonPath("$.vehicles[0].riskScore").value(64.6))
                .andExpect(jsonPath("$.vehicles[0].riskLevel").value("HIGH"))
                .andExpect(jsonPath("$.vehicles[0].probability").value(0.31))
                .andExpect(jsonPath("$.vehicles[0].predictedLabel").value(0))
                // Position, without which the map has nothing to place.
                .andExpect(jsonPath("$.vehicles[0].lat").value(42.3601))
                .andExpect(jsonPath("$.vehicles[0].lon").value(-71.0589));
    }

    @Test
    @DisplayName("an unscored fleet reports absence rather than zeroes")
    void unscoredFleetIsDistinguishable() throws Exception {
        // Before training has run there is no model, and the dashboard needs to say
        // "not scored yet" rather than render a confident prediction of zero.
        when(fleet.snapshot()).thenReturn(new FleetSnapshot(
                1_767_225_600_000L, 1, false, List.of(unscored())));

        mvc.perform(get("/api/fleet"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelScored").value(false))
                .andExpect(jsonPath("$.vehicles[0].modelScored").value(false))
                .andExpect(jsonPath("$.vehicles[0].probability").doesNotExist())
                .andExpect(jsonPath("$.vehicles[0].predictedLabel").doesNotExist())
                // The rule-based score is still there, which is why it exists.
                .andExpect(jsonPath("$.vehicles[0].riskScore").value(30.0));
    }

    @Test
    @DisplayName("an unknown vehicle is a 404, not an empty body")
    void unknownVehicleIsNotFound() throws Exception {
        when(fleet.latest("VEH-999")).thenReturn(Optional.empty());

        mvc.perform(get("/api/vehicles/VEH-999")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a caller cannot request an unbounded history scan")
    void historyWindowsAreClamped() throws Exception {
        when(fleet.history(eq("VEH-001"), any())).thenReturn(List.of(scored()));

        mvc.perform(get("/api/vehicles/VEH-001/history?windows=100000"))
                .andExpect(status().isOk());

        // Clamped to the documented ceiling rather than passed through, so one
        // request cannot ask HBase for a vehicle's entire history.
        org.mockito.Mockito.verify(fleet).history("VEH-001", 500);
    }

    private static VehicleRisk scored() {
        return new VehicleRisk("VEH-001", "DRV-001", "A. Okafor", "SAFE",
                1_767_225_600_000L, 1_767_225_900_000L,
                300, 17, 4, 1, 35.5, 49.7, 0.88, 0.72, 1.0,
                42.3601, -71.0589, 123.4, 1_767_225_899_000L,
                64.6, RiskLevel.HIGH, 0L, 0.31, true);
    }

    private static VehicleRisk unscored() {
        return new VehicleRisk("VEH-002", "DRV-002", "B. Nguyen", "AVERAGE",
                1_767_225_600_000L, 1_767_225_900_000L,
                300, 5, 0, 0, 30.0, 40.0, 0.70, 0.0, 1.0,
                42.36, -71.06, 90.0, 1_767_225_899_000L,
                30.0, RiskLevel.MODERATE, null, null, false);
    }
}
