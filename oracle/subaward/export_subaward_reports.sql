-- Export report assignments with the verified local report-type description.
SELECT
    sr.subaward_report_id AS subaward_report_id,
    sr.subaward_id AS subaward_id,
    sr.subaward_code AS subaward_code,
    sr.sequence_number AS sequence_number,
    sr.report_type_code AS report_type_code,
    srt.description AS report_type_description,
    sr.update_timestamp AS update_timestamp,
    sr.update_user AS update_user,
    sr.ver_nbr AS ver_nbr,
    sr.obj_id AS obj_id
FROM KCOEUS.SUBAWARD_REPORTS sr
LEFT JOIN KCOEUS.SUBAWARD_REPORT_TYPE srt
    ON srt.report_type_code = sr.report_type_code
ORDER BY sr.subaward_code, sr.sequence_number, sr.subaward_report_id;
