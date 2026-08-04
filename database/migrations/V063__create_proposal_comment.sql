-- PROPOSAL_COMMENTS - live-verified against Oracle. Mirrors
-- archive.award_comment's shape (V045) exactly: PROPOSAL_COMMENTS
-- carries proposal_id/proposal_number/sequence_number directly (all
-- NOT NULL at the Oracle DDL level), COMMENT_TYPE_CODE is a bare
-- lookup code into the shared archive.comment_type table (V057, the
-- same table Award's own comments already reuse), kept unjoined here.
--
-- Real comment_type codes observed on PROPOSAL_COMMENTS (live-verified,
-- family 205 and 01157400 fixtures): 12 ("Proposal Comments", the
-- dominant category, 34,080 rows archive-wide) and 13 ("Proposal IP
-- Review Comments", 32,833 rows) - the two the user asked to display.
-- Two others also appear in real data (8 "Indirect Cost  Comments",
-- 9 "Cost Sharing Comments", 6,628 rows each) but archive.comment_type
-- has no institutional-proposal-equivalent of
-- award_comment_screen_flag/subaward_comment_screen_flag to
-- data-drive which categories belong on this screen - the API layer
-- filters to codes 12/13 explicitly, per instruction, not via a
-- reusable flag column that does not exist for this domain.

CREATE TABLE IF NOT EXISTS archive.proposal_comment (
    proposal_comment_id      BIGINT PRIMARY KEY,
    proposal_id               BIGINT NOT NULL,
    proposal_number           VARCHAR(50),
    sequence_number           INTEGER,

    comment_type_code         VARCHAR(10),
    comments                  TEXT,

    source_update_timestamp   TIMESTAMP,
    source_update_user        VARCHAR(100),

    loaded_at                 TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                   BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_proposal_comment_proposal
    ON archive.proposal_comment (proposal_id);

CREATE INDEX IF NOT EXISTS ix_proposal_comment_number
    ON archive.proposal_comment (proposal_number);

CREATE INDEX IF NOT EXISTS ix_proposal_comment_type
    ON archive.proposal_comment (comment_type_code);
