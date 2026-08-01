-- Award Comment: confirmed against the upstream Kuali Coeus source
-- (org.kuali.kra.award.home.AwardComment -> AWARD_COMMENT). See
-- docs/architecture/AWARD_COMMENT_DESIGN.md.
--
-- Confirmed distinct from archive.award_notepad: different Java
-- class/package, different table, different scoping (this table
-- belongs to a specific Award VERSION - a real, backfilled
-- sequence_number column - whereas award_notepad is scoped to the
-- whole award_number family with no sequence_number column at all),
-- and a different shape (comment_type_code/checklist_print_flag here,
-- entry_number/note_topic/restricted_view there).
--
-- award_id/award_number/sequence_number are nullable at the Oracle DDL
-- level (the third Award child table found with this property, after
-- award_approved_subaward and award_cost_share) - kept NOT NULL here
-- since the extraction path structurally guarantees non-null values
-- for any row actually read. comment_type_code is a bare, unjoined
-- lookup code (real Oracle FK to COMMENT_TYPE, not archived).
-- checklist_print_flag is a real OjbCharBooleanConversion field,
-- stored as raw text per this schema's existing convention.

CREATE TABLE IF NOT EXISTS archive.award_comment (
    award_comment_id BIGINT PRIMARY KEY,
    award_id BIGINT NOT NULL
        REFERENCES archive.award_version(award_id) ON DELETE CASCADE,
    award_number VARCHAR(50),
    sequence_number INTEGER,

    comment_type_code VARCHAR(10),
    checklist_print_flag VARCHAR(10),
    comments TEXT,

    source_update_timestamp TIMESTAMP,
    source_update_user VARCHAR(100),
    source_version_number BIGINT,

    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_comment_award
    ON archive.award_comment (award_id, award_comment_id);
