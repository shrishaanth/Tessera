package com.tessera.fleet.durable;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.tessera.fleet.config.FleetProperties;

/**
 * Wires the {@link DurableStore}.
 *
 * <p>Default ({@code tessera.durable.mode=in-memory}): an {@link InMemoryDurableStore}
 * — the system runs with no database at all, which is fine because the durable
 * layer is never on the live path (SRS §2.5).
 *
 * <p>{@code tessera.durable.mode=postgres} (the {@code durable} profile): a
 * PostgreSQL + PostGIS + TimescaleDB store. The DataSource, JdbcTemplate and
 * Flyway are built here by hand so Boot's JDBC auto-configuration (excluded in
 * {@code application.yml}) never trips when no database is configured.
 */
@Configuration
public class DurableConfig {

    private static final Logger log = LoggerFactory.getLogger(DurableConfig.class);

    @Bean
    @ConditionalOnProperty(prefix = "tessera.durable", name = "mode",
            havingValue = "in-memory", matchIfMissing = true)
    public DurableStore inMemoryDurableStore() {
        log.info("Durable layer: in-memory (no database). Live dispatch is unaffected.");
        return new InMemoryDurableStore();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "tessera.durable", name = "mode", havingValue = "postgres")
    public HikariDataSource durableDataSource(FleetProperties properties) {
        FleetProperties.DataSource ds = properties.durable().datasource();
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(ds.url());
        hc.setUsername(ds.username());
        hc.setPassword(ds.password());
        hc.setPoolName("tessera-durable");
        hc.setMaximumPoolSize(8);
        hc.setConnectionTimeout(5000);
        hc.setInitializationFailTimeout(-1); // start even if the DB is down (NFR-3)
        return new HikariDataSource(hc);
    }

    @Bean
    @ConditionalOnProperty(prefix = "tessera.durable", name = "mode", havingValue = "postgres")
    public Flyway durableFlyway(DataSource durableDataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(durableDataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load();
        try {
            flyway.migrate();
        } catch (Exception e) {
            // Migration failure must not stop the app — live dispatch runs regardless.
            log.error("Durable schema migration failed; durable writes will be degraded: {}",
                    e.toString());
        }
        return flyway;
    }

    @Bean
    @ConditionalOnProperty(prefix = "tessera.durable", name = "mode", havingValue = "postgres")
    public DurableStore postgresDurableStore(DataSource durableDataSource, Flyway durableFlyway) {
        log.info("Durable layer: PostgreSQL + PostGIS + TimescaleDB");
        return new PostgresDurableStore(new JdbcTemplate(durableDataSource));
    }
}
