# Negotiation Oracle Validation Package

Run `oracle/negotiation/validate_negotiation_exports.sql` locally through the
BU VPN using a read-only Oracle session. Run each labeled result set in order
and retain the output with the export review. The script performs no DDL or
DML.

## Negotiations

Export: `export_negotiations.sql`

Referenced tables and columns:

- `KCOEUS.NEGOTIATION`: `NEGOTIATION_ID`, `DOCUMENT_NUMBER`,
  `NEGOTATION_STATUS_ID`, `NEGOTIATION_AGREEMENT_TYPE_ID`,
  `NEGOTIATION_ASSC_TYPE_ID`, `NEGOTIATOR_PERSON_ID`,
  `NEGOTIATOR_FULL_NAME`, `NEGOTIATION_START_DATE`, `NEGOTIATION_END_DATE`,
  `ANTICIPATED_AWARD_DATE`, `DOCUMENT_FOLDER`, `ASSOCIATED_DOCUMENT_ID`,
  `UPDATE_TIMESTAMP`, `UPDATE_USER`, `VER_NBR`, `OBJ_ID`.
- `KCOEUS.NEGOTIATION_DOCUMENT`: `DOCUMENT_NUMBER`, `UPDATE_TIMESTAMP`,
  `UPDATE_USER`, `VER_NBR`, `OBJ_ID`.
- `KCOEUS.NEGOTIATION_STATUS`: `NEGOTIATION_STATUS_ID`,
  `NEGOTIATION_STATUS_CODE`, `DESCRIPTION`.
- `KCOEUS.NEGOTIATION_AGREEMENT_TYPE`: `NEGOTIATION_AGRMNT_TYPE_ID`,
  `NEGOTIATION_AGRMNT_TYPE_CODE`, `DESCRIPTION`.
- `KCOEUS.NEGOTIATION_ASSOCIATION_TYPE`: `NEGOTIATION_ASSC_TYPE_ID`,
  `NEGOTIATION_ASSC_TYPE_CODE`, `DESCRIPTION`.

Lookup tables: `NEGOTIATION_STATUS`, `NEGOTIATION_AGREEMENT_TYPE`, and
`NEGOTIATION_ASSOCIATION_TYPE`. `NEGOTIATION_DOCUMENT` is a document parent,
not a lookup.

Known key expectations from the descriptor:

- Primary key: `NEGOTIATION.NEGOTIATION_ID`.
- Document primary key: `NEGOTIATION_DOCUMENT.DOCUMENT_NUMBER`.
- Lookup primary keys: each lookup table's ID column listed above.
- Relationship/index candidates: `NEGOTIATION.DOCUMENT_NUMBER`,
  `NEGOTIATION.NEGOTATION_STATUS_ID`,
  `NEGOTIATION.NEGOTIATION_AGREEMENT_TYPE_ID`, and
  `NEGOTIATION.NEGOTIATION_ASSC_TYPE_ID`.

## Activities

Export: `export_negotiation_activities.sql`

Referenced tables and columns:

- `KCOEUS.NEGOTIATION_ACTIVITY`: `NEGOTIATION_ACTIVITY_ID`,
  `NEGOTIATION_ID`, `ACTIVITY_TYPE_ID`, `LOCATION_ID`, `START_DATE`,
  `END_DATE`, `CREATE_DATE`, `FOLLOWUP_DATE`, `LAST_MODIFIED_USER`,
  `LAST_MODIFIED_DATE`, `DESCRIPTION`, `RESTRICTED`, `UPDATE_TIMESTAMP`,
  `UPDATE_USER`, `VER_NBR`, `OBJ_ID`.
- `KCOEUS.NEGOTIATION_ACTIVITY_TYPE`: `NEGOTIATION_ACTIVITY_TYPE_ID`,
  `NEGOTIATION_ACTIVITY_TYPE_CODE`, `DESCRIPTION`.
- `KCOEUS.NEGOTIATION_LOCATION`: `NEGOTIATION_LOCATION_ID`,
  `NEGOTIATION_LOCATION_CODE`, `DESCRIPTION`.
- Parent validation also references `KCOEUS.NEGOTIATION.NEGOTIATION_ID`.

Lookup tables: `NEGOTIATION_ACTIVITY_TYPE` and `NEGOTIATION_LOCATION`.

Known key expectations from the descriptor:

- Primary key: `NEGOTIATION_ACTIVITY.NEGOTIATION_ACTIVITY_ID`.
- Parent foreign key: `NEGOTIATION_ACTIVITY.NEGOTIATION_ID`.
- Lookup primary keys: `NEGOTIATION_ACTIVITY_TYPE_ID` and
  `NEGOTIATION_LOCATION_ID`.
- Relationship/index candidates: `NEGOTIATION_ID`, `ACTIVITY_TYPE_ID`, and
  `LOCATION_ID` on `NEGOTIATION_ACTIVITY`.

## Custom data

Export: `export_negotiation_custom_data.sql`

Referenced tables and columns:

- `KCOEUS.NEGOTIATION_CUSTOM_DATA`: `NEGOTIATION_CUSTOM_DATA_ID`,
  `NEGOTIATION_ID`, `NEGOTIATION_NUMBER`, `CUSTOM_ATTRIBUTE_ID`, `VALUE`,
  `UPDATE_TIMESTAMP`, `UPDATE_USER`, `VER_NBR`, `OBJ_ID`.
- Parent validation also references `KCOEUS.NEGOTIATION.NEGOTIATION_ID`.

Lookup tables: none verified. `CUSTOM_ATTRIBUTE_ID` is preserved, but its
descriptor-referenced common lookup object is outside the verified object set.

Known key expectations from the descriptor:

- Primary key: `NEGOTIATION_CUSTOM_DATA.NEGOTIATION_CUSTOM_DATA_ID`.
- Parent foreign key: `NEGOTIATION_CUSTOM_DATA.NEGOTIATION_ID`.
- Relationship/index candidates: `NEGOTIATION_ID` and `CUSTOM_ATTRIBUTE_ID`.

## Notifications

Export: `export_negotiation_notifications.sql`

Referenced tables and columns:

- `KCOEUS.NEGOTIATION_NOTIFICATION`: `NOTIFICATION_ID`,
  `NOTIFICATION_TYPE_ID`, `DOCUMENT_NUMBER`, `OWNING_DOCUMENT_ID_FK`,
  `RECIPIENTS`, `SUBJECT`, `MESSAGE`, `UPDATE_TIMESTAMP`, `UPDATE_USER`,
  `VER_NBR`, `OBJ_ID`.
- Parent validation references `KCOEUS.NEGOTIATION.NEGOTIATION_ID` and
  `KCOEUS.NEGOTIATION_DOCUMENT.DOCUMENT_NUMBER`.

Lookup tables: none verified. The descriptor references a common notification
type object, but its table is not part of the verified Negotiation object set.

Known key expectations from the descriptor:

- Primary key: `NEGOTIATION_NOTIFICATION.NOTIFICATION_ID`.
- Parent foreign key: `OWNING_DOCUMENT_ID_FK` maps to
  `NEGOTIATION.NEGOTIATION_ID` in the descriptor.
- Relationship/index candidates: `OWNING_DOCUMENT_ID_FK`, `DOCUMENT_NUMBER`,
  and `NOTIFICATION_TYPE_ID`.

## Unassociated

Export: `export_negotiation_unassociated.sql`

Referenced tables and columns:

- `KCOEUS.NEGOTIATION_UNASSOC_DETAIL`:
  `NEGOTIATION_UNASSOC_DETAIL_ID`, `NEGOTIATION_ID`, `TITLE`, `PI_PERSON_ID`,
  `PI_ROLODEX_ID`, `LEAD_UNIT`, `SPONSOR_CODE`, `PI_NAME`,
  `PRIME_SPONSOR_CODE`, `SPONSOR_AWARD_NUMBER`, `CONTACT_ADMIN_PERSON_ID`,
  `SUBAWARD_ORG`, `UPDATE_TIMESTAMP`, `UPDATE_USER`, `VER_NBR`, `OBJ_ID`.
- Parent validation also references `KCOEUS.NEGOTIATION.NEGOTIATION_ID`.

Lookup tables: none in the verified export. The descriptor references common
unit, sponsor, organization, and Rolodex objects; their physical Oracle tables
and columns must be verified before lookup joins are added.

Known key expectations from the descriptor:

- Primary key:
  `NEGOTIATION_UNASSOC_DETAIL.NEGOTIATION_UNASSOC_DETAIL_ID`.
- Parent foreign key: `NEGOTIATION_UNASSOC_DETAIL.NEGOTIATION_ID`.
- Relationship/index candidates: `NEGOTIATION_ID`, `LEAD_UNIT`,
  `SPONSOR_CODE`, `PRIME_SPONSOR_CODE`, `PI_ROLODEX_ID`, and `SUBAWARD_ORG`.

## Interpreting results

- Primary-key totals must equal distinct-key totals, duplicate queries must
  return no rows, and NULL primary-key counts must be zero.
- Orphan and missing-lookup counts should be zero. Any nonzero value must be
  reviewed before the CSV contract is approved.
- A nonzero NULL lookup-description count means the ID resolved but the lookup
  record lacks its human-readable description.
- Review MIN/MAX dates for impossible future dates, unexpected truncation, and
  historical-scope implications.
- NULL percentages are descriptive. Do not treat optional business fields as
  errors without confirming their Kuali semantics.
- The final constraint/index queries report what is actually installed in BU
  Oracle. The descriptor's key declarations are expectations, not proof of a
  physical constraint or index.
