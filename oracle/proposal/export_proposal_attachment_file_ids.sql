/*
 * Distinct physical-file identifiers actually referenced by
 * KCOEUS.PROPOSAL_ATTACHMENTS - one row per unique FILE_DATA_ID, not one
 * row per reference (a single physical file can be attached to many
 * proposal_attachment rows across a proposal's version history). Used
 * only for batch selection (etl/attachment_orchestrator.py's
 * create-batch step) - metadata for the matching reference rows is
 * read separately via sql/extract/proposal/02_proposal_attachments.sql
 * with a FILE_DATA_ID IN (...) filter (OracleDataSource.read_filtered),
 * so this query never needs to change if that file's column list does.
 *
 * FILE_DATA_ID here is Oracle's FILE_DATA.ID - a UUID string (e.g.
 * 'f6f4d6d2-9a3f-4a32-a4e4-b6ffb8647847'), never a numeric surrogate
 * key - see database/migrations/V072 for the incident this class of
 * mistake caused for Award. Never int()-coerce this column.
 */
SELECT DISTINCT pa.FILE_DATA_ID AS file_data_id
FROM KCOEUS.PROPOSAL_ATTACHMENTS pa
WHERE pa.FILE_DATA_ID IS NOT NULL
ORDER BY pa.FILE_DATA_ID;
