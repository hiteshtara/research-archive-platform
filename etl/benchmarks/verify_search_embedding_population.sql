SELECT module, COUNT(*) AS row_count
FROM archive.search_embedding
GROUP BY module
ORDER BY module;

SELECT COUNT(*) AS total_rows FROM archive.search_embedding;

SELECT module, record_id, canonical_family_id, business_number
FROM archive.search_embedding
ORDER BY module, record_id
LIMIT 20;

SELECT module, record_id, COUNT(*) AS occurrences
FROM archive.search_embedding
GROUP BY module, record_id
HAVING COUNT(*) > 1;

SELECT COUNT(*) AS bad_hash_count
FROM archive.search_embedding
WHERE source_hash IS NULL OR LENGTH(source_hash) != 64;

SELECT DISTINCT vector_dims(embedding) AS embedding_dimensions
FROM archive.search_embedding;

SELECT COUNT(*) AS mismatched_canonical_id
FROM archive.search_embedding
WHERE record_id != canonical_family_id;

SELECT embedding_model, COUNT(*) AS row_count
FROM archive.search_embedding
GROUP BY embedding_model;
