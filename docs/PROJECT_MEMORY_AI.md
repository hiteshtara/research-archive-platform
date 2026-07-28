# Project Memory: AI Award Summaries

## Goal

Add an AI-generated, read-only summary to archived Award histories in the Boston University Research Data Hub.

## Design decisions

### OpenAI Responses API

The implementation uses the Responses API rather than legacy chat-completions patterns.

Reasons:

- structured output support;
- a clear single-response abstraction;
- explicit `store=false`;
- compatibility with a provider abstraction;
- predictable JSON parsing.

### Provider abstraction

The application uses:

```text
AiModelRouter -> AiProvider
```

This separates business logic from model vendors and keeps future provider additions isolated.

### Structured output

The OpenAI provider requests a strict JSON response containing:

- summary text;
- citations;
- provider/model metadata as required by the application contract.

Structured output reduces parsing ambiguity but does not replace application-side validation.

### Citation validation

Model output is never trusted solely because it conforms to the JSON schema.

The application verifies every citation against the Award records supplied in the request context. Accepted citations are canonicalized using authoritative values.

### Generic client errors

The UI receives generic 503 messages so implementation details and provider responses are not exposed.

Server logs contain:

- correlation ID;
- sanitized exception message;
- stack trace.

### Timeouts

Connection establishment and full model execution use separate settings:

```text
connect timeout: 10 seconds
request timeout: 60 seconds
```

This avoids treating a normal model-generation duration as a connection failure.

## Implementation milestones

1. Added local AI feature flag and stub provider.
2. Added `OpenAiProvider`.
3. Added structured-output parsing and citations.
4. Verified local GPT-5 summaries.
5. Added ECS configuration and Secrets Manager injection.
6. Fixed execution-role permission for secret retrieval.
7. Rebuilt and deployed the correct Docker image.
8. Added correlation-ID exception logging.
9. Split connection and request timeouts.
10. Hardened citation validation.
11. Completed successful end-to-end Award AI summary generation.

## Deployment incident lessons

### Secrets access

The ECS execution role—not only the task role—must be able to retrieve startup secrets.

### Mutable image tags

`latest` is not proof that ECS has the intended code. Compare image digests.

### Shell variable mistakes

A missing colon produced the invalid repository name:

```text
research-archive-platform-dev-apiatest
```

Echo deployment variables before destructive or remote operations.

### Missing logs

Generic exception handlers must log the underlying sanitized throwable before returning a generic response.

### Browser errors can mislead

A browser CORS-looking error can accompany a backend failure. Test:

- route directly;
- authentication response;
- OPTIONS preflight;
- backend logs.

### Schema validation is not enough

A response can satisfy the JSON schema but still cite unsupported records. Domain validation remains mandatory.

## Successful final behavior

The Award AI panel now:

- generates a summary from archived Award sequences;
- displays an AI disclaimer;
- shows citations for the physical Award records;
- validates citation IDs, Award number, and sequence;
- displays a support reference on failure;
- preserves the archived records as the source of truth.

## Future opportunities

The same platform can support:

- Protocol summaries;
- Proposal summaries;
- Negotiation summaries;
- Subaward summaries;
- cross-record search explanations;
- timeline generation;
- document summaries.

Any future feature should reuse:

- provider abstraction;
- structured outputs;
- source-context validation;
- generic client errors;
- correlation-ID logging;
- digest-based deployment verification.
