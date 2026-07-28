# AI Deployment Runbook

This runbook covers deployment of the Research Archive Platform AI summary capability to AWS ECS.

## Architecture

```text
UI
  -> AwardAiController
  -> AwardAiSummaryService
  -> AiModelRouter
  -> AiProvider
  -> OpenAiProvider
  -> OpenAI Responses API
```

The API returns generic client-facing errors while detailed failures are logged with correlation IDs.

## Required configuration

```text
APP_AI_ENABLED=true
APP_AI_PROVIDER=openai
APP_AI_OPENAI_ENABLED=true
APP_AI_STUB_ENABLED=false
APP_AI_OPENAI_MODEL=gpt-5
APP_AI_OPENAI_TIMEOUT_SECONDS=60
APP_AI_OPENAI_CONNECT_TIMEOUT_SECONDS=10
```

Secret injection:

```text
OPENAI_API_KEY=<Secrets Manager JSON field apiKey>
```

Recommended ECS secret reference:

```text
arn:aws:secretsmanager:<region>:<account>:secret:<secret-name>:apiKey::
```

## Pre-deployment checks

```bash
git status
git log -1 --oneline
mvn -f api/pom.xml test
```

Verify the OpenAI key without printing it:

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

## Build and push

```bash
AWS_ACCOUNT_ID=589744711110
AWS_REGION=us-east-1
REPOSITORY=research-archive-platform-dev-api
IMAGE_URI="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${REPOSITORY}:latest"

echo "$IMAGE_URI"
```

Confirm the URI ends with:

```text
research-archive-platform-dev-api:latest
```

Then:

```bash
aws ecr get-login-password --region "$AWS_REGION" |
docker login \
  --username AWS \
  --password-stdin \
  "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"

docker build \
  --no-cache \
  --platform linux/amd64 \
  -t "$IMAGE_URI" \
  ./api

docker push "$IMAGE_URI"
```

## Verify ECR digest

```bash
aws ecr describe-images \
  --repository-name research-archive-platform-dev-api \
  --image-ids imageTag=latest \
  --region us-east-1 \
  --query 'imageDetails[0].{imageDigest:imageDigest,pushedAt:imagePushedAt}' \
  --output json
```

Record the digest before updating ECS.

## Deploy to ECS

```bash
aws ecs update-service \
  --cluster research-archive-platform-dev-api \
  --service research-archive-platform-dev-api \
  --force-new-deployment \
  --region us-east-1
```

## Watch rollout

```bash
aws ecs describe-services \
  --cluster research-archive-platform-dev-api \
  --services research-archive-platform-dev-api \
  --region us-east-1 \
  --query 'services[0].deployments[*].{
    status:status,
    rolloutState:rolloutState,
    taskDefinition:taskDefinition,
    desired:desiredCount,
    running:runningCount,
    pending:pendingCount,
    failedTasks:failedTasks
  }' \
  --output table
```

Successful state:

```text
PRIMARY  COMPLETED  running=1  pending=0  failedTasks=0
```

## Verify running image digest

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
  --query 'tasks[0].{
    taskDefinition:taskDefinitionArn,
    status:lastStatus,
    desiredStatus:desiredStatus,
    imageDigest:containers[0].imageDigest,
    exitCode:containers[0].exitCode,
    stoppedReason:stoppedReason
  }' \
  --output json
```

The running digest must match the current ECR `latest` digest.

## Smoke test

1. Sign in to the development UI.
2. Open an Award history page.
3. Select **Generate AI Summary**.
4. Confirm:
   - the summary displays;
   - citations reference supplied Award sequences;
   - no browser CORS error appears;
   - no 503 response appears;
   - a support reference appears only on failure.

## CloudWatch verification

```bash
aws logs tail /ecs/research-archive-platform-dev-api \
  --region us-east-1 \
  --since 10m |
grep -Ei -A 40 -B 10 \
  'AI award summary request|AI summary execution failed|AI provider request failed|Timed out waiting for OpenAI|unsupported citation'
```

A successful request should log request metadata without a following error stack trace.

## Rollback

Roll back to the last known-good task definition revision or immutable image digest. Do not rely on mutable `latest` tags for rollback decisions.

## Release checklist

- [ ] Tests pass
- [ ] Docker image built from the intended commit
- [ ] Image URI echoed and verified
- [ ] Push completed successfully
- [ ] ECR digest recorded
- [ ] ECS deployment forced
- [ ] New task reaches `RUNNING`
- [ ] Running digest matches ECR
- [ ] Load balancer target is healthy
- [ ] Award AI summary succeeds
- [ ] Citations validate
- [ ] CloudWatch has no new AI errors
