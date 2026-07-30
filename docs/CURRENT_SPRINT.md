# Research Archive Platform
## Current Sprint

### Operational Archives

- Award Archive
- Proposal Archive
- Negotiation Archive
- Subaward Archive

### Protocol Archive — removed

Was in progress (Phase 1 Core complete, Phase 2 Personnel in progress) as a
second, independent human-subjects archive alongside legacy IRB. Removed in
full: API, UI, ETL loaders/Oracle SQL, and forward-only migration
`V032__drop_protocol_archive.sql` (drops `archive.protocol_version` and its
child tables, plus the `archive.v_protocol_latest`/`archive.v_protocol_family`
views). See `docs/DECISIONS.md` for the reversal and rationale. There is no
further Protocol Archive work planned.

### Legacy IRB

Preserved as the sole human-subjects/protocol domain — no longer considered
deprecated now that Protocol Archive was removed rather than reaching
feature parity. V004–V010 and the existing loader, API, views, routes, and
UI are unchanged.

### Future Modules

- Investigator Workspace
- Agreement Archive
