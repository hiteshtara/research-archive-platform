# Protocol Personnel Reconciliation

Run `sql/verify/protocol_personnel_reconciliation.sql` after each load.
Promotion requires unique physical keys, zero orphan archive parents, and zero
protocol-family/sequence mismatches. Units must resolve to their exact
`PROTOCOL_PERSON_ID` parent.

KC source `PROTOCOL_ID` is preserved as `source_protocol_id` for evidence
only. `protocol_id` is resolved from `PROTOCOL_NUMBER` and
`SEQUENCE_NUMBER`; source-ID differences are expected and reported rather
than treated as parent-key failures.

V023 adds `source_protocol_id` without rewriting the applied V022 migration.
It first preserves the existing V022 `protocol_id` value, then the repeatable
Personnel load replaces `protocol_id` with the uniquely resolved archive
parent.

The report includes total and uniquely resolved Personnel rows; missing and
ambiguous tuple parents; source/resolved ID differences; protocol-number and
sequence mismatches; unit orphans; and unit/person consistency mismatches.
Missing or ambiguous tuple parents fail the load without a source-ID fallback.

`PROTOCOL_UNITS` is not independently parented to Protocol. Each unit resolves
through `protocol_person_id`, and therefore inherits the owning person's
resolved `protocol_id`. Unit `protocol_number` and `sequence_number` remain
source audit fields: differences from the person are reported but do not
reparent or reject the unit. Missing or ambiguous person ownership and
unexplained non-null `person_id` disagreement are load failures.

Personnel remains associated with every physical Protocol version; the
reconciliation does not select or collapse to the latest sequence.
