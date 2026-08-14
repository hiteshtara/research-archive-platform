-- Preserves the legacy Kuali RESTRICTED value (currently only sourced
-- from KCOEUS.NEGOTIATION_ATTACHMENT.RESTRICTED, via
-- NegotiationAttachmentPlugin) as a dedicated, reliably queryable
-- column, promoted out of source_metadata JSONB for typed API access -
-- mirroring how every other first-class source flag in this schema
-- (e.g. archive.negotiation_activity.restricted) already has its own
-- column rather than living only in JSONB. The original JSONB value is
-- NOT removed or overwritten - this is additive only.
--
-- Informational only: this column is never used for access control.
-- Every authenticated member of the ArchiveAttachmentViewer group sees
-- both 'Y' and 'N' (and any other/legacy value, or NULL when the source
-- module has no such concept) attachments identically. See
-- docs/architecture/NEGOTIATION_ATTACHMENT_ACCESS_DESIGN.md.

ALTER TABLE archive.archived_attachment
    ADD COLUMN IF NOT EXISTS legacy_restricted_flag VARCHAR(10);

-- Backfill from the JSONB value already captured by every Negotiation
-- attachment loaded so far - authoritative source metadata, not a
-- re-extraction from Oracle. Other modules (AWARD/PROPOSAL/SUBAWARD)
-- have no 'restricted' key in source_metadata, so this UPDATE is a
-- no-op for them and the column stays NULL - honest, not a fabricated
-- default.
UPDATE archive.archived_attachment
   SET legacy_restricted_flag = source_metadata->>'restricted'
 WHERE module_code = 'NEGOTIATION'
   AND source_metadata ? 'restricted';

CREATE INDEX IF NOT EXISTS ix_archived_attachment_legacy_restricted
    ON archive.archived_attachment (module_code, legacy_restricted_flag);
