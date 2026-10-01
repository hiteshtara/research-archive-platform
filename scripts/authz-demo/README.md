# Synthetic identity demo — BU federation not connected

A local, disposable demonstration of record-level authorization (Security Requirements IDs 1–7).

**Real parts:**
- the production identity links and grants;
- the scoped SQL;
- the request guards;
- the existing `ArchiveAttachmentViewer` gate.

**Replaced:** only sign-in, by a test-user selector. All data is invented.

> **Fixture-based authorization verified. Real BU federation and identity mapping: NOT VERIFIED.**
> The policy settings below are a *demo configuration of unapproved proposals* (P3 per-version, P6 exact lead unit, P4 PI/MPI/COI), not approved policy.

## Start, use, stop

Needs Docker, Java 21, Maven and Node 20.

```bash
scripts/authz-demo/start.sh     # DB on 127.0.0.1:55432, API on :8091, UI on :5199
open "http://localhost:5199/awards/search?q=SYNTHETIC"
scripts/authz-demo/stop.sh      # stops everything and deletes the disposable DB
scripts/authz-demo/test.sh      # automated checks (Testcontainers; demo need not be running)
```

Switch test users with the bar at the bottom of every page. Switching reloads the page, so nothing cached for one user is shown to another.

## Why the shortcut cannot reach a deployable build

| Layer | Guard |
|---|---|
| API | The selector lives in `api/src/authz-demo`, compiled only with `-Pauthz-demo` into `target-authz-demo/`. The default build, CI and Docker image never include it (`DemoClassesAbsentFromDefaultBuildTest`). |
| API runtime | The demo refuses to start unless security is in local permit-all mode, enforcement is on, and the database is a loopback database explicitly marked disposable. |
| UI | The sign-in shim and the selector load only in Vite mode `authz-demo`. Production builds drop them; a unit test and a CI grep of `dist/` check this. |

## Test users and expected access

Records (all `SYNTHETIC`):

| Record | Facts |
|---|---|
| A `990001-00001` | Unit SYN-U-100; Pat is PI on v1 and v2 |
| A child `990001-00002` | Unit SYN-U-100; a different PI |
| B `990002-00001` | Unit SYN-U-200; unrelated |
| C `990003-00001` | Pat is Co-PI (MPI) |
| D `990004-00001` | Pat is Co-Investigator |
| E `990005-00001` | Pat is Key Person only |
| F `990006-00001` | Carries IO SYN-IO-7001 |
| G `990007-00001` | Unit SYN-U-110, a sub-unit of SYN-U-100 |
| H `990008-00001` | Carries IO SYN-IO-7002 |
| Proposal 1 | Funds A; Pat not listed |
| Proposal 2 | Pat is PI |
| Proposal 3 | Unit SYN-U-100 |

| Test user | Sees (searches, counts, direct URLs) | Does not see | Attachments |
|---|---|---|---|
| Central | all 9 Award families and all Proposals; Negotiation and Subaward lists | — | yes |
| Department (SYN-U-100) | A, A child; Proposal 3 | B, G (sub-unit: exact match, P6), C–F, H | yes |
| Research Staff: Pat (PI) | A (both versions), C (Co-PI), D (COI); Proposal 2 | B, A child, E (KP excluded, P4); Proposal 1 (related to A, but no relationship grants access) | **no** (403, no attachment group) |
| Pat + attachment group | same as Pat | same as Pat | A's attachments: yes; B's: 404 |
| Other Authorized Viewer (SYN-IO-7001) | F | everything else | yes |
| Multiple grants (SYN-U-300 + SYN-IO-7002) | C, D, E (unit) + H (IO) | A, B, F, G | yes |
| Signed in, no grants | nothing ("access not provisioned") | everything | — |
| Unknown identity | nothing ("access not provisioned") | everything | — |
| Revoked mapping | nothing (access denied) | everything | — |
| Suspended | nothing (access denied, despite a Central grant) | everything | — |

Anything out of scope returns **404**, the same as a record that doesn't exist, so a direct URL never confirms that a hidden record exists (P7).

## Implemented and not yet implemented (non-Central users)

| Path | Status |
|---|---|
| Award search, Historical Award search, Proposal search: results **and** counts | **Scoped in SQL**, before count and paging |
| Global Search (Award and Proposal parts) | **Scoped**; other modules not searched |
| Dashboard counts | **Scoped** (Award/Proposal); other modules show 0 |
| Award and Proposal direct URLs, every section endpoint, `by-number` | **Checked before any query** |
| Award versions list, hierarchy, related Proposals; Proposal → Award lists | **Filtered** (no placeholders) |
| Related Negotiations and Subawards | **Omitted** (no non-Central rule yet, P8) |
| Attachments | Record check **and** `ArchiveAttachmentViewer` (kept, P5) |
| Award reports (PDF) | **Closed** for non-Central users (`NOT_AVAILABLE_UNDER_RECORD_AUTHORIZATION`) until every report section is reviewed |
| Negotiation, Subaward, IRB, Explorer, Document Explorer, File Finder, AI, legacy `/api/awards` | **Closed** for non-Central users (same code) |
| Semantic search | Not run for non-Central users |
| Real BU sign-in, enrollment, real IO field | **Not implemented**: awaiting BU IAM and decision D-A |

**Demo-data limitation:** a Central user's Award report PDF returns 404 on synthetic records. The report needs Time & Money and budget data the seed doesn't include; this is not an authorization result.
