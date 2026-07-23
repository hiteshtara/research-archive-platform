-- Protocol Personnel reconciliation. Read-only.

SELECT
    (SELECT COUNT(*) FROM archive.protocol_person) AS person_count,
    (SELECT COUNT(DISTINCT protocol_person_id)
       FROM archive.protocol_person) AS distinct_person_count,
    (SELECT COUNT(*) FROM archive.protocol_unit) AS unit_count,
    (SELECT COUNT(DISTINCT protocol_units_id)
       FROM archive.protocol_unit) AS distinct_unit_count;

SELECT
    COUNT(*) AS total_persons,
    COUNT(*) FILTER (
        WHERE parent_match_count = 1
    ) AS uniquely_resolved_persons,
    COUNT(*) FILTER (
        WHERE parent_match_count = 0
    ) AS missing_number_sequence_parents,
    COUNT(*) FILTER (
        WHERE parent_match_count > 1
    ) AS ambiguous_number_sequence_parents
FROM (
    SELECT
        person.protocol_person_id,
        (
            SELECT COUNT(*)
            FROM archive.protocol_version candidate
            WHERE candidate.protocol_number = person.protocol_number
              AND candidate.sequence_number = person.sequence_number
        ) AS parent_match_count
    FROM archive.protocol_person person
) resolution;

SELECT
    COUNT(*) AS source_protocol_id_differs_from_resolved_protocol_id
FROM archive.protocol_person
WHERE source_protocol_id IS DISTINCT FROM protocol_id;

SELECT
    COUNT(*) FILTER (
        WHERE person.protocol_number IS DISTINCT FROM
              protocol.protocol_number
    ) AS protocol_number_mismatch,
    COUNT(*) FILTER (
        WHERE person.sequence_number IS DISTINCT FROM
              protocol.sequence_number
    ) AS sequence_mismatch
FROM archive.protocol_person person
JOIN archive.protocol_version protocol
    ON protocol.protocol_id = person.protocol_id;

SELECT COUNT(*) AS unit_orphan_count
FROM archive.protocol_unit unit_row
LEFT JOIN archive.protocol_person person
    ON person.protocol_person_id = unit_row.protocol_person_id
WHERE person.protocol_person_id IS NULL;

SELECT COUNT(*) AS unit_number_sequence_audit_mismatches
FROM archive.protocol_unit unit_row
JOIN archive.protocol_person person
    ON person.protocol_person_id = unit_row.protocol_person_id
WHERE unit_row.protocol_number IS DISTINCT FROM person.protocol_number
   OR unit_row.sequence_number IS DISTINCT FROM person.sequence_number;

SELECT COUNT(*) AS unit_person_identity_inconsistencies
FROM archive.protocol_unit unit_row
JOIN archive.protocol_person person
    ON person.protocol_person_id = unit_row.protocol_person_id
WHERE unit_row.person_id IS NOT NULL
  AND person.person_id IS NOT NULL
  AND unit_row.person_id IS DISTINCT FROM person.person_id;
