# OpenAI Provider for Award AI Summaries

The OpenAI adapter implements the existing provider-neutral `AiProvider`
boundary and calls the OpenAI Responses API. Award records continue to be
retrieved by the archive application and reduced to the approved
`AwardAiContext` allowlist before the provider is invoked. The provider has no
database access and cannot modify archive data.

## Runtime configuration

Enable the provider explicitly:

```text
APP_AI_ENABLED=true
APP_AI_PROVIDER=openai
APP_AI_OPENAI_ENABLED=true
APP_AI_OPENAI_MODEL=gpt-5
OPENAI_API_KEY=<injected from AWS Secrets Manager>
```

`APP_AI_OPENAI_MODEL` is configurable and defaults to `gpt-5`.
`APP_AI_OPENAI_BASE_URL` optionally overrides the default
`https://api.openai.com/v1`, and `APP_AI_OPENAI_TIMEOUT_SECONDS` optionally
overrides the 60-second request timeout.
`APP_AI_OPENAI_CONNECT_TIMEOUT_SECONDS` optionally overrides the 10-second
connection-establishment timeout.

The feature and OpenAI provider flags default to disabled. The deterministic
stub provider remains available through its separate flag and provider
selection.

## ECS secret injection

The ECS task definition must inject `OPENAI_API_KEY` from AWS Secrets Manager:

```text
Secret: research-archive-platform/dev/openai
JSON key: apiKey
Environment variable: OPENAI_API_KEY
```

Do not copy the secret value into source code, environment files, task
definition plaintext, tests, logs, Git history, or documentation. This phase
documents the required injection only; it does not modify Terraform or ECS
resources.

## Request and response safety

The adapter:

- sends only the already-approved `AwardAiContext`;
- treats archive text as untrusted data rather than instructions;
- disables OpenAI response storage with `store=false`;
- requests strict JSON Schema output for `summary` and `citations`;
- constrains `recordType` to `award`;
- never logs prompts, archive context, credentials, authorization headers, or
  raw provider responses;
- returns sanitized failures for timeouts, HTTP errors, malformed JSON, and
  missing output.

The existing `AwardAiSummaryService` still validates each returned citation
against the exact physical Award record and sequence supplied to the provider.
It tolerates only case and surrounding-whitespace presentation differences,
then returns canonical citation values from the supplied archive context.
