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

# BU Shibboleth SAML federation - see docs/SHIBBOLETH-PLAN-3.md. Cognito
# is the SAML SP here; the signing keypair (when
# shibboleth_require_signed_requests is true) is generated and held
# entirely inside Cognito - never supplied by or exported to this
# codebase. count-gated on the metadata URL being set so this module
# still works for environments that haven't onboarded Shibboleth yet.
resource "aws_cognito_identity_provider" "shibboleth" {
  count = var.shibboleth_idp_metadata_url == null ? 0 : 1

  user_pool_id  = aws_cognito_user_pool.this.id
  provider_name = "BUShibboleth"
  provider_type = "SAML"

  provider_details = merge(
    {
      MetadataURL = var.shibboleth_idp_metadata_url
      IDPSignout  = "true"
    },
    var.shibboleth_require_signed_requests ? {
      RequestSigningAlgorithm = "rsa-sha256"
    } : {}
  )

  # Exact urn:oid:... values must be confirmed against BU's IdP
  # attribute release policy before relying on them - see
  # docs/SHIBBOLETH-PLAN-3.md section 1.1.
  attribute_mapping = {
    email    = "urn:oid:0.9.2342.19200300.100.1.3"   # mail
    username = "urn:oid:1.3.6.1.4.1.5923.1.1.1.6"     # eduPersonPrincipalName (eppn)
    # "custom:entitlement" removed for now - requires a schema attribute
    # on the User Pool, which can only be added at creation time.
    # Deferred to Phase 2 (group sync) - see docs/SHIBBOLETH-PLAN-3.md.
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

  # Referencing the resource's own provider_name (rather than a
  # hardcoded "BUShibboleth" string) gives Terraform an explicit
  # dependency edge, so the IdP is always created before this client is
  # updated to reference it.
  supported_identity_providers = concat(
    ["COGNITO"],
    [for idp in aws_cognito_identity_provider.shibboleth : idp.provider_name]
  )

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

# The single authorization gate every attachment endpoint checks
# (AttachmentAuthorizationService, via the cognito:groups JWT claim ->
# ROLE_ArchiveAttachmentViewer mapping already in SecurityConfiguration -
# no API code change needed to pick up membership here). Business-record
# access is unaffected by this group - see
# docs/architecture/NEGOTIATION_ATTACHMENT_ACCESS_DESIGN.md for the full
# product decision and reconciliation this depends on.
resource "aws_cognito_user_group" "archive_attachment_viewer" {
  name         = "ArchiveAttachmentViewer"
  user_pool_id = aws_cognito_user_pool.this.id
  description  = "Members may list, view, and download Award/Proposal/Subaward/Negotiation attachments and use the Archived File Finder. All other authenticated users keep normal business-record access; only attachment endpoints are gated by this group."
}

resource "aws_cognito_user_in_group" "archive_attachment_viewer_members" {
  for_each = toset(var.attachment_viewer_usernames)

  user_pool_id = aws_cognito_user_pool.this.id
  group_name   = aws_cognito_user_group.archive_attachment_viewer.name
  username     = each.value
}
