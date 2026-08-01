/*
 * Award attachment references (business/historical grain - one row per
 * KCOEUS.AWARD_ATTACHMENT row). Repeated FILE_ID values are expected: many
 * references may point at the same physical file. This query never reads
 * ATTACHMENT_FILE/FILE_DATA at all - see export_award_attachment_files.sql
 * for the deduplicated physical-file metadata.
 *
 * TYPE_CODE has not been independently confirmed against a live DESCRIBE
 * in this repo's evidence (docs/ATTACHMENT_MODULE_INVENTORY.md's Award
 * contract table does not list it) - assumed present by analogy with
 * KCOEUS.SUBAWARD_ATTACHMENTS.ATTACHMENT_TYPE_CODE. If this column name is
 * wrong, this query fails loudly (ORA-00904) rather than silently
 * producing wrong data - verify during a live --limit run.
 */
SELECT
    aa.AWARD_ATTACHMENT_ID       AS award_attachment_id,
    aa.AWARD_ID                  AS award_id,
    aa.AWARD_NUMBER               AS award_number,
    aa.SEQUENCE_NUMBER            AS sequence_number,
    aa.DOCUMENT_ID                AS document_id,
    aa.FILE_ID                    AS file_id,
    aa.TYPE_CODE                  AS type_code,
    aa.DESCRIPTION                AS description,
    aa.DOCUMENT_STATUS_CODE       AS document_status_code,
    aa.UPDATE_TIMESTAMP            AS oracle_update_timestamp,
    aa.UPDATE_USER                 AS oracle_update_user
FROM KCOEUS.AWARD_ATTACHMENT aa
ORDER BY
    aa.AWARD_NUMBER,
    aa.SEQUENCE_NUMBER,
    aa.AWARD_ATTACHMENT_ID
