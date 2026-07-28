# AI Architecture

## Purpose

The AI subsystem produces read-only summaries from archived research administration data while keeping the archived source records authoritative.

## Request flow

```text
AwardAiSummaryPanel
        |
        v
AwardAiController
        |
        v
AwardAiSummaryService
        |
        +--> AwardContextBuilder
        |
        v
AiModelRouter
        |
        v
AiProvider
   +----+----+
   |         |
   v         v
Stub       OpenAiProvider
              |
              v
      OpenAI Responses API
```

## Components

### AwardAiSummaryPanel

- initiates generation;
- displays the AI disclaimer;
- renders summary text and citations;
- displays generic failure messages and support references.

### AwardAiController

- exposes the authenticated HTTP endpoint;
- passes the request to the application service;
- does not contain provider-specific logic.

### AwardAiSummaryService

- builds the authoritative Award context;
- invokes the selected provider;
- validates the returned summary and citations;
- rejects unsupported or fabricated citations;
- canonicalizes accepted citations using source data.

### AwardContextBuilder

- gathers archived Award sequences and relevant fields;
- supplies only data that the model may use;
- marks archived data as untrusted input, not instructions.

### AiModelRouter

- selects the configured provider by provider name;
- keeps provider selection outside business logic;
- supports future provider implementations without changing controllers.

### AiProvider

Provider abstraction responsible for:

- provider name;
- model invocation;
- provider response conversion.

Current implementations:

- `StubAiProvider`
- `OpenAiProvider`

### OpenAiProvider

- uses the OpenAI Responses API;
- uses structured JSON output;
- sets `store=false`;
- sends only the supplied archive context;
- uses separate connect and request timeouts;
- sanitizes exceptions;
- parses citations into domain objects.

### AiExceptionHandler

- preserves the client-facing response contract;
- returns generic 503 errors;
- logs detailed server-side failures;
- includes correlation IDs for execution failures.

## Citation trust model

A model-generated citation is accepted only when it matches an authoritative record supplied in the request context.

Validation requires:

- supported record type;
- exact physical Award record ID;
- exact Award number;
- exact sequence number.

Harmless differences such as casing and surrounding whitespace may be normalized. The accepted result is rewritten using authoritative canonical values.

## Security and privacy controls

- archived values are treated as data, not instructions;
- API keys come from Secrets Manager;
- secrets are never returned to the client;
- full archive payloads are not written to error logs;
- detailed provider errors remain server-side;
- responses are not stored by OpenAI (`store=false`);
- the endpoint is protected by Cognito bearer-token authentication.

## Configuration

```text
APP_AI_ENABLED
APP_AI_PROVIDER
APP_AI_STUB_ENABLED
APP_AI_OPENAI_ENABLED
APP_AI_OPENAI_MODEL
APP_AI_OPENAI_TIMEOUT_SECONDS
APP_AI_OPENAI_CONNECT_TIMEOUT_SECONDS
OPENAI_API_KEY
```

## Adding another provider

1. Implement `AiProvider`.
2. Give it a stable provider name.
3. Add conditional bean configuration.
4. Add provider-specific properties.
5. Add parsing and error-handling tests.
6. Keep `AwardAiSummaryService` provider-independent.
7. Preserve the same citation and response validation boundary.

Potential future providers:

- Azure OpenAI;
- Anthropic;
- Gemini;
- a local model;
- a BU-hosted inference service.
