# Local Development Setup

This document describes how to start a local Research Archive Platform
development session.

-------------------------------------------------------------------------------
1. Oracle
-------------------------------------------------------------------------------

Connect to the BU VPN.

Oracle is accessible only from the BU network.

Never connect AWS directly to Oracle.

-------------------------------------------------------------------------------
2. AWS SSM Tunnel
-------------------------------------------------------------------------------

Start the PostgreSQL tunnel.

aws ssm start-session \
  --region us-east-1 \
  --target i-02be522658e0f9676 \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters '{"host":["research-archive-platform-dev-postgres.cs3i6a24sthk.us-east-1.rds.amazonaws.com"],"portNumber":["5432"],"localPortNumber":["15432"]}'

Keep this terminal open.

-------------------------------------------------------------------------------
3. Environment
-------------------------------------------------------------------------------

export POSTGRES_HOST=localhost
export POSTGRES_PORT=15432
export POSTGRES_DB=research_archive

POSTGRES_USER and POSTGRES_PASSWORD should already exist in your shell.

Verify:

env | grep POSTGRES

-------------------------------------------------------------------------------
4. Verify Tunnel
-------------------------------------------------------------------------------

lsof -nP -iTCP:15432 -sTCP:LISTEN

-------------------------------------------------------------------------------
5. Backend
-------------------------------------------------------------------------------

cd api

mvn test

-------------------------------------------------------------------------------
6. Frontend
-------------------------------------------------------------------------------

cd ui

npm install

npm run dev

-------------------------------------------------------------------------------
7. ETL
-------------------------------------------------------------------------------

PYTHONPATH=etl

Run:

uv run --project etl python ...

-------------------------------------------------------------------------------
8. Proposal ETL
-------------------------------------------------------------------------------

Input

~/Downloads/proposal_versions.csv

~/Downloads/proposal_people.csv

~/Downloads/award_proposals.csv

Run

PYTHONPATH=etl uv run --project etl python \
    etl/load_proposals_from_csv.py

-------------------------------------------------------------------------------
9. Deployment
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
10. Finish
-------------------------------------------------------------------------------

git status

Working tree should be clean.

Commit and push before ending the session.

