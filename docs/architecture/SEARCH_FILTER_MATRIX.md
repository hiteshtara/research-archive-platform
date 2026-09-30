# Search filter matrix

Branch `feat/standardized-search-filters` (base `origin/main` 785b04f). Every
row is taken from the code on this branch; "existing" means it was already on
`origin/main`, "new" means this branch adds it. URL parameter names equal the
API parameter names, except free text, which is always `?q=` in the URL.

All structured filters are **server-side**: they are ANDed with the free-text
query and with each other in the SQL `WHERE`, and the page query and its count
share that exact `WHERE`, so paging and totals describe the complete filtered
result set. Date bounds are **inclusive on both ends** (`>= From`, `<= To`);
the UI rejects a From date later than its To date. "Contains" matching is
case-insensitive `ILIKE '%value%'`.

## Awards — current Award families (`/awards/search` → `GET /api/v1/awards/search`)

Grain: one row per Award family (`award_version.is_primary_current = TRUE`).
Filters apply to that current version.

| UI label | Param | Matching | Column(s) | Status |
|---|---|---|---|---|
| Search box | `q` | contains (`*` wildcard supported) over award number, title, sponsor code/name, lead unit number/name, modification number, any person's name, family-wide Grant Number (exact or contains); exact Award number match | `award_version`, `award_person`, `award_extension` | existing |
| Status | `status` | exact, case-insensitive (not contains: "Active" ≠ "Inactive") | `status_description` | new |
| Sponsor | `sponsor` | contains, name **or** code | `sponsor_name`, `sponsor_code` | new |
| Principal Investigator | `principalInvestigator` | contains; people with contact role PI or MPI (BU "Co-PI") on the current version. COI and KP never match; free text still matches any role | `award_person.full_name` | new |
| Lead Unit | `leadUnit` | contains, name **or** number | `lead_unit_name`, `lead_unit_number` | new |
| Project Start Date from / to | `projectStartDateFrom` / `projectStartDateTo` | inclusive dates | `award_effective_date` (Kuali "Project Start Date"; never `begin_date`) | new |
| (count) | — | the count now includes the family-wide Grant Number branch its page query always had | — | **bug fix** |
| Exact workflow document callout | — | suppressed while structured filters are applied (it is not subject to them) | — | new behaviour |

## Historical Award Records — every Award version (`/awards/versions/search` → `GET /api/v1/awards/versions/search`)

Grain: one row per `award_id` (version). Filters apply to each version.

| UI label | Param | Matching | Status |
|---|---|---|---|
| Search box | `q` | contains over award number, workflow document number, title, sponsor, lead unit, any person | existing |
| Award Number (exact) | `awardNumber` | exact, case-insensitive | existing (moved into the panel) |
| Document Number (exact) | `documentNumber` | exact, case-insensitive (workflow document number) | existing (moved into the panel) |
| Award ID (exact) | `awardId` | exact whole number; non-numeric rejected in the UI and 400 from the API | existing (moved into the panel) |
| Versions | `versionFilter` | `all` (default) / `current` / `historical` on `is_primary_current` | existing (moved into the panel) |
| Status, Sponsor, Principal Investigator, Lead Unit, Project Start Date from/to | as Awards | as Awards, per version | new |
| Sort (outside the panel) | `sort` | `sequence` (default) / `date`; not a filter, not counted | existing |

The current-family vs historical-version distinction is preserved: two pages,
two endpoints, and the Versions filter.

## Proposals (`/proposals` → new `GET /api/proposals/search`)

Grain: one row per Proposal family = its latest version (`version_number DESC,
source_update_timestamp DESC, proposal_id DESC`). Filters apply to that latest
version. Stable order: `proposal_number` (unique per family).

| UI label | Param | Matching | Status |
|---|---|---|---|
| Search box | `query` (`q` in URL) | contains over proposal number, title, sponsor name, lead unit name, PI name | existing semantics |
| Sponsor | `sponsor` | contains, name **or** code | new |
| Principal Investigator | `principalInvestigator` | contains | new |
| Lead Unit | `leadUnit` | contains, name **or** number | new |
| Paging | `page`, `size` | server-side, with total count | **new** (the old page showed the first 100 only) |

`GET /api/proposals/families` is **unchanged** (unpaged, capped at 200); Global
Search still calls its repository method directly. No status filter: the only
status this search returns is `proposal_sequence_status` (a version-lifecycle
flag), not a business status.

## Negotiations (`/negotiations` → `GET /api/negotiations`) — reference page

Backend unchanged by this branch.

| UI label | Param | Matching | Status |
|---|---|---|---|
| Search box | `query` (`q` in URL) | contains over id, document number, status, agreement type, association type, association id, negotiator, title, PI, sponsor name/code, lead unit name/number | existing |
| Principal Investigator (BU) | `principalInvestigator` | contains | existing |
| Sponsor | `sponsor` | contains, name or code | existing |
| Negotiator | `negotiator` | contains | existing |
| Agreement Type | `agreementType` | **exact** (now labelled "Exact match") | existing |
| Negotiation Status | `status` | **exact** | existing |
| Lead Unit | `leadUnit` | contains, name or number | existing |
| Negotiation Association Type | `associationType` | **exact** | existing — preserved |
| Negotiation Association ID | `associationId` | **exact** | existing — preserved |
| Negotiation Start / End Date from / to | `startDateFrom/To`, `endDateFrom/To` | inclusive dates | existing |

Attributes come from `archive.negotiation_search_attribute` (V080). Changed UI
behaviour: Clear All now keeps the text query (it used to clear it), and the
badge counts applied rather than draft filters.

## Subawards (`/subawards` → `GET /api/subawards`)

Grain: one row per `archive.subaward` **version** (unchanged). Stable order:
exact code first, then update time, sequence, `subaward_id`; unfiltered and
filter-only searches keep the primary-key order.

| UI label | Param | Matching | Column(s) | Status |
|---|---|---|---|---|
| Search box (incl. FRN) | `query` (`q` in URL) | contains over id, code, document number, title, status, organization, account, sponsors; **FRN** (9–10 digits) family-wide on `purchase_order_num`, `fsrs_subaward_number`, `subaward_amount.purchase_order_num` | `archive.subaward`, `subaward_amount` | existing — preserved (filters AND outside the parenthesised text/FRN predicate) |
| Status | `status` | exact, case-insensitive, with or without the Kuali ordinal ("Executed" = "07. Executed") | `status_description` | new |
| Sponsor | `sponsor` | contains; linked Award's sponsor **or** prime sponsor name (only sponsor columns archived on a Subaward) | `award_sponsor_name`, `award_prime_sponsor_name` | new |
| Organization ID | `organizationId` | exact, case-insensitive (subrecipient id; no name archived) | `organization_id` | new |
| Start Date from / to | `startDateFrom/To` | inclusive dates | `start_date` | new |
| End Date from / to | `endDateFrom/To` | inclusive dates | `end_date` | new |

## Global Search (`/search` → `GET /api/global-search`)

| UI label | Param | Matching | Status |
|---|---|---|---|
| Search box | `query` (`q` in URL), 2–200 chars | per-domain free text (each module's own search), ≤25 per domain, merged and ranked | existing |
| Record Type | `modules` (repeatable or comma-separated): `AWARD`, `PROPOSAL`, `NEGOTIATION`, `SUBAWARD` | unselected domains are not queried; semantic hits kept only for selected modules; unknown value → 400 | new |

Only Record Type is offered. Sponsor/status/PI/unit/dates mean different things
per module in Global Search results (e.g. `subtitle` is the sponsor for Awards
but the negotiator for Negotiations; PI is null for Negotiation/Subaward), so
they are deliberately not presented as cross-module filters.

## Archived File Finder (`/archived-files` → `GET /api/v1/attachments/search`) — still present

Requires the ArchiveAttachmentViewer group (unchanged). Identifier-driven, so it
has no free-text box; the shared panel opens by default. Backend unchanged.

| UI label | Param | Matching | Status |
|---|---|---|---|
| Record Type | `recordType` | `ALL` / `AWARD` / `PROPOSAL` / `NEGOTIATION` | existing |
| Award / Proposal / Negotiation Number (label follows type) | `recordNumber` | exact | existing |
| Workflow Document Number | `documentNumber` | exact | existing |
| Award / Proposal / Negotiation ID (hidden for All) | `recordId` | exact | existing |
| Attachment ID (hidden for All) | `attachmentId` | exact | existing |
| File ID (Award only) | `fileId` | exact | existing |
| Versions (hidden for Negotiation) | `versionFilter` | all / current / historical | existing |

At least one identifier is required before searching (existing rule).

## Shared UI behaviour (all seven pages)

One hook (`ui/src/hooks/useFilteredSearch.ts`) and one component
(`ui/src/components/common/search/FilteredSearchBar.tsx`, wrapping the shared
`FilterPanel`/`FilterToggleButton`/`FilterChips`) back every page:

- Draft vs applied: inputs edit a draft; nothing is requested until Enter or
  Apply Filters. Closing the panel neither applies nor discards the draft;
  unapplied edits are announced in the panel footer.
- Apply combines the text query and filters and returns to page 1. Removing a
  chip, Clear All and changing Sort also return to page 1.
- Clear All clears structured filters (draft and applied) and keeps the text.
- The badge counts APPLIED filters; chips show option labels for selects.
- URL: `?q=`, one key per active filter, `?page=` (omitted on page 1), extras
  such as `sort` (omitted at default). Values that could not come from the
  panel (unknown select option, malformed date) are dropped on read; select
  values match case-insensitively (older `recordType=award` links still work).
  Back/Forward re-syncs the inputs.
- Dates: From > To blocks Apply ("Must be on or after From."); equal dates are
  valid (inclusive); every date field says "Inclusive".
- Stale responses: each query is keyed on the full applied request and
  cancelled via AbortSignal (Global Search now passes one too).
- Accessibility: visible labels above every input (never placeholder-only,
  never truncated), native `<select>`s, helper/error text via
  aria-describedby, labelled `region` kept mounted so `aria-controls`
  resolves, `aria-expanded` + "Filters, N filters applied" on the toggle,
  chips named "Remove filter …" and removable with Delete/Backspace.

Behaviour changes from `origin/main` a reviewer should know about:

- Historical Awards no longer searches live as you type (350 ms debounce); it
  applies on Enter / Apply Filters like every other page. A non-numeric Award
  ID from a URL is never sent (the page says why).
- Negotiations' Clear All used to clear the text too; it now keeps it.
- Archived File Finder: Search/Clear are Apply Filters/Clear All buttons (were
  clickable chips); `recordType`/`versionFilter` are omitted from the URL at
  their defaults; identifiers hidden by a record-type change are dropped from
  the draft.
- Proposals page is now paged with a real total (was "first 100").
- Awards: the exact-document callout is hidden while structured filters apply.

## Review fixes made on this branch

- **Award page rows vs count.** Current-Award search now joins `award_hierarchy`
  through a `LATERAL … LIMIT 1` (active first, then newest), not a plain join.
  V049 does not make `award_number` unique there, so a family with two hierarchy
  rows appeared twice on the page while the count, which never joins it,
  counted it once.
- **Stable Award paging.** Current-Award search orders by `award_number,
  award_id`. Nothing constrains `is_primary_current` to one row per
  `award_number`, so `award_number` alone could order tied rows differently
  between pages. This order was inherited from `main`.
- **Archived File Finder.** The "enter at least one identifier" message clears
  as soon as the draft has an identifier again, or when the applied state changes.

The other checked joins cannot duplicate rows:
- `award_extension` is keyed on `award_id` (V046);
- PI name is resolved with `LATERAL … LIMIT 1`, and the PI filter uses `EXISTS`;
- amounts use `LATERAL … LIMIT 1`.

Every changed search builds its page and its count from the same WHERE
fragment (`AWARD_FAMILY_SEARCH_WHERE`, `AWARD_VERSION_SEARCH_WHERE`,
`FAMILY_PAGE_WHERE`, and Subaward `whereClause()`).

Every sort ends in a unique key:
- Award versions: `award_id`;
- Proposal families: `proposal_number`, one per family after ranking;
- Subawards: `subaward_id`.

Every filtered date column is `DATE` (`award_effective_date`, Subaward
`start_date`/`end_date`, Negotiation start/end), so an inclusive `<= To` has
no time-of-day cut-off.

## Validation status

- Unit/mock tests pass (Docker-based suites excluded). The counts at the time
  of the PR are in the PR description.
- **Real PostgreSQL execution of the new SQL: NOT RUN** (Docker not permitted).
  Prepared: `api/src/test/java/edu/bu/archive/adapter/out/persistence/StructuredSearchFiltersIntegrationTest.java`
  (`@Tag("database")`, `@Testcontainers(disabledWithoutDocker = true)`,
  skipped without Docker). It covers:
  - **Awards:**
    - a family matched only by Grant Number is counted;
    - status is exact and case-insensitive, never a substring match;
    - sponsor and lead unit match name or code;
    - the PI filter matches roles PI and MPI (Co-PI) only - not COI or KP - and free text still matches any role;
    - Project Start Date bounds are inclusive;
    - family filters apply to the current version only;
    - filters AND with the text query.
  - **Paging and counts:**
    - family pages are stable and disjoint;
    - version filters apply per version, with count parity;
    - a family with two hierarchy rows and two PIs appears once;
    - NULL attributes never match a filter but match "no filter";
    - a filter that matches nothing gives an empty page and a zero count.
  - **Subawards:**
    - status with or without the Kuali ordinal;
    - sponsor or prime sponsor;
    - organization matched exactly;
    - inclusive dates;
    - an FRN query is narrowed (never widened) by filters;
    - NULL attributes never match a filter.
  - **Proposals:** latest-version filtering, count parity and paging.

  Required, where Docker and Testcontainers are permitted:

  ```
  cd api && mvn test -Dtest='StructuredSearchFiltersIntegrationTest,SubawardFrnSearchIntegrationTest,NegotiationArchiveRepositorySchemaIntegrationTest,NegotiationWorkspaceAttributeContractTest'
  ```

  The last three are existing FRN and Negotiation suites, run for regression.
- Browser: checked against a local fixture harness with fake auth and a
  fixture API, not a real API or database. Desktop screenshots cover all seven
  pages.
- Mobile: the app shell's permanent navigation drawer does not collapse on
  phones. This predates the branch and is unchanged by it. At 390 px the drawer
  takes 250 px and leaves the content column 140 px wide, on every page.
  - Unmodified-app mobile screenshots show exactly that.
  - Separate "isolated layout" screenshots hide the drawer with injected CSS.
    They show only how the filter components lay out at phone width, not real
    mobile behaviour.
  - A collapsible navigation drawer is proposed as separate work, as is a fix
    for the Archived File Finder result cards, which overflow on narrow screens
    (in the unchanged `ResultSurface`).

## Backend gaps (not implemented, no schema/ETL change made)

- Award: prime sponsor, account number, activity/award/account type, other
  dates (execution, obligation start) — columns exist, no filter yet.
- Proposal: business `status_description`, proposal/activity type and dates
  exist on `proposal_version` but are not returned by this search; a status
  filter would need the result DTO to show the same status first.
- Negotiation: status/agreement/association type are exact-match text inputs;
  no endpoint supplies their value lists, so they cannot yet be selects.
- Subaward: no lead unit exists; `requisitioner_unit`, `site_investigator`,
  subaward type and closeout dates are archived but unfiltered.
- Archived File Finder: no document type, file name, date or availability
  filters; Subaward attachments are not searchable there.
- Global Search: no pagination; 25 per domain.
