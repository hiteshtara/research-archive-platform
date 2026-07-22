SELECT
    table_name,
    column_id,
    column_name,
    data_type,
    data_length
FROM all_tab_columns
WHERE owner = 'KCOEUS'
  AND table_name IN (
      'PROPOSAL',
      'PROPOSAL_PERSONS'
  )
ORDER BY table_name, column_id;
