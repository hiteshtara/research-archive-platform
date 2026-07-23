SELECT
    owner,
    object_name,
    object_type,
    status
FROM all_objects
WHERE object_type IN (
        'TABLE',
        'VIEW',
        'MATERIALIZED VIEW'
    )
  AND (
        UPPER(object_name) LIKE '%NEGOTIATION%'
        OR UPPER(object_name) LIKE '%NEGOTIATION_ASSOCIATION%'
        OR UPPER(object_name) LIKE '%NEGOTIATION_ACTIVITY%'
        OR UPPER(object_name) LIKE '%NEGOTIATION_LOCATION%'
        OR UPPER(object_name) LIKE '%NEGOTIATION_PERSON%'
        OR UPPER(object_name) LIKE '%NEGOTIATION_ROLE%'
        OR UPPER(object_name) LIKE '%NEGOTIATION_STATUS%'
    )
ORDER BY
    owner,
    object_name,
    object_type;

-- Row-count templates only.
-- Run these only after the owner and object names are confirmed above.
-- Replace each placeholder with a metadata-validated identifier.
--
-- SELECT COUNT(*) AS row_count
-- FROM <CONFIRMED_OWNER>.<CONFIRMED_NEGOTIATION_OBJECT>;
--
-- SELECT COUNT(*) AS row_count
-- FROM <CONFIRMED_OWNER>.<CONFIRMED_CHILD_OBJECT>;
