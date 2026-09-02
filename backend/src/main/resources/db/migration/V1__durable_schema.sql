-- Tessera Fleet durable layer (SRS §3.1, §7).
-- PostGIS for spatial types/indexing; TimescaleDB for time-bucketed history.

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS timescaledb;

-- Every position fix, durably (SRS §7 "Position (durable)"; NFR-5).
CREATE TABLE positions (
    vehicle_id   text                     NOT NULL,
    ts           timestamptz              NOT NULL,
    lat          double precision         NOT NULL,
    lon          double precision         NOT NULL,
    geom         geometry(Point, 4326)    NOT NULL,
    speed_kph    double precision,
    heading_deg  double precision
);
SELECT create_hypertable('positions', 'ts', chunk_time_interval => INTERVAL '1 day');
CREATE INDEX positions_vehicle_ts_idx ON positions (vehicle_id, ts DESC);
CREATE INDEX positions_geom_gix       ON positions USING gist (geom);

-- Customer sites / geofences (SRS §7 "Site"; FR-3.1).
CREATE TABLE sites (
    site_id             text                    PRIMARY KEY,
    name                text                    NOT NULL,
    address             text,
    boundary            geometry(Geometry, 4326) NOT NULL,
    center_lat          double precision,
    center_lon          double precision,
    radius_m            double precision,
    dwell_alert_seconds integer,
    created_at          timestamptz             NOT NULL DEFAULT now()
);
CREATE INDEX sites_boundary_gix ON sites USING gist (boundary);

-- Geofence enter/exit events with computed dwell time (SRS §7; FR-3.2, FR-3.3).
CREATE TABLE geofence_events (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    vehicle_id    text        NOT NULL,
    site_id       text        NOT NULL,
    event_type    text        NOT NULL CHECK (event_type IN ('ENTER', 'EXIT')),
    ts            timestamptz NOT NULL,
    dwell_seconds integer
);
CREATE INDEX geofence_events_site_ts_idx    ON geofence_events (site_id, ts DESC);
CREATE INDEX geofence_events_vehicle_ts_idx ON geofence_events (vehicle_id, ts DESC);

-- Jobs (SRS §7 "Job"), persisted write-through from the live JobService.
CREATE TABLE jobs (
    job_id              text        PRIMARY KEY,
    destination_address text,
    dest_lat            double precision NOT NULL,
    dest_lon            double precision NOT NULL,
    assigned_vehicle_id text,
    status              text        NOT NULL,
    created_at          timestamptz NOT NULL,
    assigned_at         timestamptz,
    completed_at        timestamptz
);
