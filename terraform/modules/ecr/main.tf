resource "aws_ecr_repository" "loader" {
  name                 = "${var.project_name}-${var.environment}-loader"
  image_tag_mutability = "MUTABLE"
  force_delete         = true

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
