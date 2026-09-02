-- Phase 3 (FR-4): fields the historical reports need on the jobs table.
-- "Arrival" is a geofence ENTER at the destination site.

ALTER TABLE jobs ADD COLUMN route                  text;
ALTER TABLE jobs ADD COLUMN site_id                text;
ALTER TABLE jobs ADD COLUMN driver_name            text;
ALTER TABLE jobs ADD COLUMN expected_arrival_at    timestamptz;
ALTER TABLE jobs ADD COLUMN actual_arrival_at      timestamptz;

CREATE INDEX jobs_completed_at_idx ON jobs (completed_at);
CREATE INDEX jobs_site_id_idx      ON jobs (site_id);
CREATE INDEX jobs_route_idx        ON jobs (route);
