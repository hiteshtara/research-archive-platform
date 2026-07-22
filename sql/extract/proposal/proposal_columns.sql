SELECT
    table_name,
    column_id,
    column_name,
    data_type,
    data_length,
    data_precision,
    data_scale,
    nullable
FROM all_tab_columns
WHERE owner = 'KCOEUS'
  AND table_name IN (
      'ACTIVITY_TYPE',
      'PROPOSAL',
      'PROPOSAL_PERSONS',
      'PROPOSAL_TYPE',
      'SPONSOR',
      'UNIT'
  )
ORDER BY table_name, column_id;
