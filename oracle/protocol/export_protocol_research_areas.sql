/*
 * Protocol Research Areas export.
 *
 * The source PROTOCOL_ID is retained for audit. The archive parent is
 * resolved from PROTOCOL_NUMBER + SEQUENCE_NUMBER because measured BU
 * evidence found direct-ID sequence mismatches.
 */
SELECT
    pra.ID                AS protocol_research_area_id,
    pra.PROTOCOL_ID       AS source_protocol_id,
    pra.PROTOCOL_NUMBER   AS protocol_number,
    pra.SEQUENCE_NUMBER   AS sequence_number,
    pra.RESEARCH_AREA_CODE AS research_area_code,
    pra.UPDATE_TIMESTAMP  AS source_update_timestamp,
    pra.UPDATE_USER       AS source_update_user,
    pra.VER_NBR           AS source_version_number,
    pra.OBJ_ID            AS source_object_id
FROM KCOEUS.PROTOCOL_RESEARCH_AREAS pra
ORDER BY
    pra.PROTOCOL_NUMBER,
    pra.SEQUENCE_NUMBER,
    pra.ID;
