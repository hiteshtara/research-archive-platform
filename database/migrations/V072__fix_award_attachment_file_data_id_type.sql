-- Fixes a real, previously-undiscovered defect: Oracle's
-- ATTACHMENT_FILE.FILE_DATA_ID (and the FILE_DATA.ID it joins to) is a
-- UUID string, not a numeric surrogate key - confirmed directly against
-- live Oracle data (e.g. 'f6f4d6d2-9a3f-4a32-a4e4-b6ffb8647847').
-- archive.attachment_object.file_data_id was defined as BIGINT, which
-- cannot hold a UUID at all. The other three attachment domains already
-- got this right: archive.subaward_attachment(_archive).file_data_id
-- and archive.proposal_attachment.file_data_id are both VARCHAR(100)
-- (see V018/V019/V060) - this migration brings Award into line with
-- that same, already-correct convention, not inventing a new one.
--
-- Net effect of the BIGINT mistake: every EXTERNAL-sourced Award
-- attachment (blob lives in FILE_DATA, not inline in
-- ATTACHMENT_FILE.FILE_DATA) could never be uploaded -
-- load_award_attachments.py's prepare_files() coerced the UUID to NaN
-- via convert_numeric(), and resolve_blob_location() would have failed
-- on int(file_data_id) even if it hadn't. Confirmed archive-wide:
-- 5,821 of 5,821 EXTERNAL-sourced attachment_object rows were affected
-- (100%), not just the CARB-X family that surfaced this.
--
-- Existing file_data_id values are already all NULL for every affected
-- row (the numeric coercion could never have stored a real UUID), so a
-- plain ALTER COLUMN TYPE has nothing incorrect to cast - this
-- migration only widens the column; the actual backfill of correct
-- UUID values happens via a normal metadata reload (--load-file-ids /
-- a batch), not by this migration.

ALTER TABLE archive.attachment_object
    ALTER COLUMN file_data_id TYPE VARCHAR(100) USING file_data_id::VARCHAR(100);
