# Protocol Action Parent Investigation

## Scope

This investigation covers only `KCOEUS.PROTOCOL_ACTIONS`. It does not define
an archive schema or authorize an implementation.

Run:

```text
oracle/protocol/parent_resolution/investigate_protocol_actions_parent.sql
```

in the local BU Oracle client while connected through the VPN. Retain all
seven result sets for review.

## Descriptor-confirmed schema

The authoritative KC descriptor defines:

| Meaning | Oracle column |
|---|---|
| Primary key | `PROTOCOL_ACTION_ID` |
| Secondary action identifier | `ACTION_ID` |
| Direct Protocol reference | `PROTOCOL_ID` |
| Protocol business identifier | `PROTOCOL_NUMBER` |
| Protocol sequence | `SEQUENCE_NUMBER` |
| Submission business number | `SUBMISSION_NUMBER` |
| Submission owner reference | `SUBMISSION_ID_FK` |
| Action type | `PROTOCOL_ACTION_TYPE_CODE` |
| Prior submission status | `PREV_SUBMISSION_STATUS_CODE` |
| Submission type | `SUBMISSION_TYPE_CODE` |
| Prior Protocol status | `PREV_PROTOCOL_STATUS_CODE` |
| Action timestamps | `ACTION_DATE`, `ACTUAL_ACTION_DATE` |
| Follow-up action | `FOLLOWUP_ACTION_CODE` |
| Comments | `COMMENTS` |
| Creation audit | `CREATE_TIMESTAMP`, `CREATE_USER` |
| Update audit | `UPDATE_TIMESTAMP`, `UPDATE_USER` |
| Version audit | `VER_NBR`, `OBJ_ID` |

The descriptor defines references from `PROTOCOL_ID` to `PROTOCOL` and from
`SUBMISSION_ID_FK` to `PROTOCOL_SUBMISSION`. It also references
`PROTOCOL_ACTION_TYPE` through `PROTOCOL_ACTION_TYPE_CODE`. Result Set 7
must confirm the foreign keys physically installed in BU Oracle.

## Existing evidence

The earlier broad analysis measured:

- 903,796 action rows;
- 130,472 direct parents whose number and sequence matched;
- 773,324 direct-parent sequence mismatches;
- zero number mismatches;
- zero unresolved number/sequence tuples; and
- an 85.5640% direct-ID mismatch rate.

These figures are prior evidence only. They must be replaced or confirmed by
the focused result sets before implementation.

Protocol Submission has separately been validated at 221,519 rows with zero
direct-parent, identity, duplicate, missing, or ambiguous-parent issues.
That makes its owner chain testable, but does not prove that every Action is
submission-owned or that the chain is the correct archival parent.

## Result sets

1. **Source summary** — source uniqueness, null identities, and distinct
   parent populations.
2. **DIRECT_PROTOCOL_ID** — parent coverage and exact number/sequence
   agreement.
3. **NUMBER_SEQUENCE** — unique, missing, and ambiguous tuple resolution and
   comparison with the direct ID.
4. **PROTOCOL_SUBMISSION owner chain** — submission coverage and comparison
   of the owner-derived, tuple-derived, and direct parents.
5. **Pattern counts** — mutually exclusive resolution categories.
6. **Representative examples** — up to 25 identifier-only rows from every
   classified pattern.
7. **Foreign keys** — installed Oracle relationships for
   `PROTOCOL_ACTIONS`.

## Candidate strategies

### DIRECT_PROTOCOL_ID

This strategy is acceptable only if every non-rejected row has a direct
parent and that parent represents the Action's stored Protocol number and
sequence. The prior 85.5640% mismatch rate currently argues against it.

### NUMBER_SEQUENCE

This strategy is acceptable if every source tuple resolves to exactly one
Protocol row, with no missing or ambiguous tuples. The prior broad analysis
reported full tuple coverage, but the focused Result Set 3 is the promotion
evidence.

### PROTOCOL_SUBMISSION owner chain

This strategy is acceptable for submission-owned rows only if the submission
exists, its Protocol parent exists, and its number/sequence identity agrees
with the Action and the unique tuple parent. Rows without
`SUBMISSION_ID_FK` require a separately proven rule.

## Recommendation

No strategy is promoted before the focused results are reviewed.

If Result Set 3 confirms unique tuple resolution for all 903,796 rows with
zero missing or ambiguous parents, `NUMBER_SEQUENCE` is the leading
candidate. The direct ID would be retained as source audit data. The owner
chain would remain relationship evidence rather than override the Action's
own exact business version.

If tuple resolution is incomplete, a hybrid may be considered only for a
mutually exclusive pattern whose owner chain resolves uniquely and agrees
with the Action business identity. There must be no unrestricted direct-ID
fallback.

## Promotion gates

Implementation must wait until all of these are documented:

- source primary keys are non-null and unique;
- every accepted row has one historical Protocol parent;
- missing and ambiguous tuple counts are zero, or explicit reject
  populations are approved;
- any owner-chain rule has complete owner and owner-parent coverage for its
  stated pattern;
- owner-derived parents agree with the Action number and sequence;
- every unexplained classification is resolved or explicitly rejected;
- installed Oracle foreign keys are recorded; and
- representative examples support the aggregate counts.

## Unresolved questions

- How many Actions have a non-null `SUBMISSION_ID_FK`?
- Does the submission owner agree with the Action tuple for every referenced
  row?
- Are Actions without submissions fully and uniquely tuple-resolvable?
- Does BU Oracle physically enforce both descriptor relationships?
- Are any source identity fields null despite the descriptor contract?
- Does `ACTION_ID` have business uniqueness within a Protocol family or
  sequence, or is only `PROTOCOL_ACTION_ID` safe as the archive key?
