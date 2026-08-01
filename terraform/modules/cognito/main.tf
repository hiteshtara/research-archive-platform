resource "aws_cognito_user_pool" "this" {
  name = "${var.project_name}-${var.environment}"

  username_attributes = ["email"]

  password_policy {
    minimum_length    = 12
    require_lowercase = true
    require_numbers   = true
    require_symbols   = true
    require_uppercase = true
  }

  admin_create_user_config {
    allow_admin_create_user_only = true
  }

  user_pool_add_ons {
    advanced_security_mode = var.advanced_security_mode
  }

  deletion_protection = var.deletion_protection

  mfa_configuration = var.mfa_configuration

  dynamic "software_token_mfa_configuration" {
    for_each = var.mfa_configuration == "OFF" ? [] : [1]

    content {
      enabled = true
    }
  }

  tags = {
    Name = "${var.project_name}-${var.environment}-user-pool"
  }

  # Matches the same hardcoded protection already applied to the data/
  # documents S3 buckets (modules/s3/main.tf) - lifecycle meta-arguments
  # can't be driven by a variable, so this applies in every environment,
  # including dev. To genuinely destroy a dev pool, comment this out for
  # the run or `terraform state rm` it first.
  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_cognito_user_pool_client" "app" {
  name         = "${var.project_name}-${var.environment}-app-client"
  user_pool_id = aws_cognito_user_pool.this.id

  # Public SPA client: no client secret.
  generate_secret = false

  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["code"]
  allowed_oauth_scopes                 = ["openid", "email", "profile"]

  supported_identity_providers = ["COGNITO"]

  callback_urls = var.callback_urls
  logout_urls   = var.logout_urls

  explicit_auth_flows = concat(
    ["ALLOW_USER_SRP_AUTH", "ALLOW_REFRESH_TOKEN_AUTH"],
    var.allow_admin_password_auth ? ["ALLOW_ADMIN_USER_PASSWORD_AUTH"] : []
  )

  prevent_user_existence_errors = "ENABLED"

  access_token_validity  = 1
  id_token_validity      = 1
  refresh_token_validity = 30

  token_validity_units {
    access_token  = "hours"
    id_token      = "hours"
    refresh_token = "days"
  }
}

resource "aws_cognito_user_pool_domain" "hosted_ui" {
  count = var.hosted_ui_domain_prefix == null ? 0 : 1

  domain       = var.hosted_ui_domain_prefix
  user_pool_id = aws_cognito_user_pool.this.id
}
