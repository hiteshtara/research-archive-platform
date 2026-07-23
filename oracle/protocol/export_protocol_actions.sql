/*
 * Full historical PROTOCOL_ACTIONS export.
 *
 * Parent resolution is deliberately not performed in Oracle. The archive
 * loader resolves the exact Protocol version from protocol_number and
 * sequence_number and preserves source_protocol_id as audit metadata.
 */
SELECT
    action.PROTOCOL_ACTION_ID AS protocol_action_id,
    action.ACTION_ID AS action_id,
    action.PROTOCOL_ID AS source_protocol_id,
    action.PROTOCOL_NUMBER AS protocol_number,
    action.SEQUENCE_NUMBER AS sequence_number,
    action.SUBMISSION_NUMBER AS submission_number,
    action.SUBMISSION_ID_FK AS submission_id_fk,
    action.PROTOCOL_ACTION_TYPE_CODE AS protocol_action_type_code,
    action.COMMENTS AS comments,
    action.PREV_SUBMISSION_STATUS_CODE
        AS prev_submission_status_code,
    action.SUBMISSION_TYPE_CODE AS submission_type_code,
    action.PREV_PROTOCOL_STATUS_CODE AS prev_protocol_status_code,
    action.CREATE_TIMESTAMP AS source_create_timestamp,
    action.CREATE_USER AS source_create_user,
    action.UPDATE_TIMESTAMP AS source_update_timestamp,
    action.UPDATE_USER AS source_update_user,
    action.ACTION_DATE AS action_date,
    action.ACTUAL_ACTION_DATE AS actual_action_date,
    action.VER_NBR AS source_version_number,
    action.OBJ_ID AS source_object_id,
    action.FOLLOWUP_ACTION_CODE AS followup_action_code
FROM KCOEUS.PROTOCOL_ACTIONS action
ORDER BY action.PROTOCOL_ACTION_ID;
