CREATE SCHEMA IF NOT EXISTS archive;

CREATE EXTENSION IF NOT EXISTS pg_trgm;

COMMENT ON SCHEMA archive IS
'Read-only archive of legacy BU research administration data.';
