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

locals {
  # Additional secret ARNs referenced by additional_secrets, derived by
  # stripping the trailing ":<json-key>::" suffix each valueFrom string
  # carries (e.g. "<arn>:apiKey::" -> "<arn>").
  additional_secret_arns = distinct([
    for value in values(var.additional_secrets) :
    replace(value, "/:[^:]+::$/", "")
  ])

  base_environment = {
    SPRING_PROFILES_ACTIVE = "aws"
    COGNITO_ISSUER_URI     = var.cognito_issuer_uri
    COGNITO_CLIENT_ID      = var.cognito_client_id
  }

  container_environment = [
    for key, value in merge(local.base_environment, var.additional_environment_variables) :
    { name = key, value = value }
  ]

  base_secrets = {
    POSTGRES_HOST     = "${var.database_secret_arn}:host::"
    POSTGRES_PORT     = "${var.database_secret_arn}:port::"
    POSTGRES_DB       = "${var.database_secret_arn}:dbname::"
    POSTGRES_USER     = "${var.database_secret_arn}:username::"
    POSTGRES_PASSWORD = "${var.database_secret_arn}:password::"
  }

  container_secrets = [
    for key, value in merge(local.base_secrets, var.additional_secrets) :
    { name = key, valueFrom = value }
  ]

  service_subnet_ids = var.use_private_subnets ? var.private_subnet_ids : var.public_subnet_ids
}

#
# CloudWatch
#

resource "aws_cloudwatch_log_group" "api" {
  name              = "/ecs/${var.project_name}-${var.environment}-api"
  retention_in_days = var.log_retention_days

  tags = {
    Name = "${var.project_name}-${var.environment}-api-logs"
  }
}

#
# Load balancer security group
#

resource "aws_security_group" "alb" {
  name = "${var.project_name}-${var.environment}-api-alb-sg"
  # Do not change this description: it is a ForceNew attribute in AWS, so
  # editing it replaces the security group instead of updating in place.
  description = "Allow public HTTP traffic to the API load balancer"
  vpc_id      = var.vpc_id

  ingress {
    description = "HTTP from the internet"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  dynamic "ingress" {
    for_each = var.certificate_arn == null ? [] : [1]

    content {
      description = "HTTPS from the internet"
      from_port   = 443
      to_port     = 443
      protocol    = "tcp"
      cidr_blocks = ["0.0.0.0/0"]
    }
  }

  egress {
    description = "Allow outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.project_name}-${var.environment}-api-alb-sg"
  }
}

#
# ECS task security group
#

resource "aws_security_group" "api" {
  name        = "${var.project_name}-${var.environment}-api-sg"
  description = "Allow application traffic from the API load balancer"
  vpc_id      = var.vpc_id

  ingress {
    description     = "Spring Boot traffic from the ALB"
    from_port       = var.container_port
    to_port         = var.container_port
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  egress {
    description = "Allow outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.project_name}-${var.environment}-api-sg"
  }
}

resource "aws_vpc_security_group_ingress_rule" "database_from_api" {
  security_group_id            = var.database_security_group_id
  referenced_security_group_id = aws_security_group.api.id

  from_port   = 5432
  to_port     = 5432
  ip_protocol = "tcp"

  description = "PostgreSQL access from the Research Archive API"
}

#
# Application Load Balancer
#

resource "aws_lb" "api" {
  name               = "rap-${var.environment}-api-alb"
  internal           = false
  load_balancer_type = "application"

  security_groups = [aws_security_group.alb.id]
  subnets         = var.public_subnet_ids

  enable_deletion_protection = var.alb_deletion_protection

  tags = {
    Name = "${var.project_name}-${var.environment}-api-alb"
  }
}

resource "aws_lb_target_group" "api" {
  name        = "rap-${var.environment}-api-tg"
  port        = var.container_port
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = var.vpc_id

  deregistration_delay = 30

  health_check {
    enabled             = true
    path                = "/actuator/health"
    protocol            = "HTTP"
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  tags = {
    Name = "${var.project_name}-${var.environment}-api-tg"
  }
}

# Plain HTTP forwarding until a certificate is available; once certificate_arn
# is set, this becomes a redirect to HTTPS instead (see aws_lb_listener.https).
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.api.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = var.certificate_arn == null ? "forward" : "redirect"

    target_group_arn = var.certificate_arn == null ? aws_lb_target_group.api.arn : null

    dynamic "redirect" {
      for_each = var.certificate_arn == null ? [] : [1]

      content {
        port        = "443"
        protocol    = "HTTPS"
        status_code = "HTTP_301"
      }
    }
  }
}

resource "aws_lb_listener" "https" {
  count = var.certificate_arn == null ? 0 : 1

  load_balancer_arn = aws_lb.api.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
}

#
# ECS IAM roles
#

resource "aws_iam_role" "execution" {
  name               = "${var.project_name}-${var.environment}-api-execution-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_task_assume_role.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "execution_secret_access" {
  statement {
    effect = "Allow"

    actions = [
      "secretsmanager:GetSecretValue"
    ]

    resources = concat(
      [var.database_secret_arn],
      local.additional_secret_arns
    )
  }
}

resource "aws_iam_role_policy" "execution_secret_access" {
  name   = "${var.project_name}-${var.environment}-api-secret-access"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution_secret_access.json
}

resource "aws_iam_role" "task" {
  name               = "${var.project_name}-${var.environment}-api-task-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_task_assume_role.json
}

data "aws_iam_policy_document" "task_documents" {
  count = var.documents_bucket_arn == null ? 0 : 1

  statement {
    effect    = "Allow"
    actions   = ["s3:ListBucket"]
    resources = [var.documents_bucket_arn]
  }

  statement {
    effect    = "Allow"
    actions   = ["s3:GetObject"]
    resources = ["${var.documents_bucket_arn}/*"]
  }
}

resource "aws_iam_role_policy" "task_documents" {
  count = var.documents_bucket_arn == null ? 0 : 1

  name   = "${var.project_name}-${var.environment}-api-documents"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task_documents[0].json
}

#
# ECS cluster, task definition and service
#

resource "aws_ecs_cluster" "api" {
  name = "${var.project_name}-${var.environment}-api"

  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_ecs_task_definition" "api" {
  family                   = "${var.project_name}-${var.environment}-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"

  cpu    = "512"
  memory = "1024"

  execution_role_arn = aws_iam_role.execution.arn
  task_role_arn      = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([
    {
      name      = "api"
      image     = var.api_image
      essential = true

      portMappings = [
        {
          containerPort = var.container_port
          hostPort      = var.container_port
          protocol      = "tcp"
        }
      ]

      environment = local.container_environment
      secrets     = local.container_secrets

      logConfiguration = {
        logDriver = "awslogs"

        options = {
          awslogs-group         = aws_cloudwatch_log_group.api.name
          awslogs-region        = var.aws_region
          awslogs-stream-prefix = "api"
        }
      }
    }
  ])
}

resource "aws_ecs_service" "api" {
  name            = "${var.project_name}-${var.environment}-api"
  cluster         = aws_ecs_cluster.api.id
  task_definition = aws_ecs_task_definition.api.arn

  desired_count = var.desired_count
  launch_type   = "FARGATE"

  platform_version = "LATEST"

  network_configuration {
    subnets          = local.service_subnet_ids
    security_groups  = [aws_security_group.api.id]
    assign_public_ip = !var.use_private_subnets
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "api"
    container_port   = var.container_port
  }

  health_check_grace_period_seconds = 120

  depends_on = [
    aws_lb_listener.http,
    aws_iam_role_policy_attachment.execution,
    aws_iam_role_policy.execution_secret_access
  ]
}
