# Protocol Location Parent Investigation

## Known evidence

The completed BU aggregate analysis reported:

- total rows: 40,336;
- direct-ID tuple matches: 38,202;
- number mismatches: 1,524;
- sequence mismatches: 610;
- unresolved exact number/sequence tuples: 1,524; and
- direct-ID mismatch rate: 5.2906%.

No `protocol_locations.csv` is currently available in the repository, so the
1,524 rows cannot be recalculated offline. No Oracle connection was made.

## Investigation package

Run:

```text
oracle/protocol/investigate_protocol_location_unresolved.sql
```

The script is SELECT-only and returns:

1. counts grouped by the exact source number and sequence;
2. placeholder and direct-ID coverage totals;
3. aggregate candidate counts;
4. counts grouped by direct-ID and tuple failure pattern;
5. proof counts for conservative normalized-number candidates;
6. 25 identifier-only examples; and
7. installed foreign-key metadata to confirm or reject another owner chain.

## Controlled hybrid promotion evidence

The first two result sets were used to confirm all of these:

- exactly 1,524 missing tuple rows;
- exactly 1,524 rows with `TRIM(PROTOCOL_NUMBER) = '0'` and
  `SEQUENCE_NUMBER = 0`;
- 1,518 distinct source Protocol IDs among those placeholder rows;
- zero other missing tuple patterns;
- 1,524 direct parents found and zero missing direct parents;
- all direct parents have sequence zero;
- no normalized Protocol-family candidate exists;
- each source direct ID resolves to one Oracle Protocol row; and
- every distinct direct ID exists as `protocol_id` in
  `archive.protocol_version`.

The final archive check must be run in PostgreSQL using the distinct
placeholder source IDs returned by the investigation. Oracle evidence alone
cannot prove archive presence.

## Candidate standards

- **Direct `PROTOCOL_ID`:** candidate evidence only. It is not proof when its
  family or sequence differs from the Location row.
- **Owner chain:** accepted only if installed metadata identifies another
  owner FK. The verified OJB descriptor currently exposes no such key.
- **Normalized number:** `UPPER(TRIM(PROTOCOL_NUMBER))` is the conservative
  candidate. A fallback is proven only when it returns exactly one Protocol
  row with the same sequence number.
- **Leading-zero normalization:** reported separately because it is more
  aggressive and cannot be promoted without business review.
- **Missing Protocol Core parent:** if Oracle has no exact or safely
  normalized tuple, this is a source-parent gap rather than an ETL lookup
  failure. If Oracle has a unique tuple but the exported Protocol Core does
  not, the Core export reconciliation must be corrected.

## Confirmed resolution

All controlled-hybrid gates passed: the 1,524 missing tuples are source
`(0, 0)`, their 1,518 distinct direct IDs all have valid sequence-zero Oracle
parents, none is missing, and all direct parents exist in
`archive.protocol_version`. Oracle enforces the direct relationship with
`FK_PROTOCOL_LOCATION`.

The implemented exception is limited to that literal placeholder tuple and
records `DIRECT_ID_PLACEHOLDER`. All ordinary rows remain
`NUMBER_SEQUENCE`. Missing non-placeholder tuples, ambiguous tuples, and
placeholder rows without an archive direct parent are rejected. No normalized
or unrestricted direct-ID fallback exists.
