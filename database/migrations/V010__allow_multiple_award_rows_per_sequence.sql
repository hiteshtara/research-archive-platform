ALTER TABLE archive.award_version
    DROP CONSTRAINT IF EXISTS ux_award_version_number_sequence;

CREATE INDEX IF NOT EXISTS ix_award_version_family_sequence
    ON archive.award_version (
        award_number,
        sequence_number DESC,
        award_id
    );

CREATE INDEX IF NOT EXISTS ix_award_version_source_row
    ON archive.award_version (award_id);
