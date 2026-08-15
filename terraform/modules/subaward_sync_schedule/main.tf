# Resolves the loader task family's current latest ACTIVE revision ARN
# at plan/apply time - EventBridge Scheduler's EcsParameters.TaskDefinitionArn
# requires a real ARN (not a bare family name), and this project's loader
# images are normally released by scripts/run-*-loader.sh/ops/deploy-*.sh
# registering new revisions directly against ECS, outside Terraform. Using
# this data source (re-read on every apply) instead of
# module.loader_ecs.task_definition_arn means re-running `terraform apply`
# for this module re-points the schedule at whatever was most recently
# deployed, rather than silently keeping a stale revision pinned forever.
data "aws_ecs_task_definition" "loader_latest" {
  task_definition = var.task_definition_family
}

# --- EventBridge Scheduler ------------------------------------------------

data "aws_iam_policy_document" "scheduler_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["scheduler.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "scheduler" {
  name               = "${var.project_name}-${var.environment}-subaward-nightly-scheduler"
  assume_role_policy = data.aws_iam_policy_document.scheduler_assume_role.json
}

# Least privilege: RunTask only this task family (any revision, so a
# future manual redeploy doesn't require an IAM change), only on this
# specific cluster; PassRole only for the two roles this exact task
# definition already uses.
data "aws_iam_policy_document" "scheduler_run_task" {
  statement {
    effect    = "Allow"
    actions   = ["ecs:RunTask"]
    resources = ["arn:aws:ecs:${var.aws_region}:${var.account_id}:task-definition/${var.task_definition_family}:*"]

    condition {
      test     = "ArnEquals"
      variable = "ecs:cluster"
      values   = [var.ecs_cluster_arn]
    }
  }

  statement {
    effect    = "Allow"
    actions   = ["iam:PassRole"]
    resources = [var.execution_role_arn, var.task_role_arn]
  }
}

resource "aws_iam_role_policy" "scheduler_run_task" {
  name   = "run-subaward-nightly-task"
  role   = aws_iam_role.scheduler.id
  policy = data.aws_iam_policy_document.scheduler_run_task.json
}

resource "aws_scheduler_schedule" "subaward_nightly" {
  name       = "${var.project_name}-${var.environment}-subaward-nightly"
  group_name = "default"

  schedule_expression          = var.schedule_expression
  schedule_expression_timezone = var.schedule_timezone

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = var.ecs_cluster_arn
    role_arn = aws_iam_role.scheduler.arn

    ecs_parameters {
      task_definition_arn = data.aws_ecs_task_definition.loader_latest.arn
      launch_type         = "FARGATE"
      platform_version    = "LATEST"

      network_configuration {
        subnets          = var.private_subnet_ids
        security_groups  = [var.security_group_id]
        assign_public_ip = false
      }
    }

    input = jsonencode({
      containerOverrides = [
        {
          name    = var.container_name
          command = ["python3", "load_subawards_from_csv.py", "--ecs", "--sync-all"]
        }
      ]
    })

    # No retries: --sync-all is already safe under overlap (advisory
    # lock, see load_subawards_from_csv.py's SUBAWARD_SYNC_ADVISORY_LOCK_KEY),
    # but a Scheduler-level retry launching a second concurrent task is
    # unnecessary risk for no benefit - a failed run is caught by the
    # alarms below and re-attempted on the next nightly invocation.
    retry_policy {
      maximum_retry_attempts       = 0
      maximum_event_age_in_seconds = 3600
    }
  }
}

# --- Monitoring -------------------------------------------------------
#
# No existing SNS/CloudWatch-alarm notification pattern exists anywhere
# else in this project's Terraform (verified: no aws_sns_topic or
# aws_cloudwatch_metric_alarm resource anywhere under terraform/ before
# this module) - so a new topic is created here rather than reusing one.
# It intentionally has no subscription: an operator subscribes their own
# endpoint (email/Slack/etc.) out of band, since this module has no way
# to know the right destination. See
# docs/runbooks/SUBAWARD_NIGHTLY_SYNC.md for how to subscribe.

resource "aws_sns_topic" "subaward_sync_alerts" {
  name = "${var.project_name}-${var.environment}-subaward-sync-alerts"
}

# Counts load_subawards_from_csv.py's per-family failure log line
# ("Subaward family {code} failed to load: ...") - fires for any family
# failure, whether from the nightly --sync-all or a manual targeted load.
resource "aws_cloudwatch_log_metric_filter" "family_failures" {
  name           = "${var.project_name}-${var.environment}-subaward-sync-family-failures"
  log_group_name = var.log_group_name
  pattern        = "\"Subaward family\" \"failed to load\""

  metric_transformation {
    name          = "SubawardSyncFamilyFailures"
    namespace     = "ResearchArchivePlatform/SubawardSync"
    value         = "1"
    default_value = "0"
  }
}

resource "aws_cloudwatch_metric_alarm" "family_failures" {
  alarm_name          = "${var.project_name}-${var.environment}-subaward-sync-family-failures"
  alarm_description   = "One or more Subaward families failed to load (nightly --sync-all or a manual load) - see the loader CloudWatch logs for the failing SUBAWARD_CODE(s)."
  namespace           = "ResearchArchivePlatform/SubawardSync"
  metric_name         = "SubawardSyncFamilyFailures"
  statistic           = "Sum"
  period              = 86400
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.subaward_sync_alerts.arn]
  ok_actions          = [aws_sns_topic.subaward_sync_alerts.arn]
}

# Counts main()'s "--sync-all did not fully converge" SystemExit message
# (family failures and/or a post-sync reconciliation gap) - this is the
# task-level exit-nonzero signal, distinct from an individual family
# failure above (a --sync-all run can converge with 0 family failures
# but still fail this check if reconciliation finds a gap).
resource "aws_cloudwatch_log_metric_filter" "task_failures" {
  name           = "${var.project_name}-${var.environment}-subaward-sync-task-failures"
  log_group_name = var.log_group_name
  pattern        = "\"did not fully converge\""

  metric_transformation {
    name          = "SubawardSyncTaskFailures"
    namespace     = "ResearchArchivePlatform/SubawardSync"
    value         = "1"
    default_value = "0"
  }
}

resource "aws_cloudwatch_metric_alarm" "task_failures" {
  alarm_name          = "${var.project_name}-${var.environment}-subaward-sync-task-failures"
  alarm_description   = "--sync-all exited nonzero (family failure and/or unresolved Oracle/archive.subaward reconciliation gap) - see the loader CloudWatch logs for the run's reconciliation summary."
  namespace           = "ResearchArchivePlatform/SubawardSync"
  metric_name         = "SubawardSyncTaskFailures"
  statistic           = "Sum"
  period              = 86400
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.subaward_sync_alerts.arn]
  ok_actions          = [aws_sns_topic.subaward_sync_alerts.arn]
}

# Counts run_sync_all()'s "--sync-all: N Subaward families in Oracle"
# INFO line, logged only once --sync-all actually starts work (i.e. not
# when skipped because another sync already holds the advisory lock).
# Used purely for staleness detection: if this metric has zero data
# points for 2 days running, no --sync-all attempt happened at all
# (scheduler misconfiguration, ECS launch failure, etc.), which the two
# alarms above cannot detect since they require the loader to have
# actually run and logged something.
resource "aws_cloudwatch_log_metric_filter" "sync_attempts" {
  name           = "${var.project_name}-${var.environment}-subaward-sync-attempts"
  log_group_name = var.log_group_name
  pattern        = "\"--sync-all:\""

  metric_transformation {
    name          = "SubawardSyncAttempts"
    namespace     = "ResearchArchivePlatform/SubawardSync"
    value         = "1"
    default_value = "0"
  }
}

resource "aws_cloudwatch_metric_alarm" "no_recent_sync_attempt" {
  alarm_name          = "${var.project_name}-${var.environment}-subaward-sync-stale"
  alarm_description   = "No --sync-all attempt has been observed in the loader logs for 2 days - the nightly schedule may not be firing at all (check the EventBridge Scheduler's last invocation and the ECS task launch history, not just this alarm)."
  namespace           = "ResearchArchivePlatform/SubawardSync"
  metric_name         = "SubawardSyncAttempts"
  statistic           = "Sum"
  period              = 86400
  evaluation_periods  = 2
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.subaward_sync_alerts.arn]
  ok_actions          = [aws_sns_topic.subaward_sync_alerts.arn]
}
