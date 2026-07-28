A step-by-step operational guide.

Sections
Overview
Architecture
AI provider configuration
Environment variables
Secrets
Local Development
Required environment variables
Running locally
Testing OpenAI
Testing Stub provider
Build
mvn clean test
docker build ...
Push
docker push ...
ECS Deployment
aws ecs update-service ...
Verify
ECS task
Task definition
Image digest
Health
CloudWatch logs
Smoke Test
Award summary
Expected response
Expected logs
2. docs/AI_TROUBLESHOOTING.md

This is the important one.

Instead of a generic troubleshooting guide, make it a real incident postmortem.

Example:

Incident 1
Secrets Manager permission

Symptoms

ResourceInitializationError
AccessDeniedException

Root Cause

Execution role missing

secretsmanager:GetSecretValue

Fix

IAM policy.

Incident 2
Wrong Docker image

Symptoms

Application behaved like old code.

Root Cause

ECS deployed old image.

Diagnosis

Compare

Running task digest

vs

ECR digest

Lesson

Never trust ":latest".

Always verify digest.

Incident 3
Wrong Docker tag

Accidentally pushed

research-archive-platform-dev-apiatest

instead of

research-archive-platform-dev-api

Diagnosis

Repository didn't exist.

Lesson

Echo IMAGE_URI before pushing.

Incident 4
OpenAI provider unavailable

Symptoms

Configured AI provider unavailable

Root Cause

OpenAI provider bean not available because stale Docker image.

Diagnosis

CloudWatch

Task definition

Image digest

Incident 5
AI errors hidden

Symptoms

UI

AI summary temporarily unavailable

CloudWatch

Only

AI award summary request

Root Cause

Exception handler swallowed provider exception.

Fix

Log

correlationId
stack trace

before returning generic response.

Incident 6
Network verification

Verified

Internet Gateway
Public IP
SG outbound
OpenAI API key

Ruled out networking.

3. docs/ARCHITECTURE_AI.md

This should explain the entire design.

AwardAiController
        │
        ▼
AwardAiSummaryService
        │
        ▼
AiModelRouter
        │
        ▼
OpenAiProvider
        │
        ▼
OpenAI Responses API

Then explain

why Router exists
why Provider abstraction exists
adding Anthropic
adding Gemini
adding Azure OpenAI
adding local providers
Also create a deployment checklist
☐ Tests pass

☐ Build succeeds

☐ Push succeeds

☐ Verify ECR digest

☐ Force ECS deployment

☐ Verify PRIMARY revision

☐ Verify running digest

☐ Test Award Summary

☐ Check CloudWatch

☐ Verify no exceptions
One more document I'd add

I would also create:

docs/PROJECT_MEMORY_AI.md

This would capture why certain decisions were made, not just what was done. For example:

