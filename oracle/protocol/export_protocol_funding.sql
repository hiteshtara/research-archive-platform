/*
 * Protocol Funding export.
 *
 * The source PROTOCOL_ID is retained for audit. Measured BU evidence found
 * 192 sequence mismatches among 43,405 rows, so the archive parent is
 * resolved later from PROTOCOL_NUMBER + SEQUENCE_NUMBER.
 */
SELECT
    pfs.PROTOCOL_FUNDING_SOURCE_ID AS protocol_funding_source_id,
    pfs.PROTOCOL_ID                AS source_protocol_id,
    pfs.PROTOCOL_NUMBER            AS protocol_number,
    pfs.SEQUENCE_NUMBER            AS sequence_number,
    pfs.FUNDING_SOURCE_TYPE_CODE   AS funding_source_type_code,
    pfs.FUNDING_SOURCE             AS funding_source_number,
    pfs.FUNDING_SOURCE_NAME        AS funding_source_name,
    pfs.UPDATE_TIMESTAMP           AS source_update_timestamp,
    pfs.UPDATE_USER                AS source_update_user,
    pfs.VER_NBR                    AS source_version_number,
    pfs.OBJ_ID                     AS source_object_id
FROM KCOEUS.PROTOCOL_FUNDING_SOURCE pfs
ORDER BY
    pfs.PROTOCOL_NUMBER,
    pfs.SEQUENCE_NUMBER,
    pfs.PROTOCOL_FUNDING_SOURCE_ID;
