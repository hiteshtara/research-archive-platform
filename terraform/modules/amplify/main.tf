# Build spec is intentionally left unset: Amplify auto-discovers the
# repository's committed amplify.yml at the repo root.

resource "aws_amplify_app" "ui" {
  name       = "${var.project_name}-${var.environment}"
  repository = var.repository_url

  access_token = var.github_access_token

  environment_variables = var.environment_variables

  auto_branch_creation_config {
    enable_auto_build = false
  }

  tags = {
    Name = "${var.project_name}-${var.environment}-ui"
  }

  lifecycle {
    # repository_url/github_access_token are optional (see variables.tf):
    # when left unset, the intended workflow is to connect the repository
    # manually via the AWS Console's GitHub App-based flow after the first
    # apply (Console > Amplify > this app > "Connect branch"), which never
    # requires a token that would otherwise persist in Terraform state.
    # Without ignore_changes here, the next apply would try to revert that
    # manual connection back to "no repository" every time.
    ignore_changes = [repository, access_token, oauth_token]
  }
}

resource "aws_amplify_branch" "main" {
  app_id      = aws_amplify_app.ui.id
  branch_name = var.branch_name

  enable_auto_build = var.enable_auto_build
  framework         = "React"
  stage             = var.environment == "prod" ? "PRODUCTION" : "DEVELOPMENT"
}

resource "aws_amplify_domain_association" "custom" {
  count = var.custom_domain == null ? 0 : 1

  app_id      = aws_amplify_app.ui.id
  domain_name = var.custom_domain

  sub_domain {
    branch_name = aws_amplify_branch.main.branch_name
    prefix      = ""
  }
}
