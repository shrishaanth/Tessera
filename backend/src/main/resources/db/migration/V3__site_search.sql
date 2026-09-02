-- Phase 4 (FR-6.3): fuzzy customer-site search.
-- SRS §3.2 specifies PostgreSQL full-text (tsvector); pg_trgm adds typo tolerance.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

ALTER TABLE sites ADD COLUMN search_tsv tsvector
    GENERATED ALWAYS AS (
        to_tsvector('simple', coalesce(name, '') || ' ' || coalesce(address, ''))
    ) STORED;

CREATE INDEX sites_search_tsv_idx ON sites USING gin (search_tsv);
CREATE INDEX sites_name_trgm_idx  ON sites USING gin (name gin_trgm_ops);
