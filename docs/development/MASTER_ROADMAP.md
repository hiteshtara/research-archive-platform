# Research Archive Platform Master Roadmap

## Vision

Build the premier read-only enterprise archive for Boston University research administration with modern AI capabilities while maintaining strict security, auditability, and read-only guarantees.

---

# Current Status

| Phase | Status |
|---------|--------|
| Archive Platform | ✅ Complete |
| ETL Framework | ✅ Complete |
| Award Archive | ✅ Complete |
| Negotiations | ✅ Complete |
| Subawards | ✅ Complete |
| Proposal Archive | ✅ Complete |
| Protocol Archive | ✅ Complete |
| AI Foundation | ✅ Complete |
| Award AI UI | ✅ Complete |
| Live AI Provider | ⬜ Planned |
| Protocol AI | ⬜ Planned |
| Negotiation AI | ⬜ Planned |
| Document RAG | ⬜ Planned |
| Natural Language Search | ⬜ Planned |
| ETL Assistant | ⬜ Planned |
| Multi-Agent Framework | ⬜ Planned |

---

# Phase 1 — AI Foundation

Status:
✅ Completed

Major Deliverables

- Provider abstraction
- Stub provider
- Secure endpoint
- Citation validation
- Context builders
- Redaction
- Feature flags
- Metadata logging

Lessons Learned

- Keep providers completely isolated.
- Never expose database access.
- Validate every citation.
- Fail closed by default.

---

# Phase 2 — Award AI UI

Status:
✅ Completed

Deliverables

- Generate AI Summary button
- React Query mutation
- Technical details panel
- Correlation ID
- Feature flag
- Accessible UI
- Safe error handling

Lessons Learned

- User-triggered generation works better than automatic summaries.
- Correlation IDs simplify troubleshooting.
- Technical details belong behind an expandable section.

---

# Phase 3 — Generic Context Framework

Status:
⬜ Planned

Goal

Create reusable context builders.

Deliverables

- ArchiveContextBuilder
- AwardContextBuilder
- ProtocolContextBuilder
- NegotiationContextBuilder
- ProposalContextBuilder
- SubawardContextBuilder
- DocumentContextBuilder

---

# Phase 4 — Protocol AI

Status:
⬜ Planned

Deliverables

- Protocol Summary endpoint
- Protocol Summary UI
- Citation validation
- Tests

---

# Phase 5 — Negotiation AI

Status:
⬜ Planned

Deliverables

- Negotiation summaries
- Timeline summaries
- Status explanations

---

# Phase 6 — Document RAG

Status:
⬜ Planned

Deliverables

- Document chunking
- Embeddings
- pgvector
- Retrieval
- Citations

---

# Phase 7 — Natural Language Search

Status:
⬜ Planned

Deliverables

- Search specification
- Validation
- SQL generation
- Secure execution

---

# Phase 8 — ETL Assistant

Status:
⬜ Planned

Deliverables

- Load explanations
- Validation assistance
- Reconciliation summaries

---

# Phase 9 — Live AI Provider

Status:
⬜ Planned

Candidate Providers

- Kimi
- OpenAI
- Claude
- Gemini
- BU-hosted model

Requirements

- Security approval
- Secrets Manager
- Timeout policy
- Cost monitoring
- Provider metrics

---

# Phase 10 — Multi-Agent Framework

Status:
⬜ Planned

Tools

- searchAwards
- searchProtocols
- searchNegotiations
- searchDocuments
- explainLoad

Agents

- Archive Agent
- ETL Agent
- Documentation Agent
- Coding Agent

---

# Long-Term Vision

Build an enterprise AI assistant capable of answering research-administration questions across:

- Awards
- Protocols
- Negotiations
- Proposals
- Subawards
- Documents

while preserving:

- Read-only guarantees
- Security
- Auditability
- Citation validation
- Provider independence

---

# Definition of Done

A feature is complete only when:

- Code reviewed
- Tests passing
- Documentation updated
- Security reviewed
- Feature flags added
- No regressions
- Deployment verified
- Rollback documented

