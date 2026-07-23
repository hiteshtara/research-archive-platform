/*
 * Protocol Location export.
 *
 * The source PROTOCOL_ID is retained for audit. Parent resolution occurs
 * from PROTOCOL_NUMBER + SEQUENCE_NUMBER; missing or ambiguous tuples are
 * rejected by the loader.
 */
SELECT
    pl.PROTOCOL_LOCATION_ID      AS protocol_location_id,
    pl.PROTOCOL_ID               AS source_protocol_id,
    pl.PROTOCOL_NUMBER           AS protocol_number,
    pl.SEQUENCE_NUMBER           AS sequence_number,
    pl.PROTOCOL_ORG_TYPE_CODE    AS protocol_org_type_code,
    pl.ORGANIZATION_ID           AS organization_id,
    pl.ROLODEX_ID                AS rolodex_id,
    pl.UPDATE_TIMESTAMP          AS source_update_timestamp,
    pl.UPDATE_USER               AS source_update_user,
    pl.VER_NBR                   AS source_version_number,
    pl.OBJ_ID                    AS source_object_id
FROM KCOEUS.PROTOCOL_LOCATION pl
ORDER BY
    pl.PROTOCOL_NUMBER,
    pl.SEQUENCE_NUMBER,
    pl.PROTOCOL_LOCATION_ID;
