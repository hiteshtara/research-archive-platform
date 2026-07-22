#!/bin/bash

set -euo pipefail

REGION="us-east-1"
ACCOUNT_ID="589744711110"

REPOSITORY="research-archive-platform-dev-api"
CLUSTER="research-archive-platform-dev-api"

echo "========================================"
echo "Research Archive API Deployment"
echo "========================================"

echo ""
echo "Locating ECS service..."

SERVICE=$(aws ecs list-services \
  --region "$REGION" \
  --cluster "$CLUSTER" \
  --query 'serviceArns[0]' \
  --output text | awk -F'/' '{print $NF}')

if [[ -z "$SERVICE" || "$SERVICE" == "None" ]]; then
    echo "ERROR: Could not determine ECS service."
    exit 1
fi

echo "Service: $SERVICE"

echo ""
echo "Building Spring Boot application..."

cd api

mvn clean package -DskipTests

echo ""
echo "Building Docker image..."

docker build \
  --platform linux/amd64 \
  -t ${REPOSITORY}:latest \
  .

echo ""
echo "Logging into Amazon ECR..."

aws ecr get-login-password \
  --region "$REGION" \
| docker login \
    --username AWS \
    --password-stdin \
    ${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com

echo ""
echo "Tagging Docker image..."

docker tag \
  ${REPOSITORY}:latest \
  ${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com/${REPOSITORY}:latest

echo ""
echo "Pushing image to ECR..."

docker push \
  ${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com/${REPOSITORY}:latest

echo ""
echo "Triggering ECS deployment..."

aws ecs update-service \
  --region "$REGION" \
  --cluster "$CLUSTER" \
  --service "$SERVICE" \
  --force-new-deployment

echo ""
echo "Waiting for deployment to complete..."

aws ecs wait services-stable \
  --region "$REGION" \
  --cluster "$CLUSTER" \
  --services "$SERVICE"

echo ""
echo "========================================"
echo "Deployment Complete!"
echo "========================================"

echo ""
echo "Current Service Status"

aws ecs describe-services \
  --region "$REGION" \
  --cluster "$CLUSTER" \
  --services "$SERVICE" \
  --query 'services[0].deployments[*].[status,runningCount,desiredCount]' \
  --output table

