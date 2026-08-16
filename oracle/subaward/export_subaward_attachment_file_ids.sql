/*
 * (subaward_id, file_data_id) pairs from KCOEUS.SUBAWARD_ATTACHMENTS -
 * NOT deduplicated to distinct FILE_DATA_ID here, because candidate
 * selection must be scoped to subaward_id (via
 * OracleDataSource.read_filtered(column="subaward_id", values=...)) -
 * archive.subaward's own core-record population is itself far short of
 * Oracle's full KCOEUS.SUBAWARD population as of 2026-08-12 (513 of
 * 88,818 real subawards - see
 * docs/architecture/ARCHIVE_ATTACHMENT_LOAD_INVENTORY.md), and
 * archive.subaward_attachment.subaward_id has a real FK to
 * archive.subaward(subaward_id) - attempting to load attachment
 * metadata for a subaward_id with no core record yet would violate that
 * FK. etl/attachment_orchestrator.py's
 * _run_create_subaward_attachment_batch() applies this filter, then
 * deduplicates to distinct file_data_id in Python.
 *
 * FILE_DATA_ID here is Oracle's FILE_DATA.ID - a UUID string, never a
 * numeric surrogate key (see database/migrations/V072 for the Award
 * incident this class of mistake caused). Never int()-coerce this
 * column.
 */
SELECT sa.SUBAWARD_ID AS subaward_id, sa.FILE_DATA_ID AS file_data_id
FROM KCOEUS.SUBAWARD_ATTACHMENTS sa
WHERE sa.FILE_DATA_ID IS NOT NULL
ORDER BY sa.SUBAWARD_ID, sa.FILE_DATA_ID;
