output "user_pool_id" {
  description = "Cognito User Pool ID."
  value       = aws_cognito_user_pool.this.id
}

output "user_pool_arn" {
  description = "Cognito User Pool ARN."
  value       = aws_cognito_user_pool.this.arn
}

output "app_client_id" {
  description = "Cognito app client ID used by the UI and validated by the API."
  value       = aws_cognito_user_pool_client.app.id
}

output "issuer_uri" {
  description = "OIDC issuer URI for the API's Spring Security resource-server configuration (app.security.cognito.issuer-uri)."
  value       = "https://cognito-idp.${var.aws_region}.amazonaws.com/${aws_cognito_user_pool.this.id}"
}

output "hosted_ui_domain" {
  description = "Cognito Hosted UI domain, if created."
  value       = var.hosted_ui_domain_prefix == null ? null : "https://${aws_cognito_user_pool_domain.hosted_ui[0].domain}.auth.${var.aws_region}.amazoncognito.com"
}
