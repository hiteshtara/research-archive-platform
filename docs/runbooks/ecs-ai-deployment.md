# ECS AI Deployment Runbook

This runbook documents the complete process for enabling and deploying the Award AI feature (Summary and Questions) in the ECS development environment — from local development through a live OpenAI-backed production rollout.

---

# Architecture

```text
UI
  -> AwardAiController
  -> AwardAiSummaryService
  -> AiModelRouter
  -> AiProvider
  -> OpenAiProvider (or StubAiProvider)
  -> OpenAI Responses API
```

The API returns generic client-facing errors while detailed failures are logged with correlation IDs. See `docs/AI_ARCHITECTURE.md` for the full design.

---

# Local Development

## Enable AI in UI

`ui/.env.local`

```text
VITE_API_BASE_URL=http://localhost:8080
VITE_AI_ENABLED=true
```

Restart Vite after changing environment variables.

```bash
npm run dev
```

---

## Enable AI locally

Project `.envrc`

```bash
export APP_AI_ENABLED=true
export APP_AI_PROVIDER=stub
export APP_AI_STUB_ENABLED=true

# Local only
export APP_SECURITY_ENABLED=false
```

Reload:

```bash
direnv allow
direnv reload
```

---

## Local database

There is no supported direct Mac-to-dev-RDS connection
(`scripts/start-db-tunnel.sh` was removed 2026-08-13 — this project has
no EC2 bastion). Use `scripts/run-local.sh` for local Postgres, or an ECS
Fargate one-off task for dev RDS work — see `CLAUDE.md`'s "Authoritative
data location" section.

---

# Deploy API

```bash
export AWS_PROFILE=bu-nprd
aws sts get-caller-identity   # confirm account 770203350335 before proceeding
./ops/deploy-api.sh
```

The script resolves and prints the AWS account/region/ECR/ECS context
first, aborting before any mutating step if it doesn't match the
expected BU account - see its own header comment. It then:

- Builds Spring Boot
- Builds a Docker image tagged with a timestamp + Git SHA (immutable,
  not just `:latest`)
- Pushes to ECR and verifies the tag landed
- Registers a new ECS task definition revision using that tag
- Updates the service and waits for stability

Use `./ops/deploy-api.sh --check-only` to run every safety check with no
build/push/deploy.

## Manual deploy (for debugging the script above)

Never hardcode the account ID - resolve it from the active credentials
every time, and confirm it's the BU dev account (`770203350335`) before
doing anything else. An earlier version of this runbook hardcoded a
personal AWS account ID (`589744711110`) that happens to have
identically-named ECS/ECR resources to BU's account - every command
below would have silently targeted the wrong account under the wrong
profile with no error. See `ops/deploy-api.sh`'s header comment and
`docs/architecture/AWARD_IMPLEMENTATION_ROADMAP.md`'s "Eleventh
same-day follow-up" for the incident this was caught in.

```bash
export AWS_PROFILE=bu-nprd   # or whatever profile targets the BU account
aws sts get-caller-identity --query Account --output text
# must print 770203350335 - stop here if it doesn't

AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
AWS_REGION=us-east-1
REPOSITORY=research-archive-platform-dev-api
IMAGE_URI="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${REPOSITORY}:latest"
echo "$IMAGE_URI"   # confirm it ends with research-archive-platform-dev-api:latest

aws ecr get-login-password --region "$AWS_REGION" |
docker login --username AWS --password-stdin \
  "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"

docker build --no-cache --platform linux/amd64 -t "$IMAGE_URI" ./api
docker push "$IMAGE_URI"
```

Verify ECR digest before updating ECS:

```bash
aws ecr describe-images \
  --repository-name research-archive-platform-dev-api \
  --image-ids imageTag=latest \
  --region us-east-1 \
  --query 'imageDetails[0].{imageDigest:imageDigest,pushedAt:imagePushedAt}' \
  --output json
```

Never trust a mutable `:latest` tag as proof of what's running — always compare digests (see Lessons Learned).

---

# ECS AI Environment Variables

Verify the deployed task definition:

```bash
aws ecs describe-task-definition \
  --task-definition research-archive-platform-dev-api \
  --region us-east-1 \
  --query 'taskDefinition.containerDefinitions[0].environment[?starts_with(name, `APP_AI_`)]' \
  --output table
```

**Stub provider** (safe default, no external calls):

```text
APP_AI_ENABLED=true
APP_AI_PROVIDER=stub
APP_AI_STUB_ENABLED=true
```

**Live OpenAI provider:**

```text
APP_AI_ENABLED=true
APP_AI_PROVIDER=openai
APP_AI_OPENAI_ENABLED=true
APP_AI_STUB_ENABLED=false
APP_AI_OPENAI_MODEL=gpt-5-mini
APP_AI_OPENAI_TIMEOUT_SECONDS=60
APP_AI_OPENAI_CONNECT_TIMEOUT_SECONDS=10
```

Secret injection for the live provider (ECS secret reference, not a plain env var):

```text
OPENAI_API_KEY=<Secrets Manager JSON field apiKey>
```

```text
arn:aws:secretsmanager:<region>:<account>:secret:<secret-name>:apiKey::
```

Verify the key works before deploying, without printing it:

```bash
export OPENAI_API_KEY=$(
  aws secretsmanager get-secret-value \
    --secret-id research-archive-platform/dev/openai \
    --region us-east-1 \
    --query 'SecretString' \
    --output text | jq -r '.apiKey'
)
curl -sS https://api.openai.com/v1/models \
  -H "Authorization: Bearer $OPENAI_API_KEY" |
jq '{error: .error, model_count: (.data | length)}'
```

Expected: `error` is `null`.

---

# If AI variables are missing

Export current task definition:

```bash
aws ecs describe-task-definition \
  --task-definition research-archive-platform-dev-api \
  --region us-east-1 \
  --query taskDefinition \
  > taskdef.json
```

Patch the environment, then register the new revision:

```bash
aws ecs register-task-definition \
  --cli-input-json file://taskdef-updated.json \
  --region us-east-1
```

Update ECS:

```bash
aws ecs update-service \
  --cluster research-archive-platform-dev-api \
  --service research-archive-platform-dev-api \
  --task-definition research-archive-platform-dev-api \
  --force-new-deployment \
  --region us-east-1

aws ecs wait services-stable \
  --cluster research-archive-platform-dev-api \
  --services research-archive-platform-dev-api \
  --region us-east-1
```

---

# Verify rollout

```bash
aws ecs describe-services \
  --cluster research-archive-platform-dev-api \
  --services research-archive-platform-dev-api \
  --region us-east-1 \
  --query 'services[0].{taskDefinition:taskDefinition,running:runningCount,pending:pendingCount,deployments:deployments[*].{status:status,rolloutState:rolloutState}}' \
  --output json
```

Expected: `PRIMARY COMPLETED running=1 pending=0`.

Verify the running task's image digest matches the ECR digest recorded above:

```bash
TASK_ARN=$(aws ecs list-tasks \
  --cluster research-archive-platform-dev-api \
  --service-name research-archive-platform-dev-api \
  --desired-status RUNNING \
  --region us-east-1 \
  --query 'taskArns[0]' \
  --output text)

aws ecs describe-tasks \
  --cluster research-archive-platform-dev-api \
  --tasks "$TASK_ARN" \
  --region us-east-1 \
  --query 'tasks[0].{taskDefinition:taskDefinitionArn,status:lastStatus,imageDigest:containers[0].imageDigest}' \
  --output json
```

---

# Test endpoint

Without authentication:

```bash
curl -i -X POST \
'https://d1t1nk2y2enmtq.cloudfront.net/api/ai/awards/100004-00001/summary'
```

Expected: `401 Unauthorized`, `WWW-Authenticate: Bearer`. This confirms the endpoint exists.

---

# Production verification (smoke test)

1. Hard refresh browser and sign in.
2. Open an Award.
3. Click **Generate AI Summary**.

Expected:

- Summary displays with citations referencing supplied Award sequences
- Correlation ID, provider, and model are shown (dev-details panel)
- No browser CORS error, no 503 response

Then check CloudWatch for a clean request with no following error stack trace:

```bash
aws logs tail /ecs/research-archive-platform-dev-api \
  --region us-east-1 \
  --since 10m |
grep -Ei -A 40 -B 10 \
  'AI award summary request|AI summary execution failed|AI provider request failed|Timed out waiting for OpenAI|unsupported citation'
```

For the full pre-release checklist (code, config, Docker/ECR, ECS, end-to-end, operational readiness), see `docs/AI_CHECKLIST.md`.

---

# Rollback

Roll back to the last known-good task definition revision or immutable image digest. Do not rely on mutable `latest` tags for rollback decisions.

---

# Common failures

## 404

AI controller not deployed. Check `APP_AI_ENABLED`.

## 401

Expected from curl. Browser should send a Cognito token.

## 403

Usually an authorization issue or a masked backend exception. Check CloudWatch logs.

## Configured AI provider is unavailable

Verify `APP_AI_PROVIDER` and the corresponding `APP_AI_*_ENABLED` flag agree (e.g. `APP_AI_PROVIDER=stub` requires `APP_AI_STUB_ENABLED=true`; `APP_AI_PROVIDER=openai` requires `APP_AI_OPENAI_ENABLED=true`).

---

# Lessons Learned

1. ECS task definitions are immutable — register a new revision before updating the service.
2. Wait for rollout to complete; never assume ECS picked up environment changes.
3. Verify environment variables and image digest after every deployment, not just before.
4. Never trust a mutable `:latest` tag as proof of what's running — compare digests.
5. Verify the endpoint with curl, then verify in the browser after rollout.
6. See `docs/AI_TROUBLESHOOTING.md` for a full incident-by-incident postmortem of issues hit while building this out (Secrets Manager permissions, wrong image digest, a shell-variable typo that produced an invalid repository name, and swallowed exceptions hiding the real error).
