/*
 * Protocol Personnel Unit export.
 *
 * PROTOCOL_PERSON_ID is the verified physical parent relationship. Protocol
 * number and sequence are retained for reconciliation to the exact version.
 */
SELECT
    pu.PROTOCOL_UNITS_ID        AS protocol_units_id,
    pu.PROTOCOL_PERSON_ID       AS protocol_person_id,
    pu.PROTOCOL_NUMBER          AS protocol_number,
    pu.SEQUENCE_NUMBER          AS sequence_number,
    pu.UNIT_NUMBER              AS unit_number,
    pu.LEAD_UNIT_FLAG           AS lead_unit_flag,
    pu.PERSON_ID                AS person_id,
    pu.UPDATE_TIMESTAMP         AS source_update_timestamp,
    pu.UPDATE_USER              AS source_update_user,
    pu.VER_NBR                  AS source_version_number,
    pu.OBJ_ID                   AS source_object_id
FROM KCOEUS.PROTOCOL_UNITS pu
ORDER BY
    pu.PROTOCOL_NUMBER,
    pu.SEQUENCE_NUMBER,
    pu.PROTOCOL_PERSON_ID,
    pu.PROTOCOL_UNITS_ID;
