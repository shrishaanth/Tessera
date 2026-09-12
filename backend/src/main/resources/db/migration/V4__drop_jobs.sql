-- The dispatch/job-assignment feature was removed: the system now runs purely on
-- a real GTFS-Realtime position feed (live map, geofencing, dwell reporting,
-- trajectory replay). The jobs table and its indexes (V1, V2) are no longer used.

DROP TABLE IF EXISTS jobs;
