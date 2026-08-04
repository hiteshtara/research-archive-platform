# Central Administration Contacts

## The rule

There is no `AWARD_CENTRAL_ADMIN_CONTACTS` Oracle table, and there never
was one to archive. `AwardCentralAdminContact.java` is, in Kuali's own
source comments, "a minor hack" — a Java subclass of
`AwardUnitContact` with zero new fields, that exists purely so the UI
layer has a distinctly-typed bean to bind to. The actual Award Central
Administration Contacts panel is populated by
`AwardCentralAdminContactsBean.initCentralAdminContacts()`, which builds
**transient, never-persisted** objects on the fly at render time by
filtering `UnitAdministrator` records where `defaultGroupFlag == 'C'`.

## Why this matters

"Central Administration Contact" reads like an Award-scoped fact that
must live in some Award child table — it doesn't. It's a *derived view*
over `UnitAdministrator` data, keyed by the Award's lead unit, computed
at request time. Archiving it correctly means archiving
`archive.unit_administrator` (already done, as a shared reference
entity — see the `archive.unit`/`archive.unit_administrator`/
`archive.unit_administrator_type` precedent) and reproducing the
`defaultGroupFlag == 'C'` filter at query time, not looking for (or
inventing) a dedicated Award-scoped contacts table.

This is also a caution against a specific failure mode already seen once
in this project: `award_unit_contact`'s original schema (`V014`) invented
plausible-sounding columns (`unit_name`, `office_phone`, etc.) with no
basis in real Oracle DDL, and had to be dropped (`V033`) and rebuilt from
the real, narrower, double-verified shape. "The UI shows a field" does
not mean "there is a column for it" — sometimes the field is computed,
not stored.

## Evidence

- `AwardCentralAdminContact.java`,
  `AwardCentralAdminContactsBean.initCentralAdminContacts()` —
  `coeus-impl/src/main/java/org/kuali/kra/award/...`.
- `repository-award.xml` (OJB mapping, lines 191-196 and 752-800) —
  confirms `AwardCentralAdminContact` carries no independent
  `class-descriptor` fields beyond its parent.
- BU's own `Award.xml` DataDictionary override on
  `awardUnitContacts.awardContactId` confirms this area has active,
  real BU-specific customization layered on top of generic Kuali.

## See also

[`docs/architecture/AWARD_CONTACTS_DESIGN.md`](../architecture/AWARD_CONTACTS_DESIGN.md)
for the full derivation rule and the real `unit_administrator` schema.
