package com.tessera.fleet.durable;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.tessera.fleet.geofence.SiteGeometry;

/**
 * Verifies the real PostgreSQL + PostGIS + TimescaleDB SQL of {@link PostgresDurableStore}
 * and the Flyway schema. Docker-gated: skipped when no Docker engine is available
 * (e.g. this dev machine); runs in CI / anywhere Docker is present.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("dockerAvailable")
class PostgisDurableStoreDockerIT {

    @SuppressWarnings("resource")
    private final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("tessera").withUsername("tessera").withPassword("tessera");

    private HikariDataSource dataSource;
    private PostgresDurableStore store;
    private JdbcTemplate jdbc;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeAll
    void setUp() {
        pg.start();
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(pg.getJdbcUrl());
        dataSource.setUsername(pg.getUsername());
        dataSource.setPassword(pg.getPassword());
        Flyway.configure().dataSource((DataSource) dataSource)
                .locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = new PostgresDurableStore(jdbc);
    }

    @AfterAll
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
        pg.stop();
    }

    @Test
    void schemaHasPostgisTimescaleAndTheHypertable() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname IN ('postgis','timescaledb')",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM timescaledb_information.hypertables WHERE hypertable_name='positions'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void positionsAndGeofenceEventsInsertAndCount() {
        store.savePositions(List.of(
                new PositionRecord("V1", 42.3601, -71.0589, 25, 90, 1_700_000_000_000L),
                new PositionRecord("V1", 42.3602, -71.0590, 26, 92, 1_700_000_001_000L)));
        store.saveGeofenceEvents(List.of(
                GeofenceEventRecord.enter("V1", "S1", 1_700_000_000_500L),
                GeofenceEventRecord.exit("V1", "S1", 1_700_000_600_500L, 600)));

        assertThat(store.positionCount()).isGreaterThanOrEqualTo(2);
        assertThat(store.recentGeofenceEvents("V1", "S1", 10)).hasSize(2);
        assertThat(store.lastGeofenceEvent("V1", "S1")).get()
                .extracting(GeofenceEventRecord::dwellSeconds).isEqualTo(600);

        // The stored point geometry matches the lat/lon columns.
        Double dist = jdbc.queryForObject(
                "SELECT ST_Distance(geom::geography, ST_SetSRID(ST_MakePoint(lon, lat),4326)::geography) "
                        + "FROM positions LIMIT 1", Double.class);
        assertThat(dist).isNotNull().isLessThan(0.01);
    }

    @Test
    void sitesRoundTripAsWktAndContainmentWorksInPostgis() {
        String wkt = SiteGeometry.fromRadius(42.3560, -71.0635, 150).toWkt();
        store.saveSite(new SiteRecord("SITE-PG", "PG Depot", "addr", wkt,
                42.3560, -71.0635, 150.0, 900, 1_700_000_000_000L));

        List<SiteRecord> sites = store.loadSites();
        assertThat(sites).extracting(SiteRecord::siteId).contains("SITE-PG");
        assertThat(sites.stream().filter(s -> s.siteId().equals("SITE-PG")).findFirst()
                .orElseThrow().boundaryWkt()).startsWith("POLYGON");

        Boolean insideHit = jdbc.queryForObject(
                "SELECT ST_Contains(boundary, ST_SetSRID(ST_MakePoint(?, ?), 4326)) "
                        + "FROM sites WHERE site_id = 'SITE-PG'",
                Boolean.class, -71.0635, 42.3560);
        assertThat(insideHit).isTrue();

        store.deleteSite("SITE-PG");
        assertThat(store.loadSites()).extracting(SiteRecord::siteId).doesNotContain("SITE-PG");
    }

    @Test
    void jobsUpsert() {
        store.saveJob(new JobRecord("JOB-PG", "North Loop", "addr", 42.0, -71.0, "S1",
                null, null, "UNASSIGNED", 1_700_000_000_000L, null, null, null, null));
        store.saveJob(new JobRecord("JOB-PG", "North Loop", "addr", 42.0, -71.0, "S1",
                "CAR-9", "Driver A", "ASSIGNED", 1_700_000_000_000L, 1_700_000_050_000L,
                1_700_000_650_000L, null, null));
        List<JobRecord> jobs = store.loadJobs();
        assertThat(jobs.stream().filter(j -> j.jobId().equals("JOB-PG")).findFirst().orElseThrow())
                .extracting(JobRecord::status, JobRecord::assignedVehicleId, JobRecord::driverName)
                .containsExactly("ASSIGNED", "CAR-9", "Driver A");
    }

    @Test
    void reportingFactsComeBackFromRealSql() {
        long base = 1_700_100_000_000L;
        store.saveJob(new JobRecord("JOB-R1", "R1", "a", 42.0, -71.0, "S1", "V1", "D1",
                "COMPLETED", base, base + 60_000L, base + 600_000L, base + 500_000L, base + 500_000L));
        store.saveJob(new JobRecord("JOB-R2", "R1", "b", 42.0, -71.0, "S1", "V2", "D2",
                "COMPLETED", base, base + 60_000L, base + 600_000L, base + 900_000L, base + 900_000L));
        store.saveGeofenceEvents(List.of(
                GeofenceEventRecord.exit("V1", "S1", base + 500_000L, 420),
                GeofenceEventRecord.exit("V2", "S1", base + 900_000L, 780)));

        assertThat(store.completedJobs(base, base + 1_000_000L)).hasSize(2);
        assertThat(store.completedJobs(base, base + 1_000_000L).stream()
                .filter(f -> f.onTime(0)).count()).isEqualTo(1);
        assertThat(store.siteVisits(base, base + 1_000_000L)).hasSize(2);
        assertThat(store.reportingWindow().completedJobs()).isGreaterThanOrEqualTo(2);
    }
}
