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

#
# CloudWatch
#

resource "aws_cloudwatch_log_group" "api" {
  name              = "/ecs/${var.project_name}-${var.environment}-api"
  retention_in_days = 14

  tags = {
    Name = "${var.project_name}-${var.environment}-api-logs"
  }
}

#
# Load balancer security group
#

resource "aws_security_group" "alb" {
  name        = "${var.project_name}-${var.environment}-api-alb-sg"
  description = "Allow public HTTP traffic to the API load balancer"
  vpc_id      = var.vpc_id

  ingress {
    description = "HTTP from the internet"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
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

  enable_deletion_protection = false

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

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.api.arn
  port              = 80
  protocol          = "HTTP"

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

    resources = [
      var.database_secret_arn
    ]
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

      environment = [
        {
          name  = "SPRING_PROFILES_ACTIVE"
          value = "aws"
        },
        {
          name  = "COGNITO_ISSUER_URI"
          value = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_KnifXAgWm"
        },
        {
          name  = "COGNITO_CLIENT_ID"
          value = "4svvnli76o8j2qtekkvasq7agc"
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
    subnets          = var.public_subnet_ids
    security_groups  = [aws_security_group.api.id]
    assign_public_ip = true
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
