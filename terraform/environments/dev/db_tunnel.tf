#
# Mac-based read-only PostgreSQL access over SSM, for GUI clients.
#
# Restores the workflow scripts/start-db-tunnel.sh was written for in
# August 2026 and never delivered: that script port-forwarded through an
# SSM-managed EC2 instance, and this project has never had one (see
# f7c8028, which deleted it precisely because it failed closed every run).
#
# This replaces the missing bastion with an EPHEMERAL Fargate task. The
# operator starts a task, forwards localhost:15432 to RDS through it, works
# in psql/DBeaver/DataGrip, then stops the task. Nothing stays running.
#
#   Mac :15432 -> SSM Session Manager -> Fargate task (this SG) -> RDS :5432
#
# Everything here is gated on var.manage_db_tunnel_access and defaults to
# OFF, so merging this file changes nothing until someone opts in.
#
# Deliberately NOT a bastion: no EC2, no host to patch, no standing compute.
# The only always-on resource is the ssmmessages interface endpoint.
#

# --- The SSM control plane, privately ------------------------------------
#
# ECS Exec needs the task to reach ssmmessages. This VPC has no NAT gateway
# and no public route for private subnets, so without this endpoint the task
# cannot reach SSM and exec fails with TargetNotConnectedException.
#
# ssmmessages is the ONLY endpoint ECS Exec requires. The `ssm` and
# `ec2messages` endpoints that general Session Manager guidance lists are
# for EC2 managed-instance registration, which Fargate does not do - its
# exec agent is bind-mounted by the Fargate platform, not registered as a
# managed node. Verified against the ECS developer guide's "ECS Exec and
# Amazon VPC" note, not inferred.
#
# Reuses the EXISTING vpce security group, which already allows 443 from the
# VPC CIDR for the logs/secretsmanager/ecr/sts endpoints. No new security
# group and no rule change on the shared one.
resource "aws_vpc_endpoint" "ssmmessages" {
  count = var.manage_db_tunnel_access ? 1 : 0

  vpc_id            = module.vpc.vpc_id
  service_name      = "com.amazonaws.${var.aws_region}.ssmmessages"
  vpc_endpoint_type = "Interface"

  # One AZ, not two. This is an on-demand operator path, not a production
  # dependency: a single-AZ outage costs an analyst their tunnel for the
  # duration, and halves the only recurring charge here. Add
  # module.vpc.private_subnet_ids[1] if that trade stops being acceptable.
  subnet_ids = [module.vpc.private_subnet_ids[0]]

  security_group_ids  = [module.vpc.vpc_endpoint_security_group_id]
  private_dns_enabled = true

  tags = {
    Name = "${var.project_name}-${var.environment}-ssmmessages-vpce"
  }
}

# --- A cluster of its own -------------------------------------------------
#
# Free, and it makes the operator IAM policy exact: ssm:StartSession and
# ecs:RunTask can be scoped to this cluster's ARN rather than to the ETL
# cluster, where the loader also runs. Without it, the narrowest honest
# scope for an operator would include the ETL tasks.
resource "aws_ecs_cluster" "db_tunnel" {
  count = var.manage_db_tunnel_access ? 1 : 0

  name = "${var.project_name}-${var.environment}-dbtunnel"

  tags = {
    Name = "${var.project_name}-${var.environment}-dbtunnel"
  }
}

# --- The task role, which is the blast radius -----------------------------
#
# CVE-2026-89049 (AWS bulletin 2026-107-AWS, 2026-09-10) is a server-side
# request forgery in SSM Agent's REMOTE-HOST PORT FORWARDING specifically -
# exactly the feature this file enables. A caller who can start a forwarding
# session can reach link-local addresses and read the credentials of the
# role the agent runs as. On Fargate that is this task role, via
# 169.254.170.2.
#
# So this role is built to be worthless if stolen: the four ssmmessages
# actions and nothing else. No Secrets Manager (the operator authenticates
# from their own machine, the task never holds a database credential), no
# S3, no Bedrock, no KMS.
#
# This is why the task does NOT reuse the loader task role, which carries
# Bedrock, two S3 policies and Secrets Manager access. Reusing it would have
# been one less resource and a far worse answer.
resource "aws_iam_role" "db_tunnel_task" {
  count = var.manage_db_tunnel_access ? 1 : 0

  name = "${var.project_name}-${var.environment}-dbtunnel-task-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
      Condition = {
        ArnLike      = { "aws:SourceArn" = "arn:aws:ecs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:*" }
        StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id }
      }
    }]
  })

  tags = {
    Name = "${var.project_name}-${var.environment}-dbtunnel-task-role"
  }
}

resource "aws_iam_role_policy" "db_tunnel_task_ssm" {
  count = var.manage_db_tunnel_access ? 1 : 0

  name = "${var.project_name}-${var.environment}-dbtunnel-ssmmessages"
  role = aws_iam_role.db_tunnel_task[0].id

  # The exact four actions the ECS developer guide requires, and no more.
  # ssmmessages has no resource-level permissions, so "*" is the only valid
  # Resource - the containment is that these four actions can do nothing but
  # open an SSM channel.
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "ssmmessages:CreateControlChannel",
        "ssmmessages:CreateDataChannel",
        "ssmmessages:OpenControlChannel",
        "ssmmessages:OpenDataChannel",
      ]
      Resource = "*"
    }]
  })
}

# --- Network position: nothing in, three things out -----------------------
resource "aws_security_group" "db_tunnel" {
  count = var.manage_db_tunnel_access ? 1 : 0

  name        = "${var.project_name}-${var.environment}-dbtunnel-sg"
  description = "Ephemeral Fargate task used only as an SSM port-forwarding hop to dev RDS"
  vpc_id      = module.vpc.vpc_id

  # Zero inbound, actively maintained. The task is reached through SSM's
  # outbound channel, never by anything connecting to it.
  ingress = []

  # Egress IS declared inline here, unlike the cloudshell_analyst SG above
  # which uses separate aws_vpc_security_group_egress_rule resources. That
  # SG had its AWS-default allow-all-outbound rule revoked by an earlier
  # apply and cannot re-declare `egress = []` without fighting its own
  # separate rule (see the comment there). This SG is new, so its default
  # allow-all-outbound must be revoked on creation, and declaring every rule
  # inline does that AND defines them - with no second resource to conflict
  # with. Do not split these out later without re-reading that comment.
  egress {
    description     = "PostgreSQL to dev RDS - the only reason this task exists"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [module.rds.database_security_group_id]
  }

  egress {
    description = "HTTPS to VPC interface endpoints (ssmmessages, ecr.api, ecr.dkr, logs)"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = [module.vpc.vpc_cidr]
  }

  egress {
    description     = "HTTPS to S3 via the gateway endpoint, for ECR image layers"
    from_port       = 443
    to_port         = 443
    protocol        = "tcp"
    prefix_list_ids = [data.aws_prefix_list.s3.id]
  }

  tags = {
    Name = "${var.project_name}-${var.environment}-dbtunnel-sg"
  }
}

data "aws_prefix_list" "s3" {
  name = "com.amazonaws.${var.aws_region}.s3"
}

# The paired rule on the database side. Source is a security group, never a
# CIDR: RDS stays private, is never publicly accessible, and 5432 is never
# open to the internet.
resource "aws_vpc_security_group_ingress_rule" "database_from_db_tunnel" {
  count = var.manage_db_tunnel_access ? 1 : 0

  security_group_id            = module.rds.database_security_group_id
  referenced_security_group_id = aws_security_group.db_tunnel[0].id

  from_port   = 5432
  to_port     = 5432
  ip_protocol = "tcp"

  description = "PostgreSQL from an ephemeral SSM tunnel task - see docs/runbooks/SSM_DB_TUNNEL.md"
}

# --- The task definition --------------------------------------------------
#
# Reuses the loader IMAGE deliberately. It is already in this account's ECR
# and reachable over the existing ecr/s3 endpoints; a public image
# (amazonlinux, alpine) would need egress to public ECR, which this VPC has
# no route for. The container runs `sleep` and nothing else - the ETL code
# in the image is never invoked, and the task holds no credential that could
# invoke it against anything.
#
# The image is read from the LIVE loader task definition rather than
# composed from the loader ECR repository URL plus var.loader_image_tag.
# That expression resolves to the "latest" tag, because terraform.tfvars
# does not set loader_image_tag - and "latest" DOES NOT EXIST in this
# repository; the only tagged image is 20260923T163946Z-443e507. The plan
# would still have succeeded and the first run-task would have failed with
# CannotPullContainerError. Reading the live definition is self-maintaining
# and can only ever name an image that is actually deployed. Mirrors the
# api_live pattern in main.tf.
#
# Only consulted when var.db_tunnel_image is null. Pinning the image skips
# these reads entirely, which is the point: the data source is what couples
# this task definition to the loader's deploy cadence.
data "aws_ecs_task_definition" "loader_live" {
  count = var.manage_db_tunnel_access && var.db_tunnel_image == null ? 1 : 0

  task_definition = module.loader_ecs.task_definition_family
}

data "aws_ecs_container_definition" "loader_live" {
  count = var.manage_db_tunnel_access && var.db_tunnel_image == null ? 1 : 0

  task_definition = data.aws_ecs_task_definition.loader_live[0].id
  container_name  = "loader"
}

resource "aws_ecs_task_definition" "db_tunnel" {
  count = var.manage_db_tunnel_access ? 1 : 0

  family                   = "${var.project_name}-${var.environment}-dbtunnel"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"

  # The floor Fargate offers. This process sleeps; the forwarded bytes are
  # handled by the platform's exec agent, not by the container.
  cpu    = "256"
  memory = "512"

  task_role_arn      = aws_iam_role.db_tunnel_task[0].arn
  execution_role_arn = module.loader_ecs.execution_role_arn

  container_definitions = jsonencode([{
    name = "tunnel"
    # Pinned when var.db_tunnel_image is set; otherwise whatever the loader
    # is currently running. See that variable for why pinning is preferred.
    image     = var.db_tunnel_image != null ? var.db_tunnel_image : data.aws_ecs_container_definition.loader_live[0].image
    essential = true

    # A hard ceiling on a forgotten task, independent of the Mac. If the
    # laptop sleeps, the shell is killed, or the stop call never happens,
    # the task exits on its own. The start script also traps and stops it;
    # this is the backstop for when the script never gets to run its trap.
    command = ["sleep", tostring(var.db_tunnel_max_session_seconds)]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = "/ecs/${var.project_name}-${var.environment}-dbtunnel"
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "tunnel"
      }
    }
  }])

  tags = {
    Name = "${var.project_name}-${var.environment}-dbtunnel"
  }
}

resource "aws_cloudwatch_log_group" "db_tunnel" {
  count = var.manage_db_tunnel_access ? 1 : 0

  name              = "/ecs/${var.project_name}-${var.environment}-dbtunnel"
  retention_in_days = 30

  tags = {
    Name = "${var.project_name}-${var.environment}-dbtunnel"
  }
}

# --- What a named operator may do -----------------------------------------
#
# Attach to the specific humans who need GUI access. Not attached to anything
# by this file - creating a policy grants nobody anything until someone
# attaches it, which is a deliberate second, human step.
#
# This policy is also the CVE-2026-89049 mitigation AWS recommends: "scoping
# ssm:StartSession IAM permissions and SSM document permissions so that
# untrusted principals cannot start remote-host port-forwarding sessions."
locals {
  # Constructed rather than referenced as aws_ecs_cluster.db_tunnel[0].arn,
  # so the operator policy below renders fully in `terraform plan`. Taking
  # the reference left every statement "known after apply", which meant the
  # single most security-sensitive document in this file could not be read
  # during review. The ARN format is fixed, and depends_on preserves the
  # ordering the reference used to imply.
  db_tunnel_cluster_name = "${var.project_name}-${var.environment}-dbtunnel"
  db_tunnel_cluster_arn  = "arn:aws:ecs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:cluster/${var.project_name}-${var.environment}-dbtunnel"

  # Same reasoning as the cluster ARN: referencing the role resource made
  # the whole policy unreadable in a plan.
  db_tunnel_task_role_arn = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/${var.project_name}-${var.environment}-dbtunnel-task-role"
}

resource "aws_iam_policy" "db_tunnel_operator" {
  count = var.manage_db_tunnel_access ? 1 : 0

  depends_on = [aws_ecs_cluster.db_tunnel, aws_iam_role.db_tunnel_task]

  name        = "${var.project_name}-${var.environment}-dbtunnel-operator"
  description = "Start, use and stop an ephemeral SSM port-forwarding task to dev RDS. Attach to named operators only."

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "RunTheTunnelTaskOnly"
        Effect   = "Allow"
        Action   = "ecs:RunTask"
        Resource = "arn:aws:ecs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:task-definition/${var.project_name}-${var.environment}-dbtunnel:*"
        Condition = {
          ArnEquals = { "ecs:cluster" = local.db_tunnel_cluster_arn }
        }
      },
      {
        Sid      = "InspectAndStopOwnTasks"
        Effect   = "Allow"
        Action   = ["ecs:DescribeTasks", "ecs:StopTask", "ecs:ListTasks"]
        Resource = "*"
        Condition = {
          ArnEquals = { "ecs:cluster" = local.db_tunnel_cluster_arn }
        }
      },
      {
        # Without this, RunTask is refused. Scoped to the two roles this task
        # uses and to ECS alone, so it cannot be used to pass a richer role.
        Sid    = "PassOnlyTheTunnelRoles"
        Effect = "Allow"
        Action = "iam:PassRole"
        Resource = [
          local.db_tunnel_task_role_arn,
          module.loader_ecs.execution_role_arn,
        ]
        Condition = {
          StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" }
        }
      },
      {
        # The narrow part. Port forwarding is permitted ONLY with the
        # remote-host document and ONLY against tasks in the tunnel cluster.
        # IAM cannot condition on the document's `host` parameter, so the
        # destination is constrained by the network instead: the task's
        # security group can reach 5432 on the RDS security group and
        # nothing else.
        Sid    = "StartPortForwardingToTunnelTasksOnly"
        Effect = "Allow"
        Action = "ssm:StartSession"
        Resource = [
          "arn:aws:ecs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:task/${var.project_name}-${var.environment}-dbtunnel/*",
          "arn:aws:ssm:${var.aws_region}::document/AWS-StartPortForwardingSessionToRemoteHost",
        ]
      },
      {
        # aws:userid, NOT aws:username. AWS states plainly that the
        # aws:username form "doesn't work for accounts that grant access to
        # AWS using federated IDs" - and every principal here is SAML
        # federated (Shibboleth-InfraMgt/<user>@bu.edu). With aws:username
        # these two actions would simply never match, and an operator could
        # not end their own session; the draft had exactly that bug.
        Sid      = "EndOwnSessionsOnly"
        Effect   = "Allow"
        Action   = ["ssm:TerminateSession", "ssm:ResumeSession"]
        Resource = "arn:aws:ssm:*:*:session/$${aws:userid}-*"
      },
      {
        # Present in AWS's own quickstart end-user policy for Session
        # Manager. The local session-manager-plugin opens the data channel
        # for the session it just started; scoped to this caller's own
        # sessions, so it cannot attach to anyone else's.
        Sid      = "OpenOwnSessionDataChannel"
        Effect   = "Allow"
        Action   = "ssmmessages:OpenDataChannel"
        Resource = "arn:aws:ssm:*:*:session/$${aws:userid}-*"
      },
    ]
  })
}
