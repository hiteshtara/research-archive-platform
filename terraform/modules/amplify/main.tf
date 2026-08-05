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
    # repository is in ignore_changes alongside access_token/oauth_token so
    # that terraform/README.md's Recommended flow is actually safe: leave
    # repository_url null, apply, then connect the repo manually via AWS
    # Console > Amplify > App settings > General > Connect a repository
    # (the GitHub App-based flow - a one-time interactive step AWS
    # requires: installing the Amplify GitHub App itself against the
    # target repo, https://github.com/apps/aws-amplify-<region>/installations/new,
    # done once per account/region, not per app, with no Terraform
    # equivalent). Without repository in ignore_changes, the next
    # "terraform apply" would see actual repository != desired
    # repository_url (null) and revert/detach the manual connection,
    # since repository/access_token/oauth_token have no ForceNew and
    # would otherwise be reconciled in place via the same AWS UpdateApp
    # call. access_token/oauth_token are ignored for a different reason:
    # AWS never returns them on read (write-only, used once to create a
    # webhook/deploy key and not stored), so tracking them would show a
    # permanent phantom diff every plan regardless of which repository
    # flow is in use.
    #
    # The legacy PAT-based flow (set repository_url and
    # github_access_token via TF_VAR_amplify_github_access_token, then
    # apply) still works for *initial creation* - ignore_changes only
    # suppresses drift detection on later applies, not the values used
    # when the resource is first created. Changing repository_url after
    # that first apply will not be picked up automatically; reconnect it
    # via the console, or remove it from ignore_changes for one apply.
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
