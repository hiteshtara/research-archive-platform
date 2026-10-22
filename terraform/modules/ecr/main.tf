resource "aws_ecr_repository" "loader" {
  name                 = "${var.project_name}-${var.environment}-loader"
  image_tag_mutability = var.image_tag_mutability
  force_delete         = var.force_delete

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }

  tags = {
    Name = "${var.project_name}-${var.environment}-loader"
  }
}

resource "aws_ecr_lifecycle_policy" "loader" {
  repository = aws_ecr_repository.loader.name

  # Rule ordering is load-bearing, not cosmetic. Per the ECR lifecycle
  # docs: "An image that matches the tagging requirements of a rule
  # cannot be expired or archived by a rule with a lower priority", and
  # "A rule with a tagStatus value of any must have the highest value
  # for rulePriority and be evaluated last". So the protected-* rule
  # must come first and the keep-20 sweep must be renumbered after it.
  #
  # ECR has no "retain" action - only expire - so protection is
  # expressed as an expire rule whose threshold is never reached
  # (100 years). The paired detective control is the aws_ecr_image
  # data source in modules/subaward_sync_schedule, which fails
  # `terraform plan` if the pinned image ever disappears anyway.
  #
  # Why this exists: the Subaward nightly sync missed 14 consecutive
  # runs (2026-08-18..09-01) because the image its pinned task
  # definition referenced was swept by the keep-20 rule.
  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Never sweep protected-* images (referenced by a pinned task definition)"

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
        description  = "Keep the latest 20 loader images"

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
