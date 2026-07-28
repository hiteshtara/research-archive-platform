# AI Troubleshooting Guide

This guide documents the issues encountered while deploying the Award AI summary feature.

## 1. Secrets Manager permission failure

### Symptoms

```text
ResourceInitializationError
AccessDeniedException
not authorized to perform secretsmanager:GetSecretValue
```

### Cause

The ECS task execution role could not read the OpenAI secret.

### Fix

Grant `secretsmanager:GetSecretValue` to the execution role for the OpenAI secret ARN.

### Lesson

Secret retrieval happens before application startup. Diagnose task initialization errors before inspecting application logs.

---

## 2. Configured AI provider unavailable

### Symptoms

```text
AiProviderException: Configured AI provider is unavailable
```

### Cause

The ECS task used a stale Docker image that did not include the OpenAI provider implementation.

### Diagnosis

Compare:

- running ECS task image digest;
- ECR `latest` image digest;
- image push timestamp;
- Git commit used for the build.

### Lesson

Do not assume `latest` contains the current source. Verify by digest.

---

## 3. Incorrect Docker repository tag

### Symptoms

```text
repository research-archive-platform-dev-apiatest does not exist
```

### Cause

The image URI omitted the colon before `latest`.

Incorrect:

```text
research-archive-platform-dev-apiatest:latest
```

Correct:

```text
research-archive-platform-dev-api:latest
```

### Prevention

Always:

```bash
echo "$IMAGE_URI"
```

before building or pushing.

---

## 4. ECS task starts and exits with code 1

### Diagnosis

```bash
aws ecs describe-tasks \
  --cluster research-archive-platform-dev-api \
  --tasks <task-arn> \
  --region us-east-1 \
  --query 'tasks[0].{
    stoppedReason:stoppedReason,
    stopCode:stopCode,
    containerReason:containers[0].reason,
    exitCode:containers[0].exitCode
  }' \
  --output json
```

Then inspect the exact CloudWatch log stream for the task.

---

## 5. Generic 503 without useful logs

### Symptoms

The UI displays:

```text
AI summary is temporarily unavailable
```

CloudWatch only shows:

```text
AI award summary request
```

### Cause

`AiExceptionHandler` returned a generic 503 but did not log the underlying exception.

### Fix

Log the throwable with the correlation ID before returning the generic response.

### Safety rule

Do not log:

- API keys;
- authorization headers;
- complete Award context;
- raw secret values.

---

## 6. Browser reports CORS or routing failures

### Verification

Test the API route:

```bash
curl -i -X POST \
  "https://d1t1nk2y2enmtq.cloudfront.net/api/ai/awards/100004-00001/summary"
```

An unauthenticated `401` with `WWW-Authenticate: Bearer` proves the route reaches Spring Security.

Test preflight:

```bash
curl -i -X OPTIONS \
  "https://d1t1nk2y2enmtq.cloudfront.net/api/ai/awards/100004-00001/summary" \
  -H "Origin: https://main.d33qc0afy3ltcj.amplifyapp.com" \
  -H "Access-Control-Request-Method: POST" \
  -H "Access-Control-Request-Headers: authorization,content-type"
```

Expected:

```text
HTTP/2 200
Access-Control-Allow-Origin: https://main.d33qc0afy3ltcj.amplifyapp.com
Access-Control-Allow-Methods: GET,POST,PUT,PATCH,DELETE,OPTIONS
Access-Control-Allow-Headers: authorization, content-type
Access-Control-Allow-Credentials: true
```

### Lesson

A browser CORS message may be secondary to a backend error. Verify routing and preflight separately.

---

## 7. OpenAI request timeout

### Symptoms

```text
java.net.http.HttpTimeoutException: request timed out
```

### Cause

One 30-second timeout controlled both connection establishment and the full OpenAI Responses API request.

### Fix

Split the settings:

```text
APP_AI_OPENAI_TIMEOUT_SECONDS=60
APP_AI_OPENAI_CONNECT_TIMEOUT_SECONDS=10
```

Use:

- `HttpClient.Builder.connectTimeout(...)` for connection establishment;
- `HttpRequest.Builder.timeout(...)` for the full request.

### Logging

Convert timeout failures to:

```text
Timed out waiting for OpenAI Responses API
```

while keeping the client-facing 503 generic.

---

## 8. Unsupported citation after OpenAI succeeds

### Symptoms

```text
AiProviderException: AI provider returned an unsupported citation
```

### Cause

The response parser succeeded, but validation required exact lowercase and untrimmed textual values.

Examples of harmless model output differences:

```text
recordType = "Award"
recordId = " 1207589 "
awardNumber = "100004-00001 "
```

### Fix

Normalize harmless presentation differences:

- trim textual fields;
- compare `recordType` case-insensitively;
- continue requiring exact Award ID, Award number, and sequence;
- canonicalize accepted citations using authoritative supplied-context values;
- reject empty citation lists;
- constrain `recordType` in the structured-output schema to `"award"`.

### Safety boundary

Do not accept:

- fabricated Award IDs;
- mismatched Award numbers;
- mismatched sequence numbers;
- unsupported record types;
- citations to data not supplied in the request context.

---

## 9. Networking verification

The ECS task was verified to have:

- a public IP;
- a subnet route `0.0.0.0/0 -> Internet Gateway`;
- unrestricted outbound security-group traffic.

Commands:

```bash
aws ec2 describe-network-interfaces ...
aws ec2 describe-route-tables ...
aws ec2 describe-security-groups ...
```

### Lesson

Verify the network path once, then avoid repeatedly treating networking as the default explanation.

---

## 10. Fast diagnostic order

1. Check ECS deployment state.
2. Check stopped task reason and exit code.
3. Compare running task digest with ECR.
4. Verify execution-role secret permissions.
5. Verify API route and CORS.
6. Verify OpenAI key independently.
7. Search CloudWatch by correlation ID.
8. Inspect provider timeout or validation errors.
