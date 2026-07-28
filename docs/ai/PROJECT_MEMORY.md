Phase 1 — AI Architecture
Why we chose the OpenAI Responses API
Provider abstraction
AiModelRouter
OpenAiProvider
AwardAiSummaryService
Structured JSON output
store=false
Citation handling
Configuration properties
Phase 2 — Local Development

Document:

Stub provider
Local OpenAI provider
Environment variables
Local testing
Unit tests
Integration tests
Phase 3 — AWS Deployment

Include every issue we hit:

Secrets Manager permissions

Symptoms

Root cause

Resolution

Commands used

ECS task failures

How we diagnosed

CloudWatch

Task definitions

Running tasks

Stopped tasks

Docker image mismatch

This deserves its own section because it is a common production issue.

Explain:

stale image
digest comparison
rebuilding
force deployment
Wrong Docker tag

The accidental:

research-archive-platform-dev-apiatest

Explain:

why it happened
how we found it
prevention
Networking verification

Document:

Internet Gateway
Public IP
Security groups
OpenAI connectivity
Runtime logging

Document why we changed

AiExceptionHandler

to log exceptions.

2. AI_RUNBOOK.md

Operational guide.

When someone says

Deploy AI

there should be one document.

Contents:

Build
Push
Verify digest
Deploy
Verify task
Verify health
Smoke test
Rollback
3. AI_ARCHITECTURE.md

Explain the design.

Diagram

UI
 │
 ▼
AwardAiController
 │
 ▼
AwardAiSummaryService
 │
 ▼
AiModelRouter
 │
 ▼
AiProvider
 │
 ├── OpenAI
 ├── Stub
 └── Future Providers

Explain why every layer exists.

4. AI_TROUBLESHOOTING.md

A searchable knowledge base.

Examples:

"Configured AI provider unavailable"

Root cause

Resolution

ECS won't start

Root cause

Resolution

Secrets Manager

Root cause

Resolution

Docker digest mismatch

How to verify

Runtime provider exception

How to diagnose

CloudWatch commands

Include every AWS CLI command we used.

5. AI_CHECKLIST.md

Simple checklist.

☐ mvn test

☐ docker build

☐ docker push

☐ verify digest

☐ ECS deploy

☐ verify task

☐ verify logs

☐ smoke test

☐ production validation
6. DECISIONS.md

Capture architectural decisions (ADR-style).

Examples:

ADR-001
Use OpenAI Responses API instead of Chat Completions.

ADR-002
Use provider abstraction.

ADR-003
Use structured JSON.

ADR-004
Keep generic 503 responses while logging detailed exceptions internally.