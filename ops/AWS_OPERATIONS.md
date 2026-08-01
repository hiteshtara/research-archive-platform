# Research Archive Platform - AWS Operations Manual

===============================================================================
AWS ACCOUNT
===============================================================================

Account ID

770203350335

Do not assume this value - always confirm before running any command in
this document:

aws sts get-caller-identity --query Account --output text

(This document previously listed 589744711110, a personal AWS account
that happens to have identically-named ECS/ECR/RDS-secret resources to
this BU account - every command below was written assuming the correct
BU account and will silently target the wrong account if run under the
wrong credentials. See docs/architecture/AWARD_IMPLEMENTATION_ROADMAP.md's
"Eleventh same-day follow-up" for the incident this was caught in.)

Region

us-east-1

===============================================================================
AMPLIFY
===============================================================================

Application

research-archive-platform

App ID

d33qc0afy3ltcj

Production Branch

main

UI

https://main.d33qc0afy3ltcj.amplifyapp.com

List Builds

aws amplify list-jobs \
  --region us-east-1 \
  --app-id d33qc0afy3ltcj \
  --branch-name main

List Branches

aws amplify list-branches \
  --region us-east-1 \
  --app-id d33qc0afy3ltcj

List Apps

aws amplify list-apps \
  --region us-east-1

Trigger Build

aws amplify start-job \
  --region us-east-1 \
  --app-id d33qc0afy3ltcj \
  --branch-name main \
  --job-type RELEASE

===============================================================================
ECR
===============================================================================

Repositories

research-archive-platform-dev-api

research-archive-platform-dev-loader

List Repositories

aws ecr describe-repositories \
  --region us-east-1 \
  --output table

Login

aws ecr get-login-password \
  --region us-east-1 |
docker login \
  --username AWS \
  --password-stdin \
770203350335.dkr.ecr.us-east-1.amazonaws.com

===============================================================================
ECS
===============================================================================

API Cluster

research-archive-platform-dev-api

Loader Cluster

research-archive-platform-dev-etl

List Clusters

aws ecs list-clusters \
  --region us-east-1 \
  --output table

List Services

aws ecs list-services \
  --region us-east-1 \
  --cluster research-archive-platform-dev-api \
  --output table

Service Health

aws ecs describe-services \
  --region us-east-1 \
  --cluster research-archive-platform-dev-api \
  --services research-archive-platform-dev-api \
  --query 'services[0].{Desired:desiredCount,Running:runningCount,Pending:pendingCount}' \
  --output table

Expected

Desired = 1

Running = 1

Pending = 0

Deployment Status

aws ecs describe-services \
  --region us-east-1 \
  --cluster research-archive-platform-dev-api \
  --services research-archive-platform-dev-api \
  --query 'services[0].deployments[].{Status:status,Rollout:rolloutState,Running:runningCount,Pending:pendingCount}' \
  --output table

Expected

PRIMARY

COMPLETED

Running = 1

Force Deployment

aws ecs update-service \
  --region us-east-1 \
  --cluster research-archive-platform-dev-api \
  --service research-archive-platform-dev-api \
  --force-new-deployment

Wait

aws ecs wait services-stable \
  --region us-east-1 \
  --cluster research-archive-platform-dev-api \
  --services research-archive-platform-dev-api

Recent Events

aws ecs describe-services \
  --region us-east-1 \
  --cluster research-archive-platform-dev-api \
  --services research-archive-platform-dev-api \
  --query 'services[0].events[0:10].[createdAt,message]' \
  --output table

===============================================================================
TARGET HEALTH
===============================================================================

TG=$(aws ecs describe-services \
  --region us-east-1 \
  --cluster research-archive-platform-dev-api \
  --services research-archive-platform-dev-api \
  --query 'services[0].loadBalancers[0].targetGroupArn' \
  --output text)

aws elbv2 describe-target-health \
  --region us-east-1 \
  --target-group-arn "$TG" \
  --output table

Expected

healthy

===============================================================================
API DEPLOYMENT
===============================================================================

Use ops/deploy-api.sh instead of typing the build/tag/push/deploy steps
by hand - it resolves the AWS account from the active credentials,
aborts before any mutating step if the account/region/resources aren't
what's expected, and registers an immutable (timestamp + Git SHA)
tagged image rather than only ever moving :latest. See the script's own
header comment for the one documented override (EXPECTED_ACCOUNT_ID).

Validate only, no deploy:

export AWS_PROFILE=bu-nprd
ops/deploy-api.sh --check-only

Deploy:

export AWS_PROFILE=bu-nprd
ops/deploy-api.sh

===============================================================================
API URL
===============================================================================

Do not hardcode this - the ALB DNS name is only correct for the account
it was provisioned in. Get it fresh:

cd terraform/environments/dev && terraform output -raw api_url

NOTE

Opening the API URL directly in a browser returns

401 Unauthorized

This is EXPECTED because the API requires a Cognito Bearer token.

Use the Amplify UI instead.

===============================================================================
DEPLOYMENT CHECKLIST
===============================================================================

✓ Git Push

✓ Amplify Build Success

✓ Docker Build

✓ Docker Push

✓ ECS Deployment

✓ ECS PRIMARY COMPLETED

✓ Running = 1

✓ Pending = 0

✓ Target Healthy

✓ Test Proposal Workspace

===============================================================================
HELPFUL SCRIPTS
===============================================================================

./deploy-api.sh

./logs-api.sh

./open-api.sh

