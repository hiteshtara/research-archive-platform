# ECS AI Deployment Runbook

This runbook documents the complete process for enabling and deploying the Award AI Summary feature in the ECS development environment.

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

Credentials are loaded from AWS Secrets Manager through `.envrc`.

Start tunnel:

```bash
./scripts/start-db-tunnel.sh
```

---

# Deploy API

```bash
./ops/deploy-api.sh
```

The script:

- Builds Spring Boot
- Builds Docker image
- Pushes to ECR
- Forces ECS deployment
- Waits for service stability

---

# ECS AI Environment Variables

Verify:

```bash
aws ecs describe-task-definition \
  --task-definition research-archive-platform-dev-api \
  --region us-east-1 \
  --query 'taskDefinition.containerDefinitions[0].environment[?starts_with(name, `APP_AI_`)]' \
  --output table
```

Expected:

```text
APP_AI_ENABLED=true
APP_AI_PROVIDER=stub
APP_AI_STUB_ENABLED=true
```

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

Patch the environment.

Register:

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
```

Wait:

```bash
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

Expected:

```text
PRIMARY
COMPLETED
running=1
pending=0
```

---

# Test endpoint

Without authentication:

```bash
curl -i -X POST \
'https://d1t1nk2y2enmtq.cloudfront.net/api/ai/awards/100004-00001/summary'
```

Expected:

```
401 Unauthorized
WWW-Authenticate: Bearer
```

This confirms the endpoint exists.

---

# Production verification

1. Hard refresh browser.
2. Open Award.
3. Click Generate AI Summary.

Expected:

- Summary
- Citations
- Correlation ID
- Provider
- Model

---

# Common failures

## 404

AI controller not deployed.

Check:

```
APP_AI_ENABLED
```

---

## 401

Expected from curl.

Browser should send Cognito token.

---

## 403

Usually:

- authorization
- masked backend exception

Check CloudWatch logs.

---

## Configured AI provider is unavailable

Verify:

```
APP_AI_PROVIDER=stub
APP_AI_STUB_ENABLED=true
```

---

# Lessons Learned

1. ECS task definitions are immutable.
2. Register a new revision before updating the service.
3. Wait for rollout to complete.
4. Verify environment variables after deployment.
5. Verify endpoint with curl.
6. Verify browser after rollout.
7. Never assume ECS picked up environment changes.