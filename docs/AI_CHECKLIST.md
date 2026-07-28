# AI Release Checklist

## Code

- [ ] Working tree reviewed
- [ ] `git diff --check` passes
- [ ] Unit and integration tests pass
- [ ] Generic API error contract is unchanged
- [ ] No secrets or full archive payloads are logged
- [ ] Citation validation tests cover invalid and fabricated values

## Configuration

- [ ] `APP_AI_ENABLED=true`
- [ ] `APP_AI_PROVIDER=openai`
- [ ] `APP_AI_OPENAI_ENABLED=true`
- [ ] `APP_AI_STUB_ENABLED=false`
- [ ] OpenAI model reviewed
- [ ] Request timeout reviewed
- [ ] Connect timeout reviewed
- [ ] Secrets Manager reference uses the `apiKey` JSON field
- [ ] ECS execution role can call `secretsmanager:GetSecretValue`

## Docker and ECR

- [ ] Image URI echoed and verified
- [ ] Correct repository name
- [ ] Correct `:latest` separator
- [ ] Image built from intended commit
- [ ] Image built for `linux/amd64`
- [ ] Push succeeded
- [ ] New ECR digest recorded

## ECS

- [ ] Service updated or force deployment initiated
- [ ] New task definition revision is primary
- [ ] New task reaches `RUNNING`
- [ ] Pending count returns to zero
- [ ] Failed task count remains zero
- [ ] Running image digest matches ECR
- [ ] Old task drains normally
- [ ] Target group reports healthy

## End-to-end test

- [ ] UI signs in through Cognito
- [ ] Award page loads
- [ ] Generate AI Summary succeeds
- [ ] Summary uses archived data only
- [ ] Disclaimer displays
- [ ] Citations display
- [ ] Citation Award IDs match source records
- [ ] Citation Award number matches
- [ ] Citation sequences match
- [ ] No browser CORS errors
- [ ] No 503 response
- [ ] No new CloudWatch AI error

## Operational readiness

- [ ] Correlation ID is visible on failures
- [ ] CloudWatch search command tested
- [ ] Rollback revision or digest recorded
- [ ] Documentation updated
- [ ] Change communicated to project stakeholders
