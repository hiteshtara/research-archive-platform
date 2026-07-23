# Protocol Parent-Resolution Analysis

## Purpose and evidence boundary

This analysis tests whether a Protocol child row's physical `PROTOCOL_ID`
actually identifies the historical Protocol version represented by the
child's `PROTOCOL_NUMBER` and `SEQUENCE_NUMBER`.

The KC OJB descriptor establishes the available columns and relationships.
It does not establish that the stored physical ID and business version agree.
Verified `PROTOCOL_PERSONS` evidence demonstrates that they disagree at
material scale:

- `PROTOCOL_PERSONS.PROTOCOL_ID` points to the current Protocol row.
- `PROTOCOL_PERSONS.PROTOCOL_NUMBER` and `SEQUENCE_NUMBER` identify the
  historical version.
- Total Personnel rows: **264,811**
- Direct-ID parent sequence matches: **225,544**
- Direct-ID parent sequence mismatches: **39,267**
- Mismatch rate: approximately **14.83%**

Confirmed identifier-only example:

```text
PROTOCOL_PERSONS
  PROTOCOL_PERSON_ID = 114896
  PROTOCOL_ID        = 114886
  PROTOCOL_NUMBER    = 1602001993
  SEQUENCE_NUMBER    = 0

PROTOCOL
  PROTOCOL_ID 114886 has SEQUENCE_NUMBER 4
  PROTOCOL_NUMBER 1602001993 + SEQUENCE_NUMBER 0 resolves to PROTOCOL_ID 9141
```

This proves that direct `PROTOCOL_ID` parenting is unsafe for Personnel.
Personnel must resolve its archive parent by `PROTOCOL_NUMBER` plus
`SEQUENCE_NUMBER`, while retaining the original Oracle `PROTOCOL_ID` as
`source_protocol_id`.

The following aggregate results were produced by local BU Oracle execution
and supplied for review. No Oracle connection was made during implementation.

## Directly testable versioned children

These descriptor-backed objects contain a physical Protocol ID, Protocol
number, and sequence number and can be tested exactly as requested.

| Child object | Total | Matching tuple | Number mismatch | Sequence mismatch | Unresolved tuple | Mismatch % |
|---|---:|---:|---:|---:|---:|---:|
| `PROTOCOL_ACTIONS` | 903,796 | 130,472 | 0 | 773,324 | 0 | 85.5640% |
| `PROTOCOL_ATTACHMENT_PROTOCOL` | 6 | 6 | 0 | 0 | 0 | 0.0000% |
| `PROTOCOL_EXEMPT_CHKLST` | 2,848 | 2,748 | 100 | 100 | 0 | 3.5112% |
| `PROTOCOL_EXPIDITED_CHKLST` | 197,761 | 187,476 | 10,285 | 10,285 | 0 | 5.2007% |
| `PROTOCOL_FUNDING_SOURCE` | 43,405 | 43,213 | 0 | 192 | 0 | 0.4423% |
| `PROTOCOL_LOCATION` | 40,336 | 38,202 | 1,524 | 610 | 1,524 | 5.2906% |
| `PROTOCOL_NOTEPAD` | 11,534 | 11,195 | 339 | 304 | 0 | 2.9391% |
| `PROTOCOL_PERSONS` | 264,811 | 225,544 | 0 | 39,267 | 0 | 14.8283% |
| `PROTOCOL_RESEARCH_AREAS` | 35,331 | 35,142 | 0 | 189 | 0 | 0.5349% |
| `PROTOCOL_SUBMISSION` | 221,519 | 221,519 | 0 | 0 | 0 | 0.0000% |
| `PROTOCOL_VULNERABLE_SUB` | 17 | 14 | 1 | 2 | 1 | 17.6471% |
| `PROTO_AMEND_RENEWAL` | 16,569 | 0 | 16,569 | 174 | 0 | 100.0000% |

Unlisted eligible tables remain unmeasured. Their strategies remain
conditional. Detail mismatch categories may overlap and must not be summed.

`PROTOCOL_SUBMISSION_V` exposes the same three fields but is a read view, not
an additional transactional child. Validate it only as a read-model check; do
not count it as another archive entity.

## Children requiring inherited parent resolution

These objects cannot support the requested three-column comparison directly.
They must inherit the Protocol version through their nearest verified owning
parent.

| Child object | Missing direct evidence | Required owning path |
|---|---|---|
| `PROTOCOL_UNITS` | No physical `PROTOCOL_ID` | `PROTOCOL_PERSON_ID` → `PROTOCOL_PERSONS`, then Protocol number + sequence |
| `PROTOCOL_SPECIAL_REVIEW` | No `SEQUENCE_NUMBER` | Resolve through a verified owning/version rule; direct three-column comparison is impossible |
| `PROTOCOL_EXEMPT_NUMBER` | No Protocol version fields | `PROTOCOL_SPECIAL_REVIEW_ID` → special review |
| `PROTOCOL_ONLN_RVWS` | No Protocol number or sequence | Submission/reviewer parent chain |
| `REVIEWER_ATTACHMENTS` | No Protocol number or sequence | Online review/submission parent chain |
| `PROTO_AMEND_RENEW_MODULES` | No physical Protocol ID or sequence | `PROTO_AMEND_RENEWAL_ID` → amendment/renewal |
| `PROTOCOL_NOTIFICATION` | Owning-document relationship rather than the complete version tuple | Action/document parent chain; semantics still require validation |

The descriptor also references submission abstainers, recusers, and committee
schedule minutes whose class descriptors are external to
`ProtocolOJB.xml`. They cannot be analyzed until their physical contracts are
available.

`PROTOCOL_SPECIAL_REVIEW` was explicitly evaluated for structural
eligibility. It has `PROTOCOL_ID` and `PROTOCOL_NUMBER` but no
`SEQUENCE_NUMBER`, so including it in the direct comparison would fabricate a
field that does not exist.

## Local runner

The runner uses only `ORACLE_USER`, `ORACLE_PASSWORD`, and `ORACLE_DSN`. It
executes the two SELECT-only analysis files, prints the aggregate summary and
up to 20 mismatches per eligible table, and optionally writes CSV files. It
does not connect to PostgreSQL or make schema changes.

Exact command:

```bash
cd etl
uv run python analyze_protocol_parent_resolution.py \
  --output data/protocol_parent_resolution.csv
```

To retain the sanitized examples as a second CSV:

```bash
uv run python analyze_protocol_parent_resolution.py \
  --output data/protocol_parent_resolution.csv \
  --examples-output data/protocol_parent_resolution_examples.csv
```

## Required local Oracle count query

Executable aggregate SQL for every directly testable table is provided at:

`oracle/protocol/parent_resolution/protocol_child_parent_summary.sql`

The following is the per-table pattern used by that script. For the three
`*_FK` tables, it substitutes `PROTOCOL_ID_FK` for `PROTOCOL_ID`.

```sql
SELECT
    COUNT(*) AS total_count,
    SUM(
        CASE
            WHEN parent.PROTOCOL_ID IS NOT NULL
             AND DECODE(
                     child.SEQUENCE_NUMBER,
                     parent.SEQUENCE_NUMBER,
                     1,
                     0
                 ) = 1
             AND DECODE(
                     child.PROTOCOL_NUMBER,
                     parent.PROTOCOL_NUMBER,
                     1,
                     0
                 ) = 1
            THEN 1
            ELSE 0
        END
    ) AS matching_count,
    SUM(
        CASE
            WHEN parent.PROTOCOL_ID IS NULL
              OR DECODE(
                     child.SEQUENCE_NUMBER,
                     parent.SEQUENCE_NUMBER,
                     1,
                     0
                 ) = 0
              OR DECODE(
                     child.PROTOCOL_NUMBER,
                     parent.PROTOCOL_NUMBER,
                     1,
                     0
                 ) = 0
            THEN 1
            ELSE 0
        END
    ) AS mismatching_count,
    ROUND(
        100 * SUM(
            CASE
                WHEN parent.PROTOCOL_ID IS NOT NULL
                 AND DECODE(
                         child.SEQUENCE_NUMBER,
                         parent.SEQUENCE_NUMBER,
                         1,
                         0
                     ) = 1
                 AND DECODE(
                         child.PROTOCOL_NUMBER,
                         parent.PROTOCOL_NUMBER,
                         1,
                         0
                     ) = 1
                THEN 1
                ELSE 0
            END
        ) / NULLIF(COUNT(*), 0),
        4
    ) AS matching_percentage
FROM KCOEUS.<CHILD_TABLE> child
LEFT JOIN KCOEUS.PROTOCOL parent
    ON parent.PROTOCOL_ID = child.<PROTOCOL_ID_COLUMN>;
```

Equality on both Protocol number and sequence is included so a reused sequence
number from another family cannot be counted as a valid match.

## Required mismatch examples

Executable mismatch sampling for every directly testable table is provided
at:

`oracle/protocol/parent_resolution/protocol_child_parent_examples.sql`

It returns up to 20 identifier-only mismatches per table. The following is
the pattern used by that script.

```sql
SELECT *
FROM (
    SELECT
        child.<CHILD_PRIMARY_KEY> AS child_primary_key,
        child.<PROTOCOL_ID_COLUMN> AS child_protocol_id,
        child.PROTOCOL_NUMBER AS child_protocol_number,
        child.SEQUENCE_NUMBER AS child_sequence_number,
        parent.PROTOCOL_NUMBER AS joined_protocol_number,
        parent.SEQUENCE_NUMBER AS joined_sequence_number
    FROM KCOEUS.<CHILD_TABLE> child
    LEFT JOIN KCOEUS.PROTOCOL parent
        ON parent.PROTOCOL_ID = child.<PROTOCOL_ID_COLUMN>
    WHERE parent.PROTOCOL_ID IS NULL
       OR DECODE(
              child.PROTOCOL_NUMBER,
              parent.PROTOCOL_NUMBER,
              1,
              0
          ) = 0
       OR DECODE(
              child.SEQUENCE_NUMBER,
              parent.SEQUENCE_NUMBER,
              1,
              0
          ) = 0
    ORDER BY
        child.PROTOCOL_NUMBER,
        child.SEQUENCE_NUMBER,
        child.<CHILD_PRIMARY_KEY>
)
WHERE ROWNUM <= 10;
```

`DECODE` provides null-safe equality on supported Oracle versions. Do not
paste names, comments, or other person data into the evidence report;
identifiers and version fields are sufficient.

## Evidence still required

For every still-unmeasured directly testable child, provide:

1. total count;
2. matching count;
3. mismatching count;
4. matching percentage;
5. ten identifier-only mismatch examples;
6. count of child `(PROTOCOL_NUMBER, SEQUENCE_NUMBER)` pairs resolving to no
   Protocol row; and
7. count of pairs resolving to more than one physical `PROTOCOL_ID`.

The last two checks are necessary because choosing the business version tuple
is safe only when it resolves to exactly one archive parent.

## Canonical aggregate strategy

The evidence rejects a universal direct-ID rule and also rejects applying one
tuple rule indiscriminately. Parent resolution is module-specific:

1. `NUMBER_SEQUENCE` resolves the exact business-version tuple.
2. `DIRECT_PROTOCOL_ID` is allowed only when measured evidence supports it.
3. `OWNER_CHAIN` is used by nested children through their owning row.

Personnel is conclusively `NUMBER_SEQUENCE`. Store the uniquely resolved
archive ID in `protocol_id`, preserve the Oracle child value in
`source_protocol_id`, report source-ID mismatches, and reject missing or
ambiguous tuples without a direct-ID fallback. The 39,267 measured mismatches
remain valid historical rows under this rule.

Future child slices must interpret their own evidence and record an explicit
strategy before implementation.
