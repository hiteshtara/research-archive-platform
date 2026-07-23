# Protocol Core Reconciliation

Protocol Archive is canonical. The flat Excel-based IRB implementation is a
deprecated compatibility path. V004–V010 and the legacy loaders, APIs, views,
routes, UI, and global search remain unchanged until the dedicated retirement
milestone.

`archive.protocol_version` is the canonical future Protocol Core archive. It
is loaded independently from the verified Oracle CSV contract and does not
replace `archive.irb_protocol_version` during this phase.

## Run order

1. Apply migrations through V021.
2. Load `protocol_versions.csv` with `etl/load_protocols_from_csv.py`.
3. Run `sql/verify/protocol_core_reconciliation.sql` in PostgreSQL.
4. Save all four result sets with the load evidence.

Example:

```bash
psql "$DATABASE_URL" \
  --file sql/verify/protocol_core_reconciliation.sql
```

## Compared fields

Rows are matched by the physical Oracle `protocol_id`. The report compares:

- `protocol_number`
- `protocol_id`
- `sequence_number`
- status code and description
- title
- approval date
- expiration date

Differences are reported exactly, including whitespace and case. They must be
reviewed as source-contract or transformation differences rather than
silently normalized.

## Promotion gate

Protocol promotion must not remove the deprecated compatibility path until:

- the agreed source populations have zero unexplained missing records;
- all field mismatches are resolved or formally accepted;
- the reconciliation evidence is reviewed; and
- a separate promotion task explicitly authorizes the switch.

Retiring V004–V010 or any legacy loader, API, view, route, or UI requires a
separate cleanup milestone after Protocol feature parity.
