-- Export Subaward custom values. CUSTOM_ATTRIBUTE_ID is preserved; the common
-- custom-attribute lookup is outside the verified Subaward inventory.
SELECT
    scd.subaward_custom_data_id AS subaward_custom_data_id,
    scd.subaward_id AS subaward_id,
    scd.subaward_code AS subaward_code,
    scd.sequence_number AS sequence_number,
    scd.custom_attribute_id AS custom_attribute_id,
    scd.value AS value,
    scd.update_timestamp AS update_timestamp,
    scd.update_user AS update_user,
    scd.ver_nbr AS ver_nbr,
    scd.obj_id AS obj_id
FROM KCOEUS.SUBAWARD_CUSTOM_DATA scd
ORDER BY scd.subaward_code, scd.sequence_number, scd.subaward_custom_data_id;
