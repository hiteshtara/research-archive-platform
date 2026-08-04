-- Corrective migration for V058: the partial unique index it created
-- on archive.proposal_award(award_funding_proposal_id) (WHERE
-- award_funding_proposal_id IS NOT NULL) cannot be used as an
-- INSERT ... ON CONFLICT (award_funding_proposal_id) target unless the
-- same WHERE predicate is repeated on the ON CONFLICT clause itself -
-- Postgres rejected it outright ("no unique or exclusion constraint
-- matching the ON CONFLICT specification"), caught live loading the
-- real fixture (Proposal family 205). In practice
-- award_funding_proposal_id is always populated (it is
-- AWARD_FUNDING_PROPOSALS' own real, never-null Oracle PK) - the
-- nullable/partial-index hedge in V058 was unnecessary caution, not a
-- real requirement. Replaced with a plain (non-partial) unique index,
-- which INSERT ... ON CONFLICT (award_funding_proposal_id) can target
-- directly.

DROP INDEX IF EXISTS archive.uq_proposal_award_funding_proposal_id;

CREATE UNIQUE INDEX IF NOT EXISTS uq_proposal_award_funding_proposal_id
    ON archive.proposal_award(award_funding_proposal_id);
