# AWS Runbook

Region

us-east-1

-------------------------------------------------------------------------------

RDS Tunnel

aws ssm start-session \
  --region us-east-1 \
  --target i-02be522658e0f9676 \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters '{"host":["research-archive-platform-dev-postgres.cs3i6a24sthk.us-east-1.rds.amazonaws.com"],"portNumber":["5432"],"localPortNumber":["15432"]}'

-------------------------------------------------------------------------------

Deploy

Docker

↓

ECR

↓

ECS

↓

Verify API

↓

Verify React

