-- archive.proposal_attachment.content_type (VARCHAR(200), V060) is too
-- narrow for real Oracle data - live-verified during the first
-- 5,000-family Proposal batch load (batch_id=56): PROPOSAL_ATTACHMENTS
-- .CONTENT_TYPE reaches 250 characters for at least 9 real rows across
-- 4 proposal_numbers (e.g. proposal_attachments_id 664, 691, 696, 697,
-- 701 under proposal families 01151144/01153562/01154770/01156582).
-- The value itself is malformed/garbled (heavily backslash-escaped
-- junk terminating in "application/pdf") rather than a legitimate long
-- MIME type - but it is real, persisted Oracle data, and this archive
-- never truncates or rejects a row for that reason (see the project's
-- own "preserve every row verbatim" convention). Widened to TEXT
-- (unbounded) rather than a larger fixed VARCHAR, since content_type
-- has no real business-meaningful length constraint and Oracle has
-- already proven capable of storing corrupted values here at least
-- once.

ALTER TABLE archive.proposal_attachment
    ALTER COLUMN content_type TYPE TEXT;
