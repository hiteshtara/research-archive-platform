/*
 * Up to 20 identifier-only direct-parent mismatches per eligible child.
 * No names, comments, or other person attributes are selected.
 */
WITH child_rows (
    table_name,
    child_primary_key,
    child_protocol_id,
    protocol_number,
    sequence_number
) AS (
    SELECT 'PROTOCOL_PERSONS', TO_CHAR(PROTOCOL_PERSON_ID), PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_PERSONS
    UNION ALL
    SELECT 'PROTOCOL_FUNDING_SOURCE', TO_CHAR(PROTOCOL_FUNDING_SOURCE_ID), PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_FUNDING_SOURCE
    UNION ALL
    SELECT 'PROTOCOL_RESEARCH_AREAS', TO_CHAR(ID), PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_RESEARCH_AREAS
    UNION ALL
    SELECT 'PROTOCOL_REFERENCES', TO_CHAR(PROTOCOL_REFERENCE_ID), PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_REFERENCES
    UNION ALL
    SELECT 'PROTOCOL_RISK_LEVELS', TO_CHAR(PROTOCOL_RISK_LEVELS_ID), PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_RISK_LEVELS
    UNION ALL
    SELECT 'PROTOCOL_VULNERABLE_SUB', TO_CHAR(PROTOCOL_VULNERABLE_SUB_ID), PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_VULNERABLE_SUB
    UNION ALL
    SELECT 'PROTOCOL_LOCATION', TO_CHAR(PROTOCOL_LOCATION_ID), PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_LOCATION
    UNION ALL
    SELECT 'PROTOCOL_ACTIONS', TO_CHAR(PROTOCOL_ACTION_ID), PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_ACTIONS
    UNION ALL
    SELECT 'PROTOCOL_SUBMISSION', TO_CHAR(SUBMISSION_ID), PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_SUBMISSION
    UNION ALL
    SELECT 'PROTO_AMEND_RENEWAL', TO_CHAR(PROTO_AMEND_RENEWAL_ID), PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTO_AMEND_RENEWAL
    UNION ALL
    SELECT 'PROTOCOL_ATTACHMENT_PROTOCOL', TO_CHAR(PA_PROTOCOL_ID), PROTOCOL_ID_FK, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_ATTACHMENT_PROTOCOL
    UNION ALL
    SELECT 'PROTOCOL_ATTACHMENT_PERSONNEL', TO_CHAR(PA_PERSONNEL_ID), PROTOCOL_ID_FK, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_ATTACHMENT_PERSONNEL
    UNION ALL
    SELECT 'PROTOCOL_EXEMPT_CHKLST', TO_CHAR(PROTOCOL_EXEMPT_CHKLST_ID), PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_EXEMPT_CHKLST
    UNION ALL
    SELECT 'PROTOCOL_EXPIDITED_CHKLST', TO_CHAR(PROTOCOL_EXPEDITED_CHKLST_ID), PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_EXPIDITED_CHKLST
    UNION ALL
    SELECT 'PROTOCOL_REVIEWERS', TO_CHAR(PROTOCOL_REVIEWER_ID), PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_REVIEWERS
    UNION ALL
    SELECT 'PROTOCOL_SUBMISSION_DOC', TO_CHAR(SUBMISSION_DOC_ID), PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_SUBMISSION_DOC
    UNION ALL
    SELECT 'PROTOCOL_CORRESPONDENCE', TO_CHAR(ID), PROTOCOL_ID, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_CORRESPONDENCE
    UNION ALL
    SELECT 'PROTOCOL_NOTEPAD', TO_CHAR(PROTOCOL_NOTEPAD_ID), PROTOCOL_ID_FK, PROTOCOL_NUMBER, SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_NOTEPAD
),
mismatches AS (
    SELECT
        child.table_name,
        child.child_primary_key,
        child.child_protocol_id,
        child.protocol_number AS child_protocol_number,
        child.sequence_number AS child_sequence_number,
        parent.protocol_number AS joined_protocol_number,
        parent.sequence_number AS joined_sequence_number,
        (
            SELECT MIN(version_row.PROTOCOL_ID)
            FROM KCOEUS.PROTOCOL version_row
            WHERE version_row.PROTOCOL_NUMBER = child.protocol_number
              AND version_row.SEQUENCE_NUMBER = child.sequence_number
        ) AS resolved_protocol_id,
        (
            SELECT COUNT(*)
            FROM KCOEUS.PROTOCOL version_row
            WHERE version_row.PROTOCOL_NUMBER = child.protocol_number
              AND version_row.SEQUENCE_NUMBER = child.sequence_number
        ) AS version_pair_match_count
    FROM child_rows child
    LEFT JOIN KCOEUS.PROTOCOL parent
        ON parent.PROTOCOL_ID = child.child_protocol_id
    WHERE parent.PROTOCOL_ID IS NULL
       OR DECODE(
              child.protocol_number,
              parent.protocol_number,
              1,
              0
          ) = 0
       OR DECODE(
              child.sequence_number,
              parent.sequence_number,
              1,
              0
          ) = 0
),
ranked AS (
    SELECT
        mismatches.*,
        ROW_NUMBER() OVER (
            PARTITION BY table_name
            ORDER BY
                child_protocol_number,
                child_sequence_number,
                child_primary_key
        ) AS example_number
    FROM mismatches
)
SELECT
    table_name,
    child_primary_key,
    child_protocol_id,
    child_protocol_number,
    child_sequence_number,
    joined_protocol_number,
    joined_sequence_number,
    resolved_protocol_id,
    version_pair_match_count
FROM ranked
WHERE example_number <= 20
ORDER BY table_name, example_number;
