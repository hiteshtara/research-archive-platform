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

  # SPA routing fallback: a direct browser request for a React route
  # (e.g. /awards/100004-00001) has no matching object in Amplify's
  # static hosting - without this rule it 404s instead of loading the
  # app, which then handles the route client-side. Excludes real static
  # asset extensions so those still 404 correctly instead of falling
  # back to index.html.
  custom_rule {
    source = "</^[^.]+$|\\.(?!(css|gif|ico|jpg|js|png|txt|svg|woff|woff2|ttf|map|json)$)([^.]+$)/>"
    target = "/index.html"
    status = "200"
  }

  tags = {
    Name = "${var.project_name}-${var.environment}-ui"
  }

  lifecycle {
    # repository is tracked (not ignored) - Terraform attaches/updates it
    # via the same AWS UpdateApp API call either way, in place, no
    # replacement (verified against the provider's own resource schema:
    # repository/access_token/oauth_token have no ForceNew). The
    # one-time interactive step AWS actually requires is installing the
    # Amplify GitHub App itself against the target repo
    # (https://github.com/apps/aws-amplify-<region>/installations/new) -
    # that's a GitHub-side action with no Terraform equivalent, done
    # once per account/region, not per app. After that, attaching this
    # specific app to a specific repo/branch is a normal Terraform
    # change: set repository_url and supply github_access_token (a
    # narrowly-scoped admin:repo_hook-only PAT - see variables.tf) via
    # TF_VAR_amplify_github_access_token, then apply. AWS uses that
    # token once, to create a webhook and read-only deploy key, and
    # does not store it - which is exactly why access_token/oauth_token
    # stay in ignore_changes below: AWS never returns them on read, so
    # tracking them would show a permanent phantom diff every plan.
    ignore_changes = [access_token, oauth_token]
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
