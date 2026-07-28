# AI Phase 1

Phase 1 provides a disabled-by-default, provider-neutral foundation for
generating a read-only summary of one Award history.

The model never receives database access. The application retrieves the
complete Award family by `awardNumber` through `AwardArchiveService` and builds
a bounded, allowlisted context. Account numbers, sponsor award numbers, people,
contacts, source users, documents, credentials, and database metadata are not
included.

The only provider is a deterministic stub. It makes no network calls and needs
no credentials.

For local verification only:

```text
AI_ENABLED=true
AI_STUB_ENABLED=true
AI_PROVIDER=stub
```

The endpoint remains protected by the existing Cognito JWT rules:

```text
POST /api/ai/awards/{awardNumber}/summary
```

Each request writes one metadata-only structured application log containing
the correlation ID, JWT subject, archive domain, Award number, provider, model,
elapsed time, and success or safe-failure status. Full prompts, archive
context, model responses, Award titles, JWTs, credentials, and sensitive
fields are not logged.

Do not enable a live provider until BU has approved its data handling,
retention, region, model, credentials, rate limits, and operational controls.
