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
  AND (
        UPPER(column_name) LIKE '%NEGOTIATION_ID%'
        OR UPPER(column_name) LIKE '%NEGOTIATION_NUMBER%'
        OR UPPER(column_name) LIKE '%NEGOTIATION_ASSOCIATION_ID%'
        OR UPPER(column_name) LIKE '%ASSOCIATED_DOCUMENT_ID%'
        OR UPPER(column_name) LIKE '%ASSOCIATED_DOCUMENT_NUMBER%'
        OR UPPER(column_name) LIKE '%PROPOSAL_ID%'
        OR UPPER(column_name) LIKE '%PROPOSAL_NUMBER%'
        OR UPPER(column_name) LIKE '%AWARD_ID%'
        OR UPPER(column_name) LIKE '%AWARD_NUMBER%'
        OR UPPER(column_name) LIKE '%SUBAWARD_ID%'
        OR UPPER(column_name) LIKE '%SUBAWARD_NUMBER%'
        OR UPPER(column_name) LIKE '%MODULE_CODE%'
        OR UPPER(column_name) LIKE '%ASSOCIATION_TYPE%'
        OR UPPER(column_name) LIKE '%PERSON_ID%'
        OR UPPER(column_name) LIKE '%UNIT_NUMBER%'
        OR UPPER(column_name) LIKE '%SPONSOR_CODE%'
        OR UPPER(column_name) LIKE '%STATUS_CODE%'
        OR UPPER(column_name) LIKE '%ACTIVITY_TYPE_CODE%'
    )
ORDER BY
    owner,
    table_name,
    column_id;
