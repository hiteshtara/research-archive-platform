# Protocol Funding Reconciliation

Run `sql/verify/protocol_funding_reconciliation.sql` after loading.

Promotion requires:

- unique physical `protocol_funding_source_id` values;
- every row resolved to exactly one Protocol number/sequence parent;
- zero resolved-parent number or sequence mismatches; and
- no unexplained row-count difference from the source extract.

The source/resolved Protocol ID difference count is audit evidence. The
measured source analysis predicts 192 such sequence-parent differences; these
rows are retained and reparented through the exact business-version tuple.
