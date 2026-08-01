-- Award Notepad: free-text notes attached to an Award, confirmed
-- against both the upstream Kuali Coeus OJB mapping
-- (org.kuali.kra.award.notesandattachments.notes.AwardNotepad ->
-- AWARD_NOTEPAD) and the real Oracle bootstrap DDL
-- (coeus-db V300_107__schema.sql, V310_1_030__TBL_AWARD_NOTEPAD.sql
-- for CREATE_USER). See docs/architecture/AWARD_NOTEPAD_DESIGN.md.
--
-- Unlike every other Award child table archived so far, AWARD_NOTEPAD
-- has NO sequence_number column at all - notes are scoped to the whole
-- award_number family (confirmed by the real, family-wide
-- UQ_AWARD_NOTEPAD index on (AWARD_NUMBER, ENTRY_NUMBER), and by
-- Award.add(AwardNotepad)'s Java logic, which sets awardNumber and a
-- family-wide entryNumber independent of any sequence_number). AWARD_ID
-- is retained here anyway (also NOT NULL in Oracle) purely so this
-- table can reuse the same family-widening join
-- (read_award_children_matching_award_ids) every other child table
-- already uses - not because notes are version-scoped.
--
-- award_notepad_id draws from its own dedicated sequence
-- (SEQ_AWARD_NOTEPAD_ID), not SEQUENCE_AWARD_ID - still a safe,
-- table-scoped UPSERT conflict key regardless.
--
-- (award_number, entry_number) is indexed but NOT a UNIQUE constraint:
-- Oracle's own UQ_AWARD_NOTEPAD is a plain CREATE INDEX despite its
-- name, not actually enforced as unique - not re-enforced here either,
-- to avoid rejecting real Oracle data on an assumption Oracle itself
-- doesn't make.
--
-- source_create_timestamp/source_create_user are new columns not
-- present on any other Award child table archived so far - this table
-- is the first with both a CREATE_* and an UPDATE_* provenance pair.

CREATE TABLE IF NOT EXISTS archive.award_notepad (
    award_notepad_id          BIGINT PRIMARY KEY,
    award_id                  BIGINT NOT NULL
                                  REFERENCES archive.award_version(award_id)
                                  ON DELETE CASCADE,
    award_number              VARCHAR(50) NOT NULL,
    entry_number              INTEGER NOT NULL,

    note_topic                VARCHAR(200),
    comments                  TEXT,
    restricted_view           VARCHAR(10),

    source_create_timestamp   TIMESTAMP,
    source_create_user        VARCHAR(100),
    source_update_timestamp   TIMESTAMP,
    source_update_user        VARCHAR(100),
    source_version_number     BIGINT,

    loaded_at                 TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                   BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_notepad_award
    ON archive.award_notepad (award_id, award_notepad_id);

CREATE INDEX IF NOT EXISTS ix_award_notepad_number
    ON archive.award_notepad (award_number, entry_number);
