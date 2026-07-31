/*
 * Protocol personnel export.
 *
 * Oracle PROTOCOL_ID is retained only as source_protocol_id because KC
 * points it at the current row, not the historical version the child
 * belongs to - the archive parent is resolved later from
 * PROTOCOL_NUMBER + SEQUENCE_NUMBER (see
 * archive_etl/pipeline/protocol_parent_resolution.py).
 *
 * person_email_address and rolodex_email_address are both extracted raw
 * and deliberately not merged here - authoritative-email selection
 * (person_email_address primary, rolodex_email_address fallback only when
 * the primary is null) and mismatch/ambiguity reporting happen in Python,
 * where they are testable.
 */
SELECT
    pp.PROTOCOL_PERSON_ID       AS protocol_person_id,
    pp.PROTOCOL_ID              AS source_protocol_id,
    pp.PROTOCOL_NUMBER          AS protocol_number,
    pp.SEQUENCE_NUMBER          AS sequence_number,
    pp.PERSON_ID                AS person_id,
    pp.PERSON_NAME              AS person_name,
    pp.FULL_NAME                AS full_name,
    pp.PROTOCOL_PERSON_ROLE_ID  AS protocol_person_role_id,
    ppr.DESCRIPTION             AS protocol_person_role_description,
    pp.EMAIL_ADDRESS            AS person_email_address,
    pp.ROLODEX_ID               AS rolodex_id,
    r.EMAIL_ADDRESS             AS rolodex_email_address,
    pp.AFFILIATION_TYPE_CODE    AS affiliation_type_code,
    pp.COMMENTS                 AS comments,
    pp.UPDATE_TIMESTAMP         AS source_update_timestamp,
    pp.UPDATE_USER              AS source_update_user,
    pp.VER_NBR                  AS source_version_number,
    pp.OBJ_ID                   AS source_object_id
FROM KCOEUS.PROTOCOL_PERSONS pp
LEFT JOIN KCOEUS.PROTOCOL_PERSON_ROLES ppr
    ON ppr.PROTOCOL_PERSON_ROLE_ID = pp.PROTOCOL_PERSON_ROLE_ID
LEFT JOIN KCOEUS.ROLODEX r
    ON r.ROLODEX_ID = pp.ROLODEX_ID
ORDER BY
    pp.PROTOCOL_NUMBER,
    pp.SEQUENCE_NUMBER,
    pp.PROTOCOL_PERSON_ID
