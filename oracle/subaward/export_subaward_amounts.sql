-- Export Subaward amount/version rows. File metadata is retained, but the
-- source inventory declares no binary payload on SUBAWARD_AMOUNT_INFO.
SELECT
    sai.subaward_amount_info_id AS subaward_amount_info_id,
    sai.subaward_id AS subaward_id,
    sai.subaward_code AS subaward_code,
    sai.sequence_number AS sequence_number,
    sai.obligated_amount AS obligated_amount,
    sai.obligated_change AS obligated_change,
    sai.obligated_change_direct AS obligated_change_direct,
    sai.obligated_change_indirect AS obligated_change_indirect,
    sai.anticipated_amount AS anticipated_amount,
    sai.anticipated_change AS anticipated_change,
    sai.anticipated_change_direct AS anticipated_change_direct,
    sai.anticipated_change_indirect AS anticipated_change_indirect,
    sai.rate AS rate,
    sai.effective_date AS effective_date,
    sai.modification_effective_date AS modification_effective_date,
    sai.modification_number AS modification_number,
    sai.modification_type_code AS modification_type_code,
    smt.description AS modification_type_description,
    sai.performance_start_date AS performance_start_date,
    sai.performance_end_date AS performance_end_date,
    sai.purchase_order_num AS purchase_order_num,
    sai.comments AS comments,
    sai.file_data_id AS file_data_id,
    sai.file_name AS file_name,
    sai.mime_type AS mime_type,
    sai.update_timestamp AS update_timestamp,
    sai.update_user AS update_user,
    sai.ver_nbr AS ver_nbr,
    sai.obj_id AS obj_id
FROM KCOEUS.SUBAWARD_AMOUNT_INFO sai
LEFT JOIN KCOEUS.SUBAWARD_MODIFICATION_TYPE smt
    ON smt.code = sai.modification_type_code
ORDER BY sai.subaward_code, sai.sequence_number, sai.subaward_amount_info_id;
