# Follow-ups

Small, separable items found while doing something else. Each is written so it
can be picked up cold: what was observed, where, why it is not urgent, and what
"done" would look like. Nothing here is a blocker for the SEC-001 … SEC-009
DEV release.

Opened 2026-11-09 from the revision-85 smoke test
(`07 AI Sessions/DEV_RELEASE_SMOKE_TESTS_2026-11-09.md`).

---

## FU-001 — Proposal version rows do not open the historical version

**Observed** 2026-11-09, DEV, authenticated browser. Proposal
`/proposals/dashboard/1116893` → Versions lists three rows (v3 ACTIVE/Funded,
v2 and v1 ARCHIVED/Pending). The rows are inert: no link, no button, no click
handler, `cursor: auto`.

Award and Subaward both navigate from the equivalent table:

| Module | Version row | Opens |
|---|---|---|
| Award | `role="button"`, `cursor: pointer` | `/awards/<award_id>` of that sequence, with a **Historical version** badge |
| Subaward | `role="button"` | `/subawards/<subaward_id>`, header reads `Version 28 of 30` |
| Proposal | no handler at all | nothing |

**Not a regression.** `ui/src/components/proposal/ProposalVersionsSection.tsx`
contains no `onClick`, `navigate`, `Link` or `role=` — the table was built
read-only. Nothing in the revision-85 release touched it, and the deployed UI
was not rebuilt as part of that release.

**Why it is only a follow-up.** The information is all on screen (version,
sequence status, status, title, updated, workflow document). What is missing is
the ability to open the earlier version's full record, which the other two
modules offer.

**Open question for the business, before any code.** Is cross-module parity
actually wanted here? Proposal's grain differs from Award's — one result per
Proposal Number, with history behind the Versions tab — and
`docs/kuali-business-rules/` has caught at least one plausible-looking
version-scoping assumption that was backwards. Confirm the expectation before
mirroring Award's behaviour.

**Done would look like.** Either (a) Proposal version rows navigate to that
version's workspace, matching Award's badge and Subaward's `Version n of m`
header, with a presentation test pinning it; or (b) a recorded decision in
`docs/DECISIONS.md` that Proposal history is intentionally read-only, so the
asymmetry stops being re-discovered.

---

## FU-002 — SpringDoc API docs and Swagger UI are enabled by default in DEV

**Observed** 2026-11-09 in the DEV API startup log, task definition 85, as the
only two WARNs in an otherwise clean start:

```
WARN  o.s.core.events.SpringDocAppInitializer : SpringDoc /v3/api-docs endpoint is enabled
  by default. To disable it in production, set the property 'springdoc.api-docs.enabled=false'
WARN  o.s.core.events.SpringDocAppInitializer : SpringDoc /swagger-ui.html endpoint is enabled
  by default. To disable it in production, set the property 'springdoc.swagger-ui.enabled=false'
```

**Pre-existing and unchanged by the release.** The warnings are not caused by
the SEC work; they appear because no `springdoc.*` property is set in any
`application*.yml`.

**Why it is only a follow-up for DEV.** DEV is behind BU authentication and
these are the framework's own advisory warnings, not errors. The schema they
publish describes endpoints, not data.

**Why it should be settled before a Production equivalent.** The warning text
is explicit that the default is wrong for production. Deciding it once, in
configuration, is cheaper than discovering it during a production cutover —
and leaving two WARNs in every startup log trains operators to skim the log.

**Done would look like.** A deliberate decision recorded in
`docs/DECISIONS.md`, implemented as explicit `springdoc.api-docs.enabled` and
`springdoc.swagger-ui.enabled` properties per profile — whatever the values,
set rather than defaulted, so the log is clean and the intent is visible. Worth
checking at the same time whether the endpoints are reachable unauthenticated
(`/actuator` and `/actuator/env` answer 401; `/actuator/info` answers 200 with
an empty object).

---

## FU-003 — Nothing the browser can reach reports which build is running

**Observed** 2026-11-09 while correcting the Security Readiness panel.
`/actuator/info` returns `200 {}` — exposed, but no build-info or git-info is
generated into the jar. `/actuator` and `/actuator/env` answer 401.

**Consequence.** The deployed-revision panel on `/qa-status` has to be a
committed snapshot refreshed by hand, which is exactly how it came to report
revision 84 for hours after 85 was deployed. The mitigations in place are a
dated provenance label on the panel, a consistency test suite, and a step in
the release checklist (§5b) — all of them process or presentation, none of them
structural.

**Done would look like.** Spring Boot build-info (git commit, build time)
generated at package time and exposed on `/actuator/info`, with the readiness
panel reading the running revision at page load and falling back to the
snapshot when the call fails. That makes this class of staleness impossible
rather than merely tested for. It is an API change plus a UI change, so it is
not folded into a UI-only correction.
