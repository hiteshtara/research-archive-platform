# Local Development Setup

## 1. Connect to BU VPN

Oracle is accessible only through the BU VPN/private network.

Never connect AWS directly to Oracle.

-------------------------------------------------------------------------------

## 2. Start AWS SSM Tunnel

aws ssm start-session \
  --region us-east-1 \
  --target i-02be522658e0f9676 \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters '{"host":["research-archive-platform-dev-postgres.cs3i6a24sthk.us-east-1.rds.amazonaws.com"],"portNumber":["5432"],"localPortNumber":["15432"]}'

Leave this terminal running.

-------------------------------------------------------------------------------

## 3. Environment

export POSTGRES_HOST=localhost
export POSTGRES_PORT=15432
export POSTGRES_DB=research_archive

Verify

env | grep POSTGRES

-------------------------------------------------------------------------------

## 4. Verify Tunnel

lsof -nP -iTCP:15432 -sTCP:LISTEN

-------------------------------------------------------------------------------

## 5. Backend

cd api

mvn test

-------------------------------------------------------------------------------

## 6. Frontend

cd ui

npm install

npm run dev

-------------------------------------------------------------------------------

## 7. ETL

PYTHONPATH=etl

uv run --project etl python ...

-------------------------------------------------------------------------------

## 8. Finish

git status

Working tree should be clean.

