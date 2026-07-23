/*
 * Protocol Core archive export.
 *
 * KCOEUS.PROTOCOL is the authoritative parent and every source row is
 * retained. Lookup and document joins are LEFT JOINs so missing optional
 * records never remove a Protocol version.
 *
 * Export this result as protocol_versions.csv without applying a date filter.
 */
SELECT
    p.PROTOCOL_ID                     AS protocol_id,
    p.PROTOCOL_NUMBER                 AS protocol_number,
    p.SEQUENCE_NUMBER                 AS sequence_number,
    p.DOCUMENT_NUMBER                 AS document_number,
    p.ACTIVE                          AS active,
    p.PROTOCOL_TYPE_CODE              AS protocol_type_code,
    pt.DESCRIPTION                    AS protocol_type_description,
    p.PROTOCOL_STATUS_CODE            AS protocol_status_code,
    ps.DESCRIPTION                    AS protocol_status_description,
    p.TITLE                           AS title,
    p.DESCRIPTION                     AS description,
    p.INITIAL_SUBMISSION_DATE         AS initial_submission_date,
    p.APPROVAL_DATE                   AS approval_date,
    p.EXPIRATION_DATE                 AS expiration_date,
    p.LAST_APPROVAL_DATE              AS last_approval_date,
    p.FDA_APPLICATION_NUMBER          AS fda_application_number,
    p.REFERENCE_NUMBER_1              AS reference_number_1,
    p.REFERENCE_NUMBER_2              AS reference_number_2,
    pd.PROTOCOL_WORKFLOW_TYPE         AS protocol_workflow_type,
    pd.REROUTED_FLAG                  AS rerouted_flag,
    p.CREATE_TIMESTAMP                AS source_create_timestamp,
    p.CREATE_USER                     AS source_create_user,
    p.UPDATE_TIMESTAMP                AS source_update_timestamp,
    p.UPDATE_USER                     AS source_update_user,
    p.VER_NBR                         AS source_version_number,
    p.OBJ_ID                          AS source_object_id
FROM KCOEUS.PROTOCOL p
LEFT JOIN KCOEUS.PROTOCOL_DOCUMENT pd
    ON pd.DOCUMENT_NUMBER = p.DOCUMENT_NUMBER
LEFT JOIN KCOEUS.PROTOCOL_STATUS ps
    ON ps.PROTOCOL_STATUS_CODE = p.PROTOCOL_STATUS_CODE
LEFT JOIN KCOEUS.PROTOCOL_TYPE pt
    ON pt.PROTOCOL_TYPE_CODE = p.PROTOCOL_TYPE_CODE
ORDER BY
    p.PROTOCOL_NUMBER,
    p.SEQUENCE_NUMBER,
    p.PROTOCOL_ID;
