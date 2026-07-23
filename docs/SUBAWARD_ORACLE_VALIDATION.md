# Subaward Oracle Validation

## Purpose

Run `oracle/subaward/validate_subaward_exports.sql` locally against BU Oracle before approving the Subaward CSV exports. The package is read-only and uses only verified physical columns from `reference/kuali/subaward-oracle-columns.txt` and relationships declared by `reference/kuali/subaward-ojb.xml`.

The validation script does not change the export SQL and does not inspect backup, deleted, temporary, conversion, or PMC tables.

## Local execution

1. Connect locally to BU Oracle through the BU VPN/private network with a read-only account that can query the `KCOEUS` objects and `ALL_CONSTRAINTS`, `ALL_CONS_COLUMNS`, `ALL_INDEXES`, and `ALL_IND_COLUMNS`.
2. Enable headings and avoid truncating CLOB/sample output in the Oracle client.
3. Spool the complete output to a local text file approved for BU data handling.
4. Run `oracle/subaward/validate_subaward_exports.sql` without editing table names or filters.
5. Sanitize the ten-row samples before sharing them. Do not paste confidential note, notification, contact, or attachment data into tickets or source control.
6. Record every nonzero orphan, duplicate, missing-lookup, null-key, and inconsistent-business-key result.
7. Run the corresponding `oracle/subaward/export_*.sql` only after the relevant validation section is accepted.

## Validation sections

### Installed constraints and indexes

The first two result sets inventory installed PK/UK/FK constraints and indexes for the thirteen source, parent, and extension tables used by the exports. Review whether descriptor primary keys and parent relationships are physically enforced, enabled, and validated. Absence of a constraint does not invalidate the descriptor relationship, but it requires ETL-side validation later.

### Subawards

Checks:

- Total and distinct `SUBAWARD_ID` counts, duplicate and null primary keys.
- Orphan `DOCUMENT_NUMBER` values and status lookup coverage.
- Business and audit date ranges.
- Audit-field null counts and important-field null percentages.
- Ten ordered source rows.
- Family count and sequence-number range/null coverage.
- Duplicate `(SUBAWARD_CODE, SEQUENCE_NUMBER)` versions.
- Documents associated with more than one `SUBAWARD_CODE`.

Expected interpretation: `SUBAWARD_ID` must be unique and non-null. Any duplicate family/sequence pair or document spanning multiple families must be explained before current-version rules are designed.

### Amounts

Checks:

- Primary-key uniqueness and parent orphans.
- Modification-type lookup coverage.
- Effective, modification, performance, and audit ranges.
- Audit nulls, business-field null percentages, and file-metadata population.
- Sequence coverage and consistency with the parent version.
- Ten ordered rows.

### Contacts

Checks primary keys, Subaward parents, audit coverage, business null percentages, sequence coverage, parent business-key consistency, and ten rows. Contact-type and Rolodex coverage cannot be checked from the supplied Subaward inventory.

### Custom data

Checks primary keys, Subaward parents, audit coverage, custom-attribute/value null percentages, sequence coverage, parent business-key consistency, and ten rows. Custom-attribute lookup coverage remains external.

### Funding

Checks primary keys, Subaward parents, audit coverage, `AWARD_ID` population, sequence coverage, parent business-key consistency, and ten rows. Award lookup coverage remains external.

### Attachments

Checks:

- Primary-key uniqueness, parent orphans, and attachment-type coverage.
- Audit/last-update ranges and audit null counts.
- Null percentages for type and file metadata.
- Counts populated for `FILE_DATA_ID`, `DOCUMENT_ID`, `FILE_NAME`, and `MIME_TYPE`, including file IDs without names.
- Sequence coverage and parent business-key consistency.
- Ten metadata rows. No binary file content is queried.

### Closeout

Checks primary keys, parents, requested/follow-up/received and audit date ranges, audit nulls, business null percentages, sequence coverage, parent consistency, and ten rows. The closeout-type lookup cannot be checked because `CLOSEOUT_TYPE` is absent from the verified inventory.

### Reports

Checks primary keys, parents, report-type lookup coverage, audit range/nulls, business null percentages, sequence coverage, parent consistency, and ten rows.

### Notepad

Checks primary keys, parents, create/update ranges and audit nulls, topic/comment/restriction population, parent `SUBAWARD_CODE` consistency, and ten rows. The physical table has no `SEQUENCE_NUMBER`, so sequence coverage is not applicable. Samples must be sanitized because notes may be restricted.

### Notifications

Checks:

- Explicit notification row count, primary-key uniqueness, and null keys.
- Descriptor-defined `OWNING_DOCUMENT_ID_FK` parent coverage.
- `DOCUMENT_NUMBER` coverage against `SUBAWARD_DOCUMENT`.
- Create/update ranges, audit nulls, and business-field null percentages.
- Parent `SUBAWARD_CODE` and `DOCUMENT_NUMBER` consistency.
- Ten rows, which must be sanitized because recipients, subject, and message can be sensitive.

The notification-type lookup is external to the supplied inventory and is not queried.

### Template info

Checks primary keys, parents, business/audit date ranges, update-audit nulls, important-field null percentages, sequence coverage, parent business-key consistency, ten rows, and one-row-per-parent behavior. `SUBAWARD_TEMPLATE_INFO` has no physical `VER_NBR` or `OBJ_ID`, so those audit checks are not applicable.

## Lookup coverage included

The package validates only lookups whose physical tables and columns occur in the verified inventory:

- `SUBAWARD.STATUS_CODE` → `SUBAWARD_STATUS.SUBAWARD_STATUS_CODE`.
- `SUBAWARD_AMOUNT_INFO.MODIFICATION_TYPE_CODE` → `SUBAWARD_MODIFICATION_TYPE.CODE`.
- `SUBAWARD_ATTACHMENTS.ATTACHMENT_TYPE_CODE` → `SUBAWARD_ATTACHMENT_TYPE.ATTACHMENT_TYPE_CODE`.
- `SUBAWARD_REPORTS.REPORT_TYPE_CODE` → `SUBAWARD_REPORT_TYPE.REPORT_TYPE_CODE`.

## Unresolved checks

The following cannot be expressed without guessing tables or columns and are intentionally absent from the SQL package:

- Organization, Award type, Unit, Rolodex, cost type, Award, contact type, custom attribute, notification type, closeout type, copyright type, and other template-code lookup coverage.
- File payload existence and integrity behind `FILE_DATA_ID` or `DOCUMENT_ID`; the backing file tables are not in the verified inventory.
- Oracle column nullability, data types, lengths, and precision/scale; the supplied inventory contains only table and column names.
- Whether every descriptor parent relationship has an installed Oracle FK. The metadata result sets reveal installed constraints but do not assert absent constraints.
- Notification parent semantics. OJB maps `OWNING_DOCUMENT_ID_FK` to `SUBAWARD_ID`, but both that relationship and `DOCUMENT_NUMBER` consistency require review of the returned counts.
- Whether absent external lookup matches represent invalid data, inactive history, or legitimate retired codes.
- Whether `SUBAWARD_NOTEPAD` restricted content and notification message content are approved for archival.
- Binary/content validation for amount-released documents, template attachments, FFATA files, and forms, which are outside the current export contracts.

## Acceptance record

For each section, retain the row count, distinct-key count, duplicate/null-key result, orphan count, lookup-miss count where available, date ranges, audit nulls, business null percentages, and sanitized sample review. Do not proceed to migration or ETL design until all nonzero integrity findings have an explicit disposition.
