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
APP_AI_PROMPT_VERSION=award-summary-v2
APP_AI_CACHE_ENABLED=false
OPENAI_API_KEY=<injected from AWS Secrets Manager>
```

`APP_AI_OPENAI_MODEL` is configurable and defaults to `gpt-5`.
`APP_AI_OPENAI_BASE_URL` optionally overrides the default
`https://api.openai.com/v1`, and `APP_AI_OPENAI_TIMEOUT_SECONDS` optionally
overrides the 60-second request timeout.
`APP_AI_OPENAI_CONNECT_TIMEOUT_SECONDS` optionally overrides the 10-second
connection-establishment timeout.
`APP_AI_PROMPT_VERSION` identifies the prompt contract and participates in
cache keys. `APP_AI_CACHE_ENABLED` optionally enables a bounded, process-local
cache; it defaults to `false`. `APP_AI_CACHE_MAX_ENTRIES` defaults to `250`.
Each ECS task has its own cache, and entries are discarded on restart.

The application also computes a lowercase hexadecimal SHA-256 hash from the
exact system prompt text placed in `AiRequest`. The prompt text is never
logged. The safe `promptHash` metadata value is logged beside
`promptVersion`, provider, and model, and it participates in cache keys.
Therefore an accidental prompt edit invalidates cached narrative even when
the configured prompt version was not updated.

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
- requests strict JSON Schema output for `overview`, `notableChanges`,
  `archiveAssessment`, and `citations`;
- constrains `recordType` to `award`;
- never logs prompts, archive context, credentials, authorization headers, or
  raw provider responses;
- returns sanitized failures for timeouts, HTTP errors, malformed JSON, and
  missing output.

The existing `AwardAiSummaryService` still validates each returned citation
against the exact physical Award record and sequence supplied to the provider.
It tolerates only case and surrounding-whitespace presentation differences,
then returns canonical citation values from the supplied archive context.

The model does not supply the `currentRecord` or `timeline` response sections.
The application builds those deterministic sections from the authoritative
Award family, current Award people, and current Award amounts. PI names and
amounts are not added to the provider context.

The optional cache value has a narrative-only type containing `overview`,
`notableChanges`, `archiveAssessment`, and already-validated citations. It
cannot contain current records, timeline records, PI names, dates, amounts,
statuses, sponsors, or lead units. Cached citations are validated against the
current supplied Award context again on every request. Deterministic sections
are rebuilt from archive services on both cache misses and cache hits.

## Successful API response

```json
{
  "overview": "Narrative based only on supplied archive records.",
  "currentRecord": {
    "awardId": 101,
    "awardNumber": "A-100",
    "sequenceNumber": 2,
    "title": "Archived award title",
    "status": "ACTIVE",
    "sponsor": "Sponsor",
    "leadUnit": "Unit",
    "principalInvestigators": ["Archive PI"],
    "beginDate": "2024-01-01",
    "closeoutDate": null,
    "anticipatedTotalAmount": 1000.00,
    "obligatedTotalAmount": 750.00
  },
  "timeline": [
    {
      "awardId": 101,
      "awardNumber": "A-100",
      "sequenceNumber": 2,
      "current": true,
      "primaryCurrent": true,
      "status": "ACTIVE",
      "awardSequenceStatus": "ACTIVE",
      "sponsor": "Sponsor",
      "leadUnit": "Unit",
      "beginDate": "2024-01-01",
      "closeoutDate": null
    }
  ],
  "notableChanges": ["Narrative change with validated support."],
  "archiveAssessment": "Assessment of the supplied archive history.",
  "citations": [
    {
      "recordType": "award",
      "recordId": "101",
      "awardNumber": "A-100",
      "sequenceNumber": 2
    }
  ],
  "provider": "openai",
  "model": "gpt-5",
  "correlationId": "11111111-1111-1111-1111-111111111111"
}
```

Operational logs contain metadata only: `durationMs`, `inputTokens`,
`outputTokens`, `totalTokens`, `cacheHit`, provider, model, prompt version,
prompt hash, sequence count, success/failure category, correlation ID, JWT
subject, domain, and Award number. They never contain prompts, complete
context, model responses, titles, credentials, or authorization headers.

These structured fields can later support average latency, average input and
output token use, failure rate, cache hit rate, and usage comparisons by model
and prompt version. This change does not add dashboards, Terraform,
Micrometer, or an external monitoring dependency.
