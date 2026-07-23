# Negotiation CSV Contract

## Scope

These files are local, read-only exports from `KCOEUS` through the BU private
network. AWS must not connect to BU Oracle. No date filter is applied; the final
historical scope remains a project decision before migration and ETL design.

The Kuali OJB descriptor establishes the business-object fields and
relationships. Verified BU Oracle metadata establishes the source objects and
the legacy `NEGOTIATION.NEGOTATION_STATUS_ID` spelling. The CSV field is
`negotiation_status_id`, sourced from
`KCOEUS.NEGOTIATION.NEGOTATION_STATUS_ID`. CSV tools must not coerce text
identifiers or codes to numbers.

## CSV files

### `negotiations.csv`

Header:

```text
negotiation_id,document_number,negotiation_status_id,negotiation_status_code,negotiation_status_description,negotiation_agreement_type_id,negotiation_agreement_type_code,negotiation_agreement_type_description,negotiation_association_type_id,negotiation_association_type_code,negotiation_association_type_description,negotiator_person_id,negotiator_full_name,negotiation_start_date,negotiation_end_date,anticipated_award_date,document_folder,associated_document_id,update_timestamp,update_user,ver_nbr,obj_id,document_update_timestamp,document_update_user,document_ver_nbr,document_obj_id
```

- Primary key: `negotiation_id`.
- Document relationship: `document_number` joins `NEGOTIATION_DOCUMENT`.
- Lookup fields: status, agreement type, and association type raw IDs plus
  codes and descriptions.
- Date/timestamp fields: `negotiation_start_date`, `negotiation_end_date`,
  `anticipated_award_date`, `update_timestamp`, `document_update_timestamp`.
- Text requiring exact preservation: `document_number`,
  `associated_document_id`, `negotiator_person_id`, and all lookup codes.
- Nullable fields: treat every non-primary-key field as nullable until BU
  nullability metadata is captured and validated.

### `negotiation_activities.csv`

Header:

```text
negotiation_activity_id,negotiation_id,activity_type_id,activity_type_code,activity_type_description,location_id,location_code,location_description,start_date,end_date,create_date,followup_date,last_modified_user,last_modified_date,description,restricted,update_timestamp,update_user,ver_nbr,obj_id
```

- Primary key: `negotiation_activity_id`.
- Parent foreign key: `negotiation_id`.
- Lookup fields: activity type and location raw IDs plus codes and
  descriptions.
- Date/timestamp fields: `start_date`, `end_date`, `create_date`,
  `followup_date`, `last_modified_date`, `update_timestamp`.
- Text requiring exact preservation: `activity_type_code`, `location_code`,
  `last_modified_user`, `restricted`, `update_user`, and `obj_id`.
- Nullable fields: treat every non-primary-key field as nullable pending BU
  nullability validation.

### `negotiation_custom_data.csv`

Header:

```text
negotiation_custom_data_id,negotiation_id,negotiation_number,custom_attribute_id,value,update_timestamp,update_user,ver_nbr,obj_id
```

- Primary key: `negotiation_custom_data_id`.
- Parent foreign key: `negotiation_id`.
- Lookup fields: `custom_attribute_id` is retained raw; its lookup object is
  not yet verified and is not joined.
- Date/timestamp fields: `update_timestamp`.
- Text requiring exact preservation: `negotiation_number`, `value`,
  `update_user`, and `obj_id`.
- Nullable fields: treat every non-primary-key field as nullable pending BU
  nullability validation.

### `negotiation_notifications.csv`

Header:

```text
notification_id,notification_type_id,document_number,owning_document_id_fk,recipients,subject,message,update_timestamp,update_user,ver_nbr,obj_id
```

- Primary key: `notification_id`.
- Parent foreign key: `owning_document_id_fk` maps to `negotiation_id` in the
  descriptor; `document_number` preserves the workflow-document relationship.
- Lookup fields: `notification_type_id` is retained raw because its common
  lookup object is not verified.
- Date/timestamp fields: `update_timestamp`.
- Text requiring exact preservation: `document_number`, `recipients`,
  `subject`, `message`, `update_user`, and `obj_id`.
- Nullable fields: treat every non-primary-key field as nullable pending BU
  nullability validation.

### `negotiation_unassociated.csv`

Header:

```text
negotiation_unassoc_detail_id,negotiation_id,title,pi_person_id,pi_rolodex_id,lead_unit,sponsor_code,pi_name,prime_sponsor_code,sponsor_award_number,contact_admin_person_id,subaward_org,update_timestamp,update_user,ver_nbr,obj_id
```

- Primary key: `negotiation_unassoc_detail_id`.
- Parent foreign key: `negotiation_id`.
- Lookup fields: unit, sponsor, prime sponsor, organization, and Rolodex
  identifiers are retained raw; those lookup objects are outside the verified
  Negotiation object set.
- Date/timestamp fields: `update_timestamp`.
- Text requiring exact preservation: `pi_person_id`, `pi_rolodex_id`,
  `lead_unit`, `sponsor_code`, `prime_sponsor_code`,
  `sponsor_award_number`, `contact_admin_person_id`, `subaward_org`, and
  `obj_id`.
- Nullable fields: treat every non-primary-key field as nullable pending BU
  nullability validation.

### Attachments

`negotiation_attachments.csv` is not part of this contract yet. Although the
descriptor declares `NEGOTIATION_ATTACHMENT`, that table was not included in
the verified BU Oracle object list. The referenced attachment-file object,
file metadata columns, content storage, and binary export mechanism also remain
unverified. No attachment SQL is provided until those details are confirmed.

## Intended load order

1. `negotiations.csv`
2. `negotiation_activities.csv`
3. `negotiation_custom_data.csv`
4. `negotiation_notifications.csv`
5. `negotiation_unassociated.csv`
6. `negotiation_attachments.csv`, only after a future verified contract exists

Lookup descriptions are denormalized into the parent exports, so separate
lookup CSVs are not required by this contract.

## Unresolved items

- The business semantics of each association-type code and description.
- Whether each `associated_document_id` identifies an Award, Proposal,
  Subaward, or another record type; interpretation must use the association
  type and be validated against source data.
- The attachment table's presence in BU Oracle, attachment-file source,
  metadata fields, content storage, restrictions, and binary handling.
- Whether all notification types and message/recipient content are approved
  for archival, and whether the common notification-type lookup is needed.
- How inactive lookup values and any deleted source rows are represented;
  exports retain rows resolved to inactive lookup records, but source delete
  history is not established.
- Whether all historical Negotiation records are in scope or an approved date
  boundary is required. No extraction query currently applies a date filter.
- Oracle nullability for child, lookup, and document columns.
- The custom-attribute lookup object and whether attribute names/descriptions
  must be included in a future contract.
- CSV field `negotiation_status_id` maps to Oracle source
  `KCOEUS.NEGOTIATION.NEGOTATION_STATUS_ID`. The lookup retains the correctly
  spelled `KCOEUS.NEGOTIATION_STATUS.NEGOTIATION_STATUS_ID` column.

PostgreSQL migrations and ETL must wait until the exports and unresolved
relationship semantics are validated against BU Oracle.
