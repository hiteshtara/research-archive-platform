# AWS Runbook

## Region

us-east-1

-------------------------------------------------------------------------------
SSM Tunnel
-------------------------------------------------------------------------------

Start the PostgreSQL tunnel:

aws ssm start-session \
  --region us-east-1 \
  --target i-02be522658e0f9676 \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters '{"host":["research-archive-platform-dev-postgres.cs3i6a24sthk.us-east-1.rds.amazonaws.com"],"portNumber":["5432"],"localPortNumber":["15432"]}'

Keep this terminal open while running ETL or connecting to PostgreSQL.

-------------------------------------------------------------------------------
Environment
-------------------------------------------------------------------------------

export POSTGRES_HOST=localhost
export POSTGRES_PORT=15432
export POSTGRES_DB=research_archive

POSTGRES_USER and POSTGRES_PASSWORD come from your local environment.

Verify:

env | grep POSTGRES

-------------------------------------------------------------------------------
Verify Tunnel
-------------------------------------------------------------------------------

lsof -nP -iTCP:15432 -sTCP:LISTEN

-------------------------------------------------------------------------------
Run ETL
-------------------------------------------------------------------------------

PYTHONPATH=etl uv run --project etl python \
    etl/load_proposals_from_csv.py

-------------------------------------------------------------------------------
Backend
-------------------------------------------------------------------------------

cd api

mvn test

-------------------------------------------------------------------------------
Deployment
-------------------------------------------------------------------------------

Docker

↓

ECR

↓

ECS

↓

Verify API

↓

Verify React

-------------------------------------------------------------------------------
Important
-------------------------------------------------------------------------------

- BU Oracle is accessible only through the BU VPN/private network.
- Oracle exports are created locally.
- Only approved CSV exports are uploaded into AWS.
- Never connect AWS directly to BU Oracle.
