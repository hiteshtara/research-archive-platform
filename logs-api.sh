#!/bin/bash

set -euo pipefail

REGION="us-east-1"
CLUSTER="research-archive-platform-dev-api"

SERVICE=$(aws ecs list-services \
  --region "$REGION" \
  --cluster "$CLUSTER" \
  --query 'serviceArns[0]' \
  --output text | awk -F'/' '{print $NF}')

if [[ -z "$SERVICE" || "$SERVICE" == "None" ]]; then
  echo "ERROR: Could not determine ECS service."
  exit 1
fi

TASK_DEFINITION=$(aws ecs describe-services \
  --region "$REGION" \
  --cluster "$CLUSTER" \
  --services "$SERVICE" \
  --query 'services[0].taskDefinition' \
  --output text)

LOG_GROUP=$(aws ecs describe-task-definition \
  --region "$REGION" \
  --task-definition "$TASK_DEFINITION" \
  --query 'taskDefinition.containerDefinitions[0].logConfiguration.options."awslogs-group"' \
  --output text)

if [[ -z "$LOG_GROUP" || "$LOG_GROUP" == "None" ]]; then
  echo "ERROR: Could not determine CloudWatch log group."
  exit 1
fi

echo "Service:   $SERVICE"
echo "Log group: $LOG_GROUP"
echo ""
echo "Following logs. Press Ctrl+C to stop."
echo ""

aws logs tail "$LOG_GROUP" \
  --region "$REGION" \
  --follow \
  --since 10m
