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
