resource "aws_ecr_repository" "api" {
  name                 = "${var.project_name}-${var.environment}-api"
  image_tag_mutability = var.image_tag_mutability
  force_delete         = var.force_delete

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }

  tags = {
    Name = "${var.project_name}-${var.environment}-api"
  }
}

resource "aws_ecr_lifecycle_policy" "api" {
  repository = aws_ecr_repository.api.name

  # Mirrors modules/ecr (the loader repository). Rule ordering is
  # load-bearing: per the ECR lifecycle docs, "an image that matches the
  # tagging requirements of a rule cannot be expired or archived by a
  # rule with a lower priority", and "a rule with a tagStatus value of
  # any must have the highest value for rulePriority and be evaluated
  # last". So protected-* is rule 1 and the keep-20 sweep is renumbered
  # to rule 2.
  #
  # ECR has no "retain" action, only expire, so protection is an expire
  # rule whose threshold is never reached (100 years).
  #
  # Why this exists here: the loader repository gained this rule after
  # the Subaward nightly sync missed 14 consecutive runs
  # (2026-08-18..09-01) because the image its pinned task definition
  # referenced had been swept by keep-20. The API repository had no such
  # rule, so the image the running API serves from could be swept the
  # same way - and then no rollback to that image is possible, whatever
  # the task definition says.
  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Never sweep protected-* images (referenced by a pinned or rollback task definition)"

        selection = {
          tagStatus     = "tagged"
          tagPrefixList = ["protected-"]
          countType     = "sinceImagePushed"
          countUnit     = "days"
          countNumber   = 36500
        }

        action = {
          type = "expire"
        }
      },
      {
        rulePriority = 2
        description  = "Keep the latest 20 API images"

        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = 20
        }

        action = {
          type = "expire"
        }
      }
    ]
  })
}
