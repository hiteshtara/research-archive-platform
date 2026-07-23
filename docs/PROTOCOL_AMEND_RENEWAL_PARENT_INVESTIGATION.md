# Protocol Amendment/Renewal Parent Investigation

This gate covers only `KCOEUS.PROTO_AMEND_RENEWAL`. No archive migration or
implementation may proceed until the focused Oracle results are reviewed.

Run locally:

```text
oracle/protocol/parent_resolution/investigate_proto_amend_renewal_parent.sql
```

## Descriptor-confirmed fields

- Primary key: `PROTO_AMEND_RENEWAL_ID`
- Business number: `PROTO_AMEND_REN_NUMBER`
- Created date: `DATE_CREATED`
- Summary: `SUMMARY`
- Direct Protocol reference: `PROTOCOL_ID`
- Protocol business identity: `PROTOCOL_NUMBER`
- Protocol sequence: `SEQUENCE_NUMBER`
- Audit: `UPDATE_TIMESTAMP`, `UPDATE_USER`, `VER_NBR`, `OBJ_ID`

The descriptor defines a direct reference to `PROTOCOL` and a child
collection of `PROTO_AMEND_RENEW_MODULES`. It defines no submission or other
owner reference on `PROTO_AMEND_RENEWAL`; modules are children and cannot
resolve the parent's Protocol version.

## Existing evidence

The focused investigation confirmed 16,569 rows, 16,569 unique primary keys,
zero null Protocol identity fields, zero exact direct-ID matches, a 100%
direct-ID mismatch rate, 16,569 unique tuple parents, zero missing or
ambiguous tuple parents, and 16,569 tuple parents that differ from the
direct parent.

## Expected result sets

1. Source counts, identities, duplicates, and nulls.
2. Direct-ID parent coverage and mismatch categories.
3. Unique, missing, and ambiguous number/sequence parents.
4. Physical owner-chain eligibility.
5. Mutually exclusive resolution patterns.
6. Up to 25 identifier-only examples per pattern.
7. Installed Oracle foreign keys.

## Confirmed strategy

`NUMBER_SEQUENCE` is the canonical strategy. All 16,569 rows resolve to
exactly one Protocol parent. `PROTOCOL_ID` is retained as source audit data
and is never used as a fallback.

The focused metadata result confirmed that no owner chain is supported.

## Implementation promotion gates

- primary keys are non-null and unique;
- every accepted row resolves to exactly one historical Protocol version;
- missing and ambiguous tuple counts are zero, or reject populations receive
  explicit approval;
- all pattern counts are explained by representative examples;
- installed foreign keys are recorded;
- no unrestricted direct-ID fallback is needed; and
- the selected strategy accounts for all 16,569 source rows.
