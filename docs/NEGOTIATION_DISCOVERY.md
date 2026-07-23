# Negotiation Oracle Discovery

## Purpose

The Negotiations archive will preserve read-only historical Kuali research
administration Negotiation records after the source system is retired. The
source model must be established from Oracle metadata before extraction,
PostgreSQL design, ETL, API, or UI work begins.

Proposal is the archive's central research object. Negotiation relationships
to Proposal, Award, Subaward, and any other supported Kuali module must be
preserved when the source keys and association semantics are verified.

## Source and network boundary

- The expected source system is BU Kuali in Oracle; `KCOEUS` is the expected
  owner based on Award and Proposal work, but the discovery output must confirm
  the owner for Negotiation objects.
- BU Oracle is reachable only from the user's local computer through the BU
  VPN/private network.
- AWS must never connect directly to BU Oracle.
- Metadata discovery and future extraction run locally.
- Only reviewed and approved exported files may be uploaded to or loaded in
  AWS.
- Discovery scripts contain no credentials and return metadata only.

## Known candidate source objects

- `NEGOTIATION_ASSOCIATION` was used by a prior local query, but its owner,
  columns, keys, and relationship semantics have not been validated here.
- Names containing `NEGOTIATION`, `NEGOTIATION_ACTIVITY`,
  `NEGOTIATION_LOCATION`, `NEGOTIATION_PERSON`, `NEGOTIATION_ROLE`, and
  `NEGOTIATION_STATUS` are discovery candidates, not confirmed object names.
- The repository's existing `archive.negotiation` SQL is a legacy PostgreSQL
  design artifact. It is not evidence of Oracle table or column names and must
  not drive the source extraction.

## Metadata questions to resolve

The local Oracle results must establish:

1. Which owner contains the authoritative Negotiation tables or views.
2. The authoritative parent object and its primary or stable business key.
3. Whether Negotiations are versioned and, if so, the version and current-row
   rules.
4. Whether `NEGOTIATION_ASSOCIATION` is a child of the parent Negotiation
   object and which columns form that join.
5. How an association identifies Proposal, Award, Subaward, or another module:
   direct IDs, document numbers, module/type codes, or another lookup.
6. Whether association values point to source row IDs, stable business
   identifiers, document-header identifiers, or mixed identifier types.
7. Which objects contain activities, status history, locations, people, roles,
   units, sponsors, agreement types, and other lookup descriptions.
8. Cardinality and uniqueness for parent, association, activity, location, and
   person records.
9. Available created/updated timestamps and source user/version fields.
10. Whether deleted, inactive, cancelled, or superseded records remain in the
    source and must be retained.
11. Whether the archive includes all historical Negotiations or a reviewed
    date-bounded subset. The previously discussed approximate period of
    2023-01-01 through 2026-06-15 is context only and is not an approved
    extraction boundary.

## Expected parent and child entities

These are planning categories to confirm, not PostgreSQL table designs:

- Negotiation parent/current and historical records
- Negotiation associations to other research-administration records
- Negotiation activities or status history
- Negotiation people and their roles
- Negotiation locations or organizational assignments
- Negotiation status, activity type, role, agreement type, and other lookups

Likely cross-links require special validation:

- Negotiation to Proposal
- Negotiation to Award
- Negotiation to Subaward
- Negotiation to any additional module identified by association metadata

No relationship should be implemented until both sides' identifiers and the
association type/module rules are confirmed.

## Future workspace field inventory

The future workspace will likely need the following information where Oracle
metadata and sample distributions prove it exists:

- Stable Negotiation identifier and display/business number
- Title or description
- Status and status history
- Agreement or Negotiation type
- Start, completion, created, and updated dates
- Sponsor and lead unit
- Assigned people, contact roles, and negotiators
- Activities, notes metadata, and locations
- Associated Proposal, Award, Subaward, and other record identifiers
- Source update timestamp, source user, and source version metadata

This inventory is not an extraction column list. Exact fields and names must
come from the discovery output.

## Local Oracle discovery procedure

1. Connect the local computer to the BU VPN/private network.
2. Open the approved local Oracle client and connect to BU Kuali Oracle using
   locally managed credentials. Do not place credentials in this repository or
   in captured output.
3. Run `oracle/negotiation/negotiation_tables.sql` first. Save the complete
   result with column headings.
4. Run `oracle/negotiation/negotiation_columns.sql` second. Save the complete
   result with column headings and without truncating long object names.
5. Run `oracle/negotiation/negotiation_relationships.sql` third. Save the
   complete result with column headings.
6. Review the inventory output before using any commented row-count template.
   Substitute only owner and object names confirmed by metadata. Do not paste
   credentials or sensitive BU data.
7. If approved for follow-up analysis, return aggregate row counts and value
   distributions only. Do not return unrestricted record-level BU data.

## Output to return for review

Paste back:

- The complete candidate object inventory: owner, object name, object type,
  and status
- The complete column inventory for every matching object
- The relationship-column inventory
- Any Oracle errors or permission limitations
- Row counts only for metadata-confirmed objects, if approved and run
- Aggregate null/distinct counts for proposed keys only when requested after
  this metadata review
- Confirmation of whether KCOEUS is the authoritative owner
- A decision from the archive owner on all-history versus date-bounded scope

Do not paste credentials, connection strings, or unrestricted BU record data.

## Required gate

PostgreSQL migrations and ETL must wait until the Oracle metadata, parent/child
keys, association semantics, cardinality, and historical scope are validated.
Extraction SQL must then be written and reviewed before any export is loaded.
