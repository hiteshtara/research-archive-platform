-- Forward migration from the clean committed baseline (HEAD as of
-- 2026-08-15: highest committed migration is V076). Deliberately a new
-- number, not a copy of the untracked, uncommitted
-- V073__extend_subaward_attachment_archive_status.sql sitting in the
-- working tree - that file's ownership/history is unresolved (see
-- docs/project-memory/CURRENT_STATE.md's "Open items"), so a clean
-- checkout of this commit must never depend on it existing. That file
-- is left untouched on disk; this migration does not reference it.
--
-- Two independent, both real, defects being fixed together because a
-- 2026-08-15 read-only audit (Postgres SELECT + Oracle SELECT + S3
-- HEAD only, no writes - see docs/project-memory/CURRENT_STATE.md)
-- found they block the same next step (the Subaward attachment binary
-- load) and both touch archive.subaward_attachment_archive's schema:
--
-- 1. archive_status widening (a) + b) below): the CHECK constraint
--    V019 originally defined only ever allowed the terminal states
--    ARCHIVED/MISSING/FAILED - there was no "not yet attempted" or
--    "actively being uploaded" state, unlike Award
--    (archive.attachment_object.upload_status, PENDING/UPLOADING added
--    by V036) and Proposal (archive.proposal_attachment.upload_status,
--    NOT_REQUESTED/IN_PROGRESS from V060). etl/attachment_orchestrator.py's
--    Subaward metadata stage (_upsert_subaward_attachments) already
--    unconditionally inserts new archive-state rows with
--    archive_status = 'PENDING' - every one of those inserts fails
--    against the original V019 constraint. No existing row's
--    archive_status changes - every row currently in the table already
--    has a terminal status (ARCHIVED/MISSING/FAILED), so this half is a
--    pure widening, exactly like V036's own precedent for Award. Proven
--    via a real-Postgres Testcontainers migration test
--    (api/src/test/java/edu/bu/archive/adapter/out/persistence/
--    SubawardAttachmentArchiveMigrationTest.java): full committed chain
--    applies cleanly, the widened CHECK accepts all five states and
--    still rejects an invalid one, DEFAULT 'PENDING' works, re-running
--    this migration is idempotent, and a pre-migration ARCHIVED fixture
--    row is byte-for-byte unchanged afterward.
--
-- 2. Dropping ux_subaward_attachment_archive_object (c) below): V019
--    defined this UNIQUE (s3_bucket, s3_key) constraint when this
--    table's only writer keyed one S3 object per *reference row*
--    (subawards/{subaward_id}/{attachment_id}/{filename} - the
--    generic-plugin path in
--    etl/archive_etl/attachments/plugins/subaward.py, which produced
--    the 1,764 real ARCHIVED rows that exist in dev RDS today, one
--    distinct key per row). etl/attachment_orchestrator.py's newer
--    subaward_binary_stage instead keys one S3 object per *physical
--    file* (subawards/{file_data_id}/{filename}) and intentionally
--    updates every reference row sharing a file_data_id to the
--    identical (s3_bucket, s3_key) pair in one batch UPDATE - the
--    orchestrator module's own docstring states this explicitly ("a
--    file referenced by many proposal/subaward versions is still only
--    streamed from Oracle and PUT to S3 once"), and Proposal's
--    equivalent table (archive.proposal_attachment, V060) has no such
--    UNIQUE constraint for exactly this reason (documented in V060's
--    own migration comment: ~2.7x FILE_DATA_ID reuse across 405,779
--    rows, working correctly today). Leaving V019's constraint in place
--    would make the orchestrator's very next shared-file UPDATE raise
--    "duplicate key value violates unique constraint
--    ux_subaward_attachment_archive_object" and roll back - confirmed
--    this would not be a rare case (11 of 13 physical files in the
--    Subaward Code 3595 pilot population are multiply-referenced, per
--    the live 2026-08-15 reconciliation). A full codebase audit (Java
--    API repositories, every ETL query/UPDATE, the cross-domain search
--    repository) found no code anywhere that looks up a
--    subaward_attachment_archive row BY (s3_bucket, s3_key) - every
--    reader/writer already keys by attachment_id (the primary key,
--    unaffected by this change) or file_data_id, so dropping this
--    constraint introduces no new ambiguity for any existing caller.
--    IF EXISTS so this migration is itself safe to re-run and safe
--    against a database where some other path already dropped it.
--
-- Neither change mutates or deletes any existing row - both are pure
-- schema widenings/removals.

ALTER TABLE archive.subaward_attachment_archive
    DROP CONSTRAINT IF EXISTS ck_subaward_attachment_archive_status;

ALTER TABLE archive.subaward_attachment_archive
    ADD CONSTRAINT ck_subaward_attachment_archive_status
        CHECK (
            archive_status IN (
                'PENDING',
                'UPLOADING',
                'ARCHIVED',
                'MISSING',
                'FAILED'
            )
        );

ALTER TABLE archive.subaward_attachment_archive
    ALTER COLUMN archive_status SET DEFAULT 'PENDING';

ALTER TABLE archive.subaward_attachment_archive
    DROP CONSTRAINT IF EXISTS ux_subaward_attachment_archive_object;
