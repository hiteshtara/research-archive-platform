output "app_id" {
  description = "Amplify app ID."
  value       = aws_amplify_app.ui.id
}

output "default_domain" {
  description = "Amplify's default *.amplifyapp.com domain for this app."
  value       = aws_amplify_app.ui.default_domain
}

output "branch_url" {
  description = "Default URL for the deployed branch."
  value       = "https://${aws_amplify_branch.main.branch_name}.${aws_amplify_app.ui.default_domain}"
}

output "custom_domain_certificate_verification_record" {
  description = <<-EOT
    Raw certificate-validation DNS record Amplify wants published, as a single
    space-separated "<name> <TYPE> <value>" string, or null when no custom
    domain is configured. Empty immediately after creation in some regions, so
    consumers must tolerate that and publish on a subsequent apply.
    EOT
  value       = try(aws_amplify_domain_association.custom[0].certificate_verification_dns_record, null)
}

output "custom_domain_sub_domain_records" {
  description = "Raw per-sub_domain DNS records Amplify wants published, each a space-separated \"<name> <TYPE> <value>\" string. Empty list when no custom domain is configured."
  value       = try([for sd in aws_amplify_domain_association.custom[0].sub_domain : sd.dns_record], [])
}

output "custom_domain_name" {
  description = "The custom domain attached to the Amplify app, or null when none is configured."
  value       = try(aws_amplify_domain_association.custom[0].domain_name, null)
}
