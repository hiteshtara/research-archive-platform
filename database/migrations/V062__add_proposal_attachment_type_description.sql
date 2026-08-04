-- attachment_type_code alone does not carry a human-readable label -
-- and, critically, does NOT match the informal bucket names a user
-- might read off Kuali's screen from attachment *titles* (e.g. a file
-- titled "...Guidelines" or "Cornell Transfer Package"). Oracle's own
-- PROPOSAL_ATTACHMENT_TYPE lookup (live-verified, 7 rows) is the real
-- taxonomy: 1=Internal Documents, 2=Budget, 3=Subaward Documents,
-- 4=Proposal Package, 5=Communications, 6=Required Signature Page,
-- 7=Other. Live-verified against fixture Institutional Proposal
-- 01157400: the "Ryan_NSF_1.11.17_Guidelines" file is actually
-- type_code 7 ("Other"), and "Cornell Transfer Package" is type_code 4
-- ("Proposal Package") - neither has its own dedicated Oracle type.
-- Denormalized via a JOIN in the extraction SQL (see
-- 02_proposal_attachments.sql), the same convention already used for
-- proposal_version.status_description - no separate
-- archive.proposal_attachment_type reference table needed for a
-- static 7-row lookup.

ALTER TABLE archive.proposal_attachment
    ADD COLUMN IF NOT EXISTS attachment_type_description VARCHAR(200);
