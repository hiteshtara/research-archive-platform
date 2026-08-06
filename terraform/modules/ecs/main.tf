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

# BU Oracle staging RDS - see docs/ORACLE_STAGING_CONNECTIVITY.md. The
# description matches the rule BU's central IT already created manually
# on the Oracle security group exactly, for a clean terraform import
# with no plan diff.
resource "aws_vpc_security_group_ingress_rule" "oracle_from_loader" {
  count = var.oracle_security_group_id != null ? 1 : 0

  security_group_id            = var.oracle_security_group_id
  referenced_security_group_id = aws_security_group.loader.id

  from_port   = 1521
  to_port     = 1521
  ip_protocol = "tcp"

  description = "Research Archive Platform ECS loader"
}

resource "aws_iam_role" "execution" {
  name               = "${var.project_name}-${var.environment}-loader-execution-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_task_assume_role.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
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

# Award Attachment loader's documents-bucket access - entirely separate
# from task_s3 above, which covers the unrelated, IRB-only data bucket.
data "aws_iam_policy_document" "task_documents_s3" {
  # s3:ListBucket/s3:HeadBucket (this loader's bucket-exists check,
  # validate_bucket_exists -> boto3 head_bucket) and s3:GetBucketLocation
  # are unrestricted at the bucket level - all three were previously
  # conditioned on an s3:prefix matching award-files/by-file-id/*, but
  # head_bucket (and the region lookup GetBucketLocation performs) never
  # sends an s3:prefix in its request at all, so that condition silently
  # denied the loader's own startup bucket-existence check. Sid matches
  # the name this fix already shipped under, live. Neither action can
  # return object content or list arbitrary keys outside what
  # ListBucketMultipartUploads/UploadAwardAttachmentObjects below already
  # scope - only ListBucket's object-listing form takes a prefix, and
  # this loader never calls that form (it never lists the bucket's
  # contents, only checks it exists and streams multipart uploads to a
  # fixed key).
  statement {
    sid    = "ValidateDocumentsBucket"
    effect = "Allow"

    actions = [
      "s3:ListBucket",
      "s3:GetBucketLocation",
      "s3:HeadBucket"
    ]

    resources = [
      var.documents_bucket_arn
    ]
  }

  statement {
    sid    = "ListDocumentsBucketMultipartUploads"
    effect = "Allow"

    actions = [
      "s3:ListBucketMultipartUploads"
    ]

    resources = [
      var.documents_bucket_arn
    ]
  }

  # s3:GetObject is granted alongside the write actions below because
  # the loader's own end-to-end validation needed it in practice
  # (verifying a multipart upload's result) - despite this module's
  # earlier assumption that this loader would never read back an
  # uploaded object.
  statement {
    sid    = "UploadAwardAttachmentObjects"
    effect = "Allow"

    actions = [
      "s3:PutObject",
      "s3:GetObject",
      "s3:AbortMultipartUpload",
      "s3:ListMultipartUploadParts"
    ]

    resources = [
      "${var.documents_bucket_arn}/award-files/by-file-id/*"
    ]
  }

  # Proposal Attachments (ProposalAttachmentPlugin,
  # archive_etl.attachments.runner --module proposal) - its own prefix,
  # separate from Award's award-files/by-file-id/* above, matching
  # ProposalAttachmentPlugin.default_s3_prefix / s3_key() (see
  # etl/archive_etl/attachments/plugins/proposal.py).
  statement {
    sid    = "UploadProposalAttachmentObjects"
    effect = "Allow"

    actions = [
      "s3:PutObject",
      "s3:GetObject",
      "s3:AbortMultipartUpload",
      "s3:ListMultipartUploadParts"
    ]

    resources = [
      "${var.documents_bucket_arn}/proposal/*"
    ]
  }

  # Negotiation Activity Attachments (NegotiationAttachmentPlugin,
  # archive_etl.attachments.runner --module negotiation) - its own
  # prefix, mirroring UploadProposalAttachmentObjects above exactly.
  # NegotiationAttachmentPlugin has no s3_key() override, so it uses the
  # shared AttachmentFilePlugin base implementation
  # (etl/archive_etl/attachments/plugins/attachment_file.py:39):
  # "{prefix}/{record_id}/{attachment_id}/{filename}" -
  # scripts/run-negotiation-loader.sh passes --s3-prefix negotiations
  # (overriding the plugin's own test/negotiations default), so the real
  # key shape is negotiations/<negotiation_id>/<attachment_id>/<file>.
  statement {
    sid    = "UploadNegotiationAttachmentObjects"
    effect = "Allow"

    actions = [
      "s3:PutObject",
      "s3:GetObject",
      "s3:AbortMultipartUpload",
      "s3:ListMultipartUploadParts"
    ]

    resources = [
      "${var.documents_bucket_arn}/negotiations/*"
    ]
  }

  # Subaward Attachments (SubawardAttachmentPlugin,
  # archive_etl.attachments.runner --module subaward) - its own prefix,
  # mirroring UploadNegotiationAttachmentObjects above. SubawardAttachmentPlugin
  # has its own s3_key() override (etl/archive_etl/attachments/plugins/subaward.py),
  # but it produces the same shape as the shared base:
  # "{prefix}/{subaward_id}/{attachment_id}/{filename}" - unlike Negotiation/
  # Proposal, Subaward's default_s3_prefix is already "subawards" (not
  # test/subawards), and scripts/run-subaward-loader.sh passes
  # --s3-prefix subawards explicitly to match, so the real key shape is
  # subawards/<subaward_id>/<attachment_id>/<file>. SUBAWARD_ATTACHMENT_S3_BUCKET
  # (the plugin's bucket_environment_variable) is deliberately not added to
  # this task definition's environment below - run-subaward-loader.sh always
  # passes --s3-bucket explicitly, which archive_etl.attachments.runner
  # prefers over the env var, so the env var would never be read.
  statement {
    sid    = "UploadSubawardAttachmentObjects"
    effect = "Allow"

    actions = [
      "s3:PutObject",
      "s3:GetObject",
      "s3:AbortMultipartUpload",
      "s3:ListMultipartUploadParts"
    ]

    resources = [
      "${var.documents_bucket_arn}/subawards/*"
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
        # identifiers only (ARNs, not credentials) - the loader resolves
        # username/password/host/port/dbname itself, at runtime, from
        # Secrets Manager via POSTGRES_SECRET_ID/ORACLE_SECRET_ID.
        # Deliberately no `secrets` block on this container: ECS-native
        # secret injection would put POSTGRES_USER/PASSWORD/HOST/PORT/DB
        # into the container's plain environment before the loader's own
        # startup validation ever runs, which is exactly what the
        # hardened PostgreSQL secret resolution (etl/load_award_attachments.py)
        # rejects in --ecs mode.
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
