/*
 * Protocol Personnel export.
 *
 * Oracle PROTOCOL_ID is retained only as source_protocol_id because KC points
 * it at the current row. The archive parent is resolved later from
 * PROTOCOL_NUMBER + SEQUENCE_NUMBER.
 */
SELECT
    pp.PROTOCOL_PERSON_ID       AS protocol_person_id,
    pp.PROTOCOL_ID              AS source_protocol_id,
    pp.PROTOCOL_NUMBER          AS protocol_number,
    pp.SEQUENCE_NUMBER          AS sequence_number,
    pp.PERSON_ID                AS person_id,
    pp.PERSON_NAME              AS person_name,
    pp.PROTOCOL_PERSON_ROLE_ID  AS protocol_person_role_id,
    pp.ROLODEX_ID               AS rolodex_id,
    pp.AFFILIATION_TYPE_CODE    AS affiliation_type_code,
    pp.COMMENTS                 AS comments,
    pp.UPDATE_TIMESTAMP         AS source_update_timestamp,
    pp.UPDATE_USER              AS source_update_user,
    pp.VER_NBR                  AS source_version_number,
    pp.OBJ_ID                   AS source_object_id
FROM KCOEUS.PROTOCOL_PERSONS pp
ORDER BY
    pp.PROTOCOL_NUMBER,
    pp.SEQUENCE_NUMBER,
    pp.PROTOCOL_PERSON_ID;
