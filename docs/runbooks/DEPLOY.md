# Deployment Runbook

Backend

cd api

mvn test

-------------------------------------------------------------------------------

Frontend

cd ui

npm run build

-------------------------------------------------------------------------------

Deployment Order

Docker

↓

ECR

↓

ECS

↓

Verification

-------------------------------------------------------------------------------

Deploy only after feature completion.

