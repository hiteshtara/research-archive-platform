# HTTPS Deployment Pattern

## Status

Implemented for BU dev (`api-dev.app-nprd.aws-cloud.bu.edu`, ACM-issued,
DNS-validated via a Route53 zone this account owns). Applies to any
environment that puts an HTTPS-hosted UI (Amplify) in front of an
ECS/ALB-hosted API.

## The problem

```
React (HTTPS, e.g. Amplify's *.amplifyapp.com)
        │
        ▼
Needs to call the API
        │
        ▼
Cannot call an HTTP-only ALB
        │
        ▼
Browser blocks it: "Mixed Content: The page loaded over HTTPS requested
an insecure HTTP API."
```

This isn't a CORS problem and CORS headers cannot fix it — the browser
refuses to even send the request. The only fix is making the API itself
HTTPS.

## The two-stage rollout

```
Stage A                              Stage B
──────────────                       ──────────────
Request ACM certificate              HTTPS listener (443) on the ALB
DNS validation (Route53)             HTTP listener (80) → redirect to HTTPS
Wait for ISSUED                      Route53 alias record → the ALB
                                      Update VITE_API_BASE_URL (https://...)
                                      Trigger an Amplify rebuild
```

### Why two stages, not one `terraform apply`?

The ALB's HTTPS listener (`aws_lb_listener.https` in
`terraform/modules/api_service/main.tf`) is conditionally created:

```hcl
resource "aws_lb_listener" "https" {
  count = var.certificate_arn == null ? 0 : 1
  ...
}
```

`count` must be known at **plan** time. The certificate ARN doesn't
exist as a real value until `aws_acm_certificate_validation` finishes —
and that resource intentionally blocks until ACM reports the
certificate `ISSUED` (DNS validation can take anywhere from under a
minute to several minutes). Terraform cannot resolve "how many listener
resources will there be" while the value that decides it is still
`(known after apply)`. Attempting both in one plan fails with:

```
Error: Invalid count argument
The "count" value depends on resource attributes that cannot be
determined until apply, so Terraform cannot predict how many instances
will be created.
```

So the certificate must be requested, validated, and **actually
present in state** before anything that reads its ARN (the listener,
and anything depending on the listener) can be planned. Two applies,
not stylistic preference:

- **Stage A**: `terraform apply -target='aws_acm_certificate_validation.api[0]'`
  — creates and validates the certificate only. Fully automatable with
  *no manual/interactive step*, provided the validating Route53 zone is
  owned by the same AWS account (true here: `app-nprd.aws-cloud.bu.edu`).
- **Stage B**: a normal `terraform apply` — now that the certificate
  ARN is a known value in state, Terraform can plan and create the
  HTTPS listener, flip the HTTP listener to a redirect, open port 443
  on the ALB's security group, add the Route53 alias record, and update
  the UI's build-time `VITE_API_BASE_URL`.

Verify Stage A actually finished before starting Stage B:

```bash
aws acm describe-certificate \
  --certificate-arn <arn> --region us-east-1 \
  --query 'Certificate.{Status:Status,Validation:DomainValidationOptions[*].ValidationStatus}'
# want: Status: ISSUED, Validation: SUCCESS
```

## Terraform shape (this implementation)

- `aws_acm_certificate` + `aws_route53_record` (DNS validation CNAME) +
  `aws_acm_certificate_validation`, all in
  `terraform/environments/dev/main.tf` — provisioned there rather than
  in a shared module, since this is one certificate for one
  environment's one hostname.
- `local.api_certificate_arn_effective` picks between a Terraform
  -provisioned certificate and an externally-supplied
  `var.api_certificate_arn`, so an environment can either provision its
  own (dev) or bring an existing one (e.g. a shared wildcard cert in
  prod).
- `modules/api_service` needed **no changes at all** — the HTTPS
  listener, the HTTP→HTTPS redirect, and the `https://` vs `http://`
  switch in the `alb_url` output were already built, just unwired.
- CORS needs no change when only the API's own domain changes — it's
  keyed off the UI's origin (`module.amplify[0].default_domain`), not
  the API's.
- `VITE_API_BASE_URL` needs no manual tfvars edit either — it's already
  `coalesce(var.ui_api_base_url, local.api_url)`, and `local.api_url`
  already switches to `https://${var.api_domain_name}` the moment
  `api_domain_name` is set.

## Finding an approved domain/zone

Before requesting a certificate, check what's actually available and
owned by the target AWS account — don't assume `bu.edu` itself, and
don't guess a zone:

```bash
aws route53 list-hosted-zones --query 'HostedZones[*].Name'
aws acm list-certificates --region us-east-1 \
  --query 'CertificateSummaryList[*].{Domain:DomainName,Status:Status}'
```

For BU dev, `app-nprd.aws-cloud.bu.edu` is a public zone already owned
by this account and already shared by several unrelated nonprod apps
(confirmed via existing, unrelated records in that zone) — the natural
home for a new `api-dev.app-nprd.aws-cloud.bu.edu`-style hostname,
picked to avoid colliding with any existing record.

## Lessons learned

- **Never expose an HTTP-only API behind an HTTPS frontend.** The
  browser will block it outright (mixed content) — this isn't a
  configuration nuance, it's a hard browser security boundary.
- **Use an ACM-issued certificate, not a self-signed one or the ALB's
  raw DNS name** — ACM certificates cannot cover an AWS-generated ALB
  hostname (e.g. `*.elb.amazonaws.com`); a real domain name is
  required.
- **Keep `VITE_API_BASE_URL` HTTPS-only** once a certificate exists —
  don't leave a mixed HTTP fallback lying around "just in case."
- **Rebuild the frontend after changing any `VITE_*` build-time
  variable.** Vite bakes these into the compiled bundle at build time,
  not runtime — updating Terraform's `environment_variables` on the
  Amplify app does **not** retroactively change an already-deployed
  bundle. Confirmed directly by fetching the live bundle before and
  after a manual `aws amplify start-job --job-type RELEASE` and
  grepping for the actual baked-in constant.
- **Keep `localhost` callback/logout URLs alongside the deployed
  origin** in Cognito during active development (`cognito_callback_urls`/
  `cognito_logout_urls` as a list, not a single value) — don't replace
  one with the other.

See `docs/architecture/LESSONS_LEARNED.md` for the broader set of
operational incidents this same rollout surfaced.

## Date last updated

2026-08-01
