CREATE UNLOGGED TABLE IF NOT EXISTS archive.irb_protocol_version_stage
(LIKE archive.irb_protocol_version INCLUDING DEFAULTS);

CREATE UNLOGGED TABLE IF NOT EXISTS archive.irb_submission_stage
(LIKE archive.irb_submission INCLUDING DEFAULTS);

CREATE UNLOGGED TABLE IF NOT EXISTS archive.irb_funding_source_stage
(LIKE archive.irb_funding_source INCLUDING DEFAULTS);

CREATE UNLOGGED TABLE IF NOT EXISTS archive.irb_timeline_event_stage
(LIKE archive.irb_timeline_event INCLUDING DEFAULTS);

ALTER TABLE archive.irb_protocol_version_stage
    DROP COLUMN IF EXISTS loaded_at;

ALTER TABLE archive.irb_submission_stage
    DROP COLUMN IF EXISTS submission_id,
    DROP COLUMN IF EXISTS loaded_at;

ALTER TABLE archive.irb_funding_source_stage
    DROP COLUMN IF EXISTS funding_source_id,
    DROP COLUMN IF EXISTS loaded_at;

ALTER TABLE archive.irb_timeline_event_stage
    DROP COLUMN IF EXISTS timeline_event_id,
    DROP COLUMN IF EXISTS loaded_at;
