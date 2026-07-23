# Protocol Personnel CSV Contract

## Files

### `protocol_persons.csv`

Source: `KCOEUS.PROTOCOL_PERSONS`

Primary key: `protocol_person_id`

Source Protocol ID: `source_protocol_id`

Resolved parent key: `protocol_id` →
`archive.protocol_version.protocol_id`

Header:

```text
protocol_person_id,source_protocol_id,protocol_number,sequence_number,person_id,person_name,protocol_person_role_id,rolodex_id,affiliation_type_code,comments,source_update_timestamp,source_update_user,source_version_number,source_object_id
```

### `protocol_units.csv`

Source: `KCOEUS.PROTOCOL_UNITS`

Primary key: `protocol_units_id`

Parent key: `protocol_person_id` →
`archive.protocol_person.protocol_person_id`

The unit's `protocol_number` and `sequence_number` are source audit fields,
not independent parent keys. Its Protocol parent chain is
`protocol_person_id` → archived person → that person's resolved `protocol_id`.

Header:

```text
protocol_units_id,protocol_person_id,protocol_number,sequence_number,unit_number,lead_unit_flag,person_id,source_update_timestamp,source_update_user,source_version_number,source_object_id
```

## Load and reconciliation order

1. `archive.protocol_version`
2. `archive.protocol_person`
3. `archive.protocol_unit`

## KC parent-resolution rule

`PROTOCOL_PERSONS.PROTOCOL_ID` points to KC's current Protocol row and is not
the historical parent identifier. The archive preserves it as
`source_protocol_id` but never uses it as a foreign key.

The loader resolves `protocol_id` exclusively through the exact
`(PROTOCOL_NUMBER, SEQUENCE_NUMBER)` pair. It rejects missing or ambiguous
pairs rather than silently selecting a physical Protocol row.

`PROTOCOL_UNITS` uses `OWNER_CHAIN`; number/sequence differences from its
owning person are reported without reparenting the unit.

## Privacy boundary

The contract intentionally excludes SSN, birth date, age, gender, race,
disability, veteran, visa, citizenship, private address, and personal phone
data. No omitted sensitive source column may be added without a separate
privacy review.
