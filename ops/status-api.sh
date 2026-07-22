#!/bin/bash

set -e

REGION="us-east-1"
CLUSTER="research-archive-platform-dev-api"
SERVICE="research-archive-platform-dev-api"

echo ""
echo "======================================="
echo "API SERVICE STATUS"
echo "======================================="

aws ecs describe-services \
  --region "$REGION" \
  --cluster "$CLUSTER" \
  --services "$SERVICE" \
  --query 'services[0].{Desired:desiredCount,Running:runningCount,Pending:pendingCount}' \
  --output table

echo ""

echo "======================================="
echo "DEPLOYMENTS"
echo "======================================="

aws ecs describe-services \
  --region "$REGION" \
  --cluster "$CLUSTER" \
  --services "$SERVICE" \
  --query 'services[0].deployments[].{Status:status,Rollout:rolloutState,Running:runningCount,Pending:pendingCount}' \
  --output table

echo ""

echo "======================================="
echo "TARGET HEALTH"
echo "======================================="

TG=$(aws ecs describe-services \
  --region "$REGION" \
  --cluster "$CLUSTER" \
  --services "$SERVICE" \
  --query 'services[0].loadBalancers[0].targetGroupArn' \
  --output text)

aws elbv2 describe-target-health \
  --region "$REGION" \
  --target-group-arn "$TG" \
  --output table
