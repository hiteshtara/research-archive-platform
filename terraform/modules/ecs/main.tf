data "aws_iam_policy_document" "ecs_task_assume_role" {
  statement {
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }

    actions = ["sts:AssumeRole"]
  }
}

resource "aws_ecs_cluster" "this" {
  name = "${var.project_name}-${var.environment}-etl"

  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_cloudwatch_log_group" "loader" {
  name              = "/ecs/${var.project_name}-${var.environment}-loader"
  retention_in_days = var.log_retention_days
}

resource "aws_security_group" "loader" {
  name        = "${var.project_name}-${var.environment}-loader-sg"
  description = "Security group for the ETL loader task"
  vpc_id      = var.vpc_id

  egress {
    description = "Allow outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.project_name}-${var.environment}-loader-sg"
  }
}

resource "aws_vpc_security_group_ingress_rule" "database_from_loader" {
  security_group_id            = var.database_security_group_id
  referenced_security_group_id = aws_security_group.loader.id

  from_port   = 5432
  to_port     = 5432
  ip_protocol = "tcp"

  description = "PostgreSQL access from ECS loader"
}

resource "aws_iam_role" "execution" {
  name               = "${var.project_name}-${var.environment}-loader-execution-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_task_assume_role.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "execution_secrets" {
  statement {
    effect = "Allow"

    actions = [
      "secretsmanager:GetSecretValue"
    ]

    resources = [
      var.database_secret_arn
    ]
  }
}

resource "aws_iam_role_policy" "execution_secrets" {
  name   = "${var.project_name}-${var.environment}-loader-secrets"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution_secrets.json
}

resource "aws_iam_role" "task" {
  name               = "${var.project_name}-${var.environment}-loader-task-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_task_assume_role.json
}

data "aws_iam_policy_document" "task_s3" {
  statement {
    effect = "Allow"

    actions = [
      "s3:ListBucket"
    ]

    resources = [
      var.data_bucket_arn
    ]
  }

  statement {
    effect = "Allow"

    actions = [
      "s3:GetObject",
      "s3:PutObject"
    ]

    resources = [
      "${var.data_bucket_arn}/*"
    ]
  }
}

resource "aws_iam_role_policy" "task_s3" {
  name   = "${var.project_name}-${var.environment}-loader-s3"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task_s3.json
}

# Award Attachment loader (etl/load_award_attachments.py --ecs): the
# application code runs as this task role, not the execution role - every
# Secrets Manager/S3/STS call it makes is authorized here. Granted on
# exactly the two named secrets, never secretsmanager:*.
data "aws_iam_policy_document" "task_secrets" {
  statement {
    effect = "Allow"

    actions = [
      "secretsmanager:GetSecretValue"
    ]

    resources = [
      var.database_secret_arn,
      var.oracle_secret_arn
    ]
  }
}

resource "aws_iam_role_policy" "task_secrets" {
  name   = "${var.project_name}-${var.environment}-loader-task-secrets"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task_secrets.json
}

# Award Attachment loader's documents-bucket access, scoped to its own
# key prefix (see DEFAULT_S3_KEY_PREFIX in load_award_attachments.py) -
# entirely separate from task_s3 above, which covers the unrelated,
# IRB-only data bucket. s3:GetObject/HeadObject are deliberately not
# granted - this loader never reads back an uploaded object today; add
# them, scoped the same way, only if a future verification step needs
# them.
data "aws_iam_policy_document" "task_documents_s3" {
  statement {
    sid    = "ListDocumentsBucketForAwardAttachments"
    effect = "Allow"

    actions = [
      "s3:ListBucket",
      "s3:ListBucketMultipartUploads"
    ]

    resources = [
      var.documents_bucket_arn
    ]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["award-files/by-file-id/*"]
    }
  }

  statement {
    sid    = "UploadAwardAttachmentObjects"
    effect = "Allow"

    actions = [
      "s3:PutObject",
      "s3:AbortMultipartUpload",
      "s3:ListMultipartUploadParts"
    ]

    resources = [
      "${var.documents_bucket_arn}/award-files/by-file-id/*"
    ]
  }
}

resource "aws_iam_role_policy" "task_documents_s3" {
  name   = "${var.project_name}-${var.environment}-loader-documents-s3"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task_documents_s3.json
}

resource "aws_ecs_task_definition" "loader" {
  family                   = "${var.project_name}-${var.environment}-loader"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([
    {
      name      = "loader"
      image     = var.loader_image
      essential = true

      environment = [
        {
          name  = "AWS_REGION"
          value = var.aws_region
        },
        {
          name  = "DATA_BUCKET_NAME"
          value = var.data_bucket_name
        },
        {
          name  = "IRB_S3_PREFIX"
          value = "landing/irb/"
        },
        # Award Attachment loader (--ecs). Deliberately a distinct
        # variable from DATA_BUCKET_NAME above - that one is the
        # unrelated, IRB-only data bucket; this one is the documents
        # bucket load_award_attachments.py uploads to. Secret
        # identifiers only (ARNs, not credentials) - the loader fetches
        # username/password/dsn itself, at runtime, from Secrets
        # Manager. POSTGRES_HOST/PORT/DB are intentionally not added
        # here as plain values - they are already provided as plain
        # environment variables via the `secrets` block below (ECS
        # resolves those from the same PostgreSQL secret before the
        # container starts), and duplicating the same variable name in
        # both `environment` and `secrets` is invalid/undefined.
        {
          name  = "AWARD_ATTACHMENT_BUCKET_NAME"
          value = var.documents_bucket_name
        },
        {
          name  = "POSTGRES_SECRET_ID"
          value = var.database_secret_arn
        },
        {
          name  = "ORACLE_SECRET_ID"
          value = var.oracle_secret_arn
        }
      ]

      secrets = [
        {
          name      = "POSTGRES_HOST"
          valueFrom = "${var.database_secret_arn}:host::"
        },
        {
          name      = "POSTGRES_PORT"
          valueFrom = "${var.database_secret_arn}:port::"
        },
        {
          name      = "POSTGRES_DB"
          valueFrom = "${var.database_secret_arn}:dbname::"
        },
        {
          name      = "POSTGRES_USER"
          valueFrom = "${var.database_secret_arn}:username::"
        },
        {
          name      = "POSTGRES_PASSWORD"
          valueFrom = "${var.database_secret_arn}:password::"
        }
      ]

      logConfiguration = {
        logDriver = "awslogs"

        options = {
          awslogs-group         = aws_cloudwatch_log_group.loader.name
          awslogs-region        = var.aws_region
          awslogs-stream-prefix = "loader"
        }
      }
    }
  ])
}
