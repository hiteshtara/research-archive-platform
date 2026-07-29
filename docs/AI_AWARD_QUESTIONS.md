# Award AI Questions — Phase 1

Phase 1 adds a read-only, Award-scoped endpoint:

```http
POST /api/ai/awards/{awardNumber}/questions
Content-Type: application/json

{"question":"What is the current status?"}
```

The feature is disabled by default and is independent of Award summary UI
visibility:

```text
APP_AI_ENABLED=true
APP_AI_QUESTIONS_ENABLED=true
APP_AI_QUESTION_PROMPT_VERSION=award-question-v1
VITE_AI_QUESTIONS_ENABLED=true
```

`APP_AI_ENABLED` is the backend master switch.
`APP_AI_QUESTIONS_ENABLED` enables the endpoint and question service.
`VITE_AI_QUESTIONS_ENABLED` includes the panel in the built frontend. The
frontend flag does not enable the backend.

Direct current-record fact questions are answered deterministically from
`AwardArchiveService`. Sequence comparisons and history routes first build
approved field-level diffs in application code. A configured `AiProvider`
may select only supplied support IDs and exact citations; the application
renders the final factual answer. The provider does not receive persistence
rows and cannot author authoritative Award facts.

Phase 1 supports current status, sponsor, lead unit, PI, sequence, title,
dates, anticipated amount, obligated amount, explicit or last-two sequence
comparisons, history summaries, and likely administrative-change selection.
Causal questions return the standard insufficient-archive-data answer
without invoking a provider. Question answers are not cached.

Every citation is checked against the exact physical Award record ID,
Award number, and sequence supplied as support. Metadata logs contain only
safe operational fields such as correlation ID, JWT subject, intent,
question length, provider/model, prompt version/hash, duration, token
counts, and success/failure category. Questions, prompts, Award values,
responses, JWTs, credentials, and authorization headers are not logged.
