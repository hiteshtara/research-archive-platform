# Award AI Summary UI

Phase 2 adds an optional, user-triggered AI summary panel to the Award
workspace. It calls the authenticated, read-only Phase 1 endpoint:

```text
POST /api/ai/awards/{awardNumber}/summary
```

The request has no body. The UI URL-encodes `awardNumber`, uses the existing
Cognito access token, and does not display raw prompts, context, backend error
bodies, or provider diagnostics. A summary is requested only after the user
selects **Generate AI Summary**.

## Feature flags

The frontend panel is excluded from the Award workspace unless the build-time
flag is explicitly enabled:

```text
VITE_AI_ENABLED=true
```

`VITE_AI_ENABLED` controls only UI visibility. The backend remains
independently disabled unless its runtime flags are also enabled.
`AI_ENABLED` controls backend endpoint availability, while
`AI_STUB_ENABLED` controls registration of the deterministic stub provider.
Local stub-provider development uses:

```text
AI_ENABLED=true
AI_STUB_ENABLED=true
AI_PROVIDER=stub
```

No frontend or backend AI flag should be enabled in production without the
corresponding security and operational approval. Production defaults remain
disabled.

## Error behavior

The panel gives safe, user-facing guidance for expired authentication, missing
Awards or an unavailable endpoint, temporary service failures, and network
failures. Backend error bodies and provider diagnostics are not rendered.
When the API returns a correlation ID, the panel displays it as a support
reference.

## Local workflow

1. Start the API with the three backend variables above.
2. Start or build the UI with `VITE_AI_ENABLED=true`.
3. Sign in through the existing Cognito flow.
4. Open an Award workspace and select **Generate AI Summary**.
5. Verify important details against the displayed archive citations. Use the
   correlation ID as the support reference when reporting a failure.

The current provider is deterministic and local to the API. This phase adds no
external model SDK, network call, archive write, or persistent AI audit record.
AI summary generation remains read-only.
