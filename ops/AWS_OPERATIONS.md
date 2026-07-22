# Research Archive Platform - AWS Operations Manual

===============================================================================
AWS ACCOUNT
===============================================================================

Account ID

589744711110

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
589744711110.dkr.ecr.us-east-1.amazonaws.com

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

Build

cd api

mvn clean package -DskipTests

Docker

docker build \
  --platform linux/amd64 \
  -t research-archive-platform-dev-api:latest \
  .

Tag

docker tag \
research-archive-platform-dev-api:latest \
589744711110.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-api:latest

Push

docker push \
589744711110.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-api:latest

Deploy

aws ecs update-service \
  --region us-east-1 \
  --cluster research-archive-platform-dev-api \
  --service research-archive-platform-dev-api \
  --force-new-deployment

===============================================================================
API URL
===============================================================================

http://rap-dev-api-alb-551917956.us-east-1.elb.amazonaws.com

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

