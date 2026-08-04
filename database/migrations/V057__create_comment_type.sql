-- Shared reference-data entity: Oracle's COMMENT_TYPE lookup table,
-- archived once rather than denormalized onto every archive.
-- award_comment row - the same "reference table, not duplicated per
-- row" precedent already established for
-- archive.unit_administrator_type (V056). Real, Oracle-enforced FK
-- source: AWARD_COMMENT.COMMENT_TYPE_CODE REFERENCES
-- COMMENT_TYPE(COMMENT_TYPE_CODE) - see
-- docs/architecture/AWARD_COMMENT_DESIGN.md.
--
-- Full reference-data load: a small, bounded lookup table (~15-20 rows
-- in generic Kuali Coeus seed data; BU's real row count/codes have not
-- yet been verified live - the codes/descriptions used elsewhere in
-- this project's docs are generic Kuali demo seed data, not confirmed
-- against BU's actual Oracle instance, matching the same caveat already
-- raised for unit_administrator_type before its live verification).
--
-- award_comment_screen_flag is the real filter Kuali's own
-- AwardCommentServiceImpl.retrieveCommentTypes() applies to decide
-- which comment types belong on the Award Comments screen at all -
-- comment types with this flag = 'N' (e.g. "Current Action Comments")
-- are real archived data but never shown there; kept queryable here
-- for a future generic Explorer view rather than discarded.

CREATE TABLE IF NOT EXISTS archive.comment_type (
    comment_type_code VARCHAR(10) PRIMARY KEY,
    description VARCHAR(300),
    template_flag VARCHAR(10),
    checklist_flag VARCHAR(10),
    award_comment_screen_flag VARCHAR(10),

    source_update_timestamp TIMESTAMP,
    source_update_user VARCHAR(100),
    source_version_number BIGINT,

    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id BIGINT REFERENCES archive.load_run(load_id)
);
