SELECT
    pg_size_pretty(pg_total_relation_size('archive.search_embedding_poc')) AS total_size_pretty,
    pg_total_relation_size('archive.search_embedding_poc') AS total_size_bytes,
    pg_size_pretty(pg_relation_size('archive.search_embedding_poc')) AS table_only_pretty,
    pg_relation_size('archive.search_embedding_poc') AS table_only_bytes,
    pg_size_pretty(pg_indexes_size('archive.search_embedding_poc')) AS indexes_pretty,
    pg_indexes_size('archive.search_embedding_poc') AS indexes_bytes;

SELECT COUNT(*) AS row_count FROM archive.search_embedding_poc;
