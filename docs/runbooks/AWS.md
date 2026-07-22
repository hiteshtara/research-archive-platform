# AWS Runbook

## Region

us-east-1

-------------------------------------------------------------------------------

## SSM Tunnel

aws ssm start-session \
  --region us-east-1 \
  --target i-02be522658e0f9676 \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters '{"host":["research-archive-platform-dev-postgres.cs3i6a24sthk.us-east-1.rds.amazonaws.com"],"portNumber":["5432"],"localPortNumber":["15432"]}'

-------------------------------------------------------------------------------

## PostgreSQL

POSTGRES_HOST=localhost

POSTGRES_PORT=15432

POSTGRES_DB=research_archive

POSTGRES_USER=<environment>

POSTGRES_PASSWORD=<environment>

-------------------------------------------------------------------------------

## ECS

Docker build

Push to ECR

Force ECS deployment

Verify API

-------------------------------------------------------------------------------

## Amplify

Verify frontend

-------------------------------------------------------------------------------

Never connect AWS directly to BU Oracle.

Only approved CSV exports are uploaded.

