/*
 * Protocol Submission historical export.
 *
 * The OJB descriptor verifies every selected physical column. This export
 * preserves every source row and performs no latest-only filtering.
 */
SELECT
    submission.SUBMISSION_ID AS submission_id,
    submission.PROTOCOL_ID AS source_protocol_id,
    submission.PROTOCOL_NUMBER AS protocol_number,
    submission.SEQUENCE_NUMBER AS sequence_number,
    submission.SUBMISSION_NUMBER AS submission_number,
    submission.SCHEDULE_ID AS schedule_id,
    submission.COMMITTEE_ID AS committee_id,
    submission.SUBMISSION_TYPE_CODE AS submission_type_code,
    submission.SUBMISSION_TYPE_QUAL_CODE AS submission_type_qual_code,
    submission.SUBMISSION_STATUS_CODE AS submission_status_code,
    submission.SCHEDULE_ID_FK AS schedule_id_fk,
    submission.COMMITTEE_ID_FK AS committee_id_fk,
    submission.PROTOCOL_REVIEW_TYPE_CODE AS protocol_review_type_code,
    submission.SUBMISSION_DATE AS submission_date,
    submission.COMMENTS AS comments,
    submission.COMM_DECISION_MOTION_TYPE_CODE
        AS comm_decision_motion_type_code,
    submission.YES_VOTE_COUNT AS yes_vote_count,
    submission.NO_VOTE_COUNT AS no_vote_count,
    submission.ABSTAINER_COUNT AS abstainer_count,
    submission.RECUSED_COUNT AS recused_count,
    submission.VOTING_COMMENTS AS voting_comments,
    submission.IS_BILLABLE AS is_billable,
    submission.UPDATE_TIMESTAMP AS source_update_timestamp,
    submission.UPDATE_USER AS source_update_user,
    submission.VER_NBR AS source_version_number,
    submission.OBJ_ID AS source_object_id
FROM KCOEUS.PROTOCOL_SUBMISSION submission
ORDER BY submission.SUBMISSION_ID;
