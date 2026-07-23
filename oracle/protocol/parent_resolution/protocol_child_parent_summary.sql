/*
 * Read-only aggregate analysis of Protocol child parent resolution.
 *
 * A direct-ID match requires the joined Protocol row to have both the same
 * PROTOCOL_NUMBER and SEQUENCE_NUMBER as the child. Version-pair resolution
 * is measured independently from the stored child PROTOCOL_ID.
 */
WITH child_rows (
    table_name,
    child_protocol_id,
    protocol_number,
    sequence_number
) AS (
    SELECT 'PROTOCOL_PERSONS', PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_PERSONS
    UNION ALL
    SELECT 'PROTOCOL_FUNDING_SOURCE', PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_FUNDING_SOURCE
    UNION ALL
    SELECT 'PROTOCOL_RESEARCH_AREAS', PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_RESEARCH_AREAS
    UNION ALL
    SELECT 'PROTOCOL_REFERENCES', PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_REFERENCES
    UNION ALL
    SELECT 'PROTOCOL_RISK_LEVELS', PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_RISK_LEVELS
    UNION ALL
    SELECT 'PROTOCOL_VULNERABLE_SUB', PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_VULNERABLE_SUB
    UNION ALL
    SELECT 'PROTOCOL_LOCATION', PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_LOCATION
    UNION ALL
    SELECT 'PROTOCOL_ACTIONS', PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_ACTIONS
    UNION ALL
    SELECT 'PROTOCOL_SUBMISSION', PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_SUBMISSION
    UNION ALL
    SELECT 'PROTO_AMEND_RENEWAL', PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTO_AMEND_RENEWAL
    UNION ALL
    SELECT 'PROTOCOL_ATTACHMENT_PROTOCOL', PROTOCOL_ID_FK, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_ATTACHMENT_PROTOCOL
    UNION ALL
    SELECT 'PROTOCOL_ATTACHMENT_PERSONNEL', PROTOCOL_ID_FK, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_ATTACHMENT_PERSONNEL
    UNION ALL
    SELECT 'PROTOCOL_EXEMPT_CHKLST', PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_EXEMPT_CHKLST
    UNION ALL
    SELECT 'PROTOCOL_EXPIDITED_CHKLST', PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_EXPIDITED_CHKLST
    UNION ALL
    SELECT 'PROTOCOL_REVIEWERS', PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_REVIEWERS
    UNION ALL
    SELECT 'PROTOCOL_SUBMISSION_DOC', PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_SUBMISSION_DOC
    UNION ALL
    SELECT 'PROTOCOL_CORRESPONDENCE', PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_CORRESPONDENCE
    UNION ALL
    SELECT 'PROTOCOL_NOTEPAD', PROTOCOL_ID_FK, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_NOTEPAD
),
evaluated AS (
    SELECT
        child.table_name,
        child.child_protocol_id,
        child.protocol_number,
        child.sequence_number,
        parent.protocol_id AS joined_protocol_id,
        parent.protocol_number AS joined_protocol_number,
        parent.sequence_number AS joined_sequence_number,
        CASE
            WHEN EXISTS (
                SELECT 1
                FROM KCOEUS.PROTOCOL version_row
                WHERE version_row.PROTOCOL_NUMBER = child.protocol_number
                  AND version_row.SEQUENCE_NUMBER = child.sequence_number
            )
            THEN 1
            ELSE 0
        END AS version_pair_found
    FROM child_rows child
    LEFT JOIN KCOEUS.PROTOCOL parent
        ON parent.PROTOCOL_ID = child.child_protocol_id
)
SELECT
    table_name,
    COUNT(*) AS total_rows,
    SUM(
        CASE WHEN joined_protocol_id IS NOT NULL THEN 1 ELSE 0 END
    ) AS protocol_id_parent_found,
    SUM(
        CASE
            WHEN joined_protocol_id IS NOT NULL
             AND DECODE(
                     protocol_number,
                     joined_protocol_number,
                     1,
                     0
                 ) = 1
             AND DECODE(
                     sequence_number,
                     joined_sequence_number,
                     1,
                     0
                 ) = 1
            THEN 1
            ELSE 0
        END
    ) AS matching_number_and_sequence,
    SUM(
        CASE
            WHEN joined_protocol_id IS NOT NULL
             AND DECODE(
                     protocol_number,
                     joined_protocol_number,
                     1,
                     0
                 ) = 0
            THEN 1
            ELSE 0
        END
    ) AS mismatching_number,
    SUM(
        CASE
            WHEN joined_protocol_id IS NOT NULL
             AND DECODE(
                     sequence_number,
                     joined_sequence_number,
                     1,
                     0
                 ) = 0
            THEN 1
            ELSE 0
        END
    ) AS mismatching_sequence,
    SUM(
        CASE WHEN version_pair_found = 0 THEN 1 ELSE 0 END
    ) AS unresolved_version_by_number_sequence,
    ROUND(
        100 * SUM(
            CASE
                WHEN joined_protocol_id IS NULL
                  OR DECODE(
                         protocol_number,
                         joined_protocol_number,
                         1,
                         0
                     ) = 0
                  OR DECODE(
                         sequence_number,
                         joined_sequence_number,
                         1,
                         0
                     ) = 0
                THEN 1
                ELSE 0
            END
        ) / NULLIF(COUNT(*), 0),
        4
    ) AS mismatch_percentage
FROM evaluated
GROUP BY table_name
ORDER BY table_name;
