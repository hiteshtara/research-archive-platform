-- Export Negotiation custom attribute values to negotiation_custom_data.csv.
-- NEGOTIATION_ID is the parent foreign key. CUSTOM_ATTRIBUTE_ID is retained
-- without a lookup join because its source lookup object is not yet verified.
SELECT
    cd.negotiation_custom_data_id AS negotiation_custom_data_id,
    cd.negotiation_id AS negotiation_id,
    cd.negotiation_number AS negotiation_number,
    cd.custom_attribute_id AS custom_attribute_id,
    cd.value AS value,
    cd.update_timestamp AS update_timestamp,
    cd.update_user AS update_user,
    cd.ver_nbr AS ver_nbr,
    cd.obj_id AS obj_id
FROM KCOEUS.NEGOTIATION_CUSTOM_DATA cd
ORDER BY cd.negotiation_id, cd.negotiation_custom_data_id;
