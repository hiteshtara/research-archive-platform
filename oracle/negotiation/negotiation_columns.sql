SELECT
    owner,
    table_name,
    column_id,
    column_name,
    data_type,
    data_length,
    data_precision,
    data_scale,
    nullable
FROM all_tab_columns
WHERE UPPER(table_name) LIKE '%NEGOTIATION%'
   OR UPPER(table_name) LIKE '%NEGOTIATION_ASSOCIATION%'
   OR UPPER(table_name) LIKE '%NEGOTIATION_ACTIVITY%'
   OR UPPER(table_name) LIKE '%NEGOTIATION_LOCATION%'
   OR UPPER(table_name) LIKE '%NEGOTIATION_PERSON%'
   OR UPPER(table_name) LIKE '%NEGOTIATION_ROLE%'
   OR UPPER(table_name) LIKE '%NEGOTIATION_STATUS%'
ORDER BY
    owner,
    table_name,
    column_id;
