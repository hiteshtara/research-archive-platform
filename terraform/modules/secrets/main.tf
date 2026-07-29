# Creates the Secrets Manager container for the OpenAI API key, but never
# manages its value. Terraform intentionally does not create a
# aws_secretsmanager_secret_version here - the actual key must never be
# committed to tfvars, so it is populated out-of-band after apply:
#
#   aws secretsmanager put-secret-value \
#     --secret-id <this secret's name, from the secret_name output> \
#     --secret-string '{"apiKey":"sk-..."}'
#
# Because Terraform never touches the version, re-applying never drifts or
# overwrites the value an operator sets by hand.

resource "aws_secretsmanager_secret" "openai" {
  name                    = "${var.project_name}/${var.environment}/${var.secret_name}"
  description             = "OpenAI API key for the Research Archive AI features. Value is set out-of-band; see module README."
  recovery_window_in_days = var.recovery_window_in_days

  tags = {
    Name = "${var.project_name}-${var.environment}-openai-secret"
  }
}
