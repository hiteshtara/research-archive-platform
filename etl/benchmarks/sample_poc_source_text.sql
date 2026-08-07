SELECT module, record_id, source_text
FROM archive.search_embedding_poc
WHERE module = 'AWARD' ORDER BY record_id LIMIT 5;

SELECT module, record_id, source_text
FROM archive.search_embedding_poc
WHERE module = 'PROPOSAL' ORDER BY record_id LIMIT 5;

SELECT module, record_id, source_text
FROM archive.search_embedding_poc
WHERE module = 'NEGOTIATION' ORDER BY record_id LIMIT 5;

SELECT module, record_id, source_text
FROM archive.search_embedding_poc
WHERE module = 'SUBAWARD' ORDER BY record_id LIMIT 5;
