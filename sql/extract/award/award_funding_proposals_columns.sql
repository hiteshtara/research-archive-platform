SELECT
    column_id,
    column_name,
    data_type
FROM user_tab_columns
WHERE table_name = 'AWARD_FUNDING_PROPOSALS'
ORDER BY column_id;
