# Terraform deployment

This directory provisions the Research Archive Platform's AWS infrastructure:
VPC/networking, RDS PostgreSQL, ECR, ECS (API + ETL loader), the API's load
balancer, S3 document/data storage, and (optionally) Cognito and Amplify.

```
terraform/
  bootstrap/                One-time: creates the S3 state bucket (local state)
  environments/
    dev/                    Existing BU dev account (770203350335)
    test/                   Template for a test environment
    prod/                   Template for BU's production account
  modules/                  Reusable building blocks used by every environment
```

## Prerequisites

- Terraform >= 1.8.0
- AWS CLI v2, configured with credentials for the target account (SSO
  recommended: `aws sso login --profile <profile>`)
- An IAM principal with permission to create the resources below (an
  account admin/power-user role is the simplest starting point)

## 1. Verify which account you're about to touch

**Always run this before `init`/`plan`/`apply`, in every environment, every
time** - this is the single most important habit for avoiding an accidental
cross-account deployment:

```bash
aws sts get-caller-identity
```

Confirm the `Account` field matches the `expected_account_id` you're about
to set in that environment's `terraform.tfvars`. If it doesn't, `terraform
plan`/`apply` will refuse to proceed anyway (see [Account safety](#account-safety)
below) - but check first regardless.

## 2. Bootstrap the state backend (once per AWS account)

Terraform's S3 backend needs a bucket to exist before `terraform init` can
use it - and that bucket can't be created by the same Terraform run that
needs it. `terraform/bootstrap/` is a small, separate configuration that
starts on **local** state to solve this:

```bash
cd terraform/bootstrap
cp terraform.tfvars.example terraform.tfvars   # fill in your account ID and a bucket name
terraform init
terraform plan
terraform apply
```

Note the `state_bucket_name` output - you'll need it in step 3.

You only need to create the bucket once per AWS account, no matter how many
environments (dev/test/prod) you run in it. **But this is not "run once and
forget"** - do the following immediately after, so bootstrap's own state
isn't a single local file with no backup:

### Make bootstrap's own state durable (recommended, do this right after the first apply)

```bash
cp backend.tf.example backend.tf   # fill in the bucket name from state_bucket_name
terraform init -migrate-state
```

This moves bootstrap's state into the bucket it just created (under the key
`bootstrap/terraform.tfstate`, separate from every environment's own state
key), so it becomes versioned and durable exactly like every environment's
state already is - instead of a local file that only exists on whichever
machine ran the first apply.

### Recovery model

- **State lives**: after the migration above, in the same S3 bucket
  `bootstrap` manages, under `bootstrap/terraform.tfstate` (versioned,
  encrypted, exactly like environment state).
- **Backup**: S3 versioning on the state bucket itself is your backup -
  every version of bootstrap's state is retained. No separate backup step
  is needed once migrated.
- **If migrated and you need to recover on a new machine**: `git clone`,
  `cd terraform/bootstrap`, `cp backend.tf.example backend.tf` (fill in the
  bucket name), `terraform init` - state comes from S3, nothing to import.
- **If state is lost before ever migrating** (worst case: local-only state
  on a machine that's now gone): the bucket itself still exists (it has
  `prevent_destroy = true`) and is recoverable by re-attaching state to it:
  ```bash
  cd terraform/bootstrap
  cp terraform.tfvars.example terraform.tfvars   # same account ID and bucket name as before
  terraform init
  terraform import aws_s3_bucket.state <bucket-name>
  terraform import aws_s3_bucket_versioning.state <bucket-name>
  terraform import aws_s3_bucket_server_side_encryption_configuration.state <bucket-name>
  terraform import aws_s3_bucket_public_access_block.state <bucket-name>
  terraform import aws_s3_bucket_ownership_controls.state <bucket-name>
  terraform plan   # should show no changes if the bucket's actual config matches this file
  ```
  Then proceed with the state-migration step above so this doesn't happen again.
- **Never** run `terraform destroy` against this configuration, and never
  delete/recreate the state bucket by hand - every environment's remote
  state depends on it continuing to exist untouched. `prevent_destroy`
  is a backstop, not a substitute for care.

## 3. Configure and initialize an environment

```bash
cd terraform/environments/dev   # or test, or prod

cp backend.hcl.example backend.hcl   # fill in the bucket from step 2
cp terraform.tfvars.example terraform.tfvars   # dev already has a real terraform.tfvars checked in - see note below

terraform init -backend-config=backend.hcl
```

**Note on dev's `terraform.tfvars`**: unlike `test`/`prod`, `environments/dev/terraform.tfvars`
is already checked into the repository with real (non-secret) values for
the existing BU dev account, since none of its values are sensitive. If
you're standing up a *new* dev-equivalent environment for a different
account, treat `terraform.tfvars.example` as the starting point instead.

## 4. Plan and apply

```bash
terraform plan
terraform apply
```

Review the plan output carefully, especially the first time you apply in a
given account - confirm the resource count and the account ID in the ARNs
shown match what you expect.

## 5. Enable and populate the OpenAI secret (only if using the live provider)

By default, `enable_openai_secret = false`: no Secrets Manager secret is
created, `OPENAI_API_KEY` is never referenced by the API task definition,
and the execution role is granted no access to any such secret. ECS deploys
successfully with no OpenAI secret at all, and the API falls back to the
deterministic stub AI provider. Nothing below applies unless you actually
want the live provider.

To enable it, set in your tfvars:

```hcl
enable_openai_secret = true
```

`terraform apply` then creates the Secrets Manager container for the key
but deliberately never sets its value (so the key is never written to state
or tfvars). After applying:

```bash
aws secretsmanager put-secret-value \
  --secret-id "$(terraform output -raw openai_secret_name)" \
  --secret-string '{"apiKey":"sk-..."}'
```

Then also set `additional_api_environment_variables` to actually enable the
live provider, e.g.:

```hcl
additional_api_environment_variables = {
  APP_AI_ENABLED        = "true"
  APP_AI_PROVIDER       = "openai"
  APP_AI_OPENAI_ENABLED = "true"
}
```

See `docs/runbooks/ecs-ai-deployment.md` for the full list of `APP_AI_*`
flags.

## Outputs

After `apply`, `terraform output` shows (among others): `api_url`, `ui_url`
(when `manage_amplify = true`), `database_endpoint`, `cognito_issuer_uri`,
`cognito_client_id`, `cognito_user_pool_id`, `cognito_hosted_ui_domain`,
`ui_redirect_url`, `ui_logout_url`, `data_bucket_name`,
`documents_bucket_name`, and `openai_secret_name` (null unless
`enable_openai_secret = true`). Run `terraform output <name>` for a single
value, or `terraform output -raw <name>` to strip the quotes for scripting.

## Account safety

Every environment's `provider.tf` sets `allowed_account_ids = [var.expected_account_id]`.
If your active AWS credentials resolve to any other account, the AWS
provider refuses to do anything at all - `plan` and `apply` both fail
immediately, before touching a single resource. There is no default for
`expected_account_id`; you must set it explicitly per environment.

## Environment separation

`dev`, `test`, and `prod` are separate directories with separate backend
state keys (and, once bootstrapped, ideally separate state buckets/accounts
entirely) - not Terraform workspaces. This repo uses directories rather
than workspaces because the environments need materially different
variable *values* (instance sizing, deletion protection, image-tag
immutability) in addition to different state, and because a workspace
mistake (`terraform workspace select` typo) is an easier way to
accidentally apply against the wrong environment than a wrong `-chdir`.

Each environment also uses distinct VPC CIDR ranges (dev `10.30.0.0/16`,
test `10.31.0.0/16`, prod `10.32.0.0/16`) so they can be peered or connected
via Transit Gateway later without a CIDR collision.

## Bring your own Cognito/Amplify, or let Terraform manage them

Both are controlled by `manage_cognito` / `manage_amplify` (both default
`false`):

- **`false`** (dev's current setting): you supply `cognito_issuer_uri`,
  `cognito_client_id`, and `cognito_user_pool_id` for an existing User Pool
  (plus `cognito_hosted_ui_domain` if `manage_amplify = true`), and don't
  set `manage_amplify` at all - your UI is hosted some other way.
- **`true`**: Terraform creates a Cognito User Pool + app client (and,
  optionally, a Hosted UI domain), and/or an Amplify app connected to a
  GitHub repository. Use this for a brand-new BU account that doesn't have
  either yet.

`terraform.tfvars.example` in each environment shows both options.

### The UI gets its Cognito configuration from Terraform, not from source

`ui/src/auth.ts` has no hardcoded pool ID, client ID, domain, or URL - it
reads `VITE_AWS_REGION`, `VITE_COGNITO_USER_POOL_ID`, `VITE_COGNITO_CLIENT_ID`,
`VITE_COGNITO_DOMAIN`, `VITE_COGNITO_REDIRECT_URL`, and
`VITE_COGNITO_LOGOUT_URL` at build time (`import.meta.env.VITE_*`) and
throws immediately if any are missing. When `manage_amplify = true`,
Terraform sets all six as Amplify build-time environment variables
(`module.amplify.environment_variables`), computed from whichever
Cognito source is in effect (created pool or bring-your-own) - see
`local.cognito_*` in `main.tf`. For local development, copy `ui/.env.example`
to `ui/.env.local` and fill in values from `terraform output`.

`VITE_COGNITO_REDIRECT_URL`/`VITE_COGNITO_LOGOUT_URL` come from
`ui_redirect_url`/`ui_logout_url`, which must also appear in
`cognito_callback_urls`/`cognito_logout_urls` (Cognito's own allow-list is a
separate, independent variable - keep them in sync). Amplify's own
`*.amplifyapp.com` domain is only assigned after the app is created, so
Terraform cannot auto-derive it as a build input to that same app (a
resource can't depend on its own computed output). Two ways to handle this:

- Set `amplify_custom_domain` to a domain you control - `ui_redirect_url`/
  `ui_logout_url` then default to `https://<amplify_custom_domain>/`
  automatically.
- Or do a one-time two-step bring-up with the default `amplifyapp.com`
  domain: apply once with a placeholder (e.g.
  `ui_redirect_url = ui_logout_url = "http://localhost:5173/"`), read the
  real URL from `terraform output ui_url`, then set `ui_redirect_url`/
  `ui_logout_url` (and add the same URL to `cognito_callback_urls`/
  `cognito_logout_urls`) to that value and re-apply.

### Amplify repository connection: avoid a token in state

`aws_amplify_app` (AWS provider `~> 6.0`, verified against the provider
schema) only supports repository auth via `access_token`/`oauth_token` -
there is no write-only-argument or CodeStarConnections-based alternative
for this specific resource, so either one is stored in Terraform state,
plaintext, for as long as the resource exists.

**Recommended**: leave `amplify_repository_url` and
`amplify_github_access_token` both unset. Terraform creates the Amplify app
with no repository connected; after the first apply, go to **AWS Console >
Amplify > (this app) > App settings > General > Connect a repository** and
use the GitHub App-based flow (a one-time authorization of the BU GitHub
org/repo - no PAT involved). The module's `lifecycle { ignore_changes =
[repository, access_token, oauth_token] }` means later `terraform apply`
runs will never try to revert that manual connection.

**Legacy alternative**: set both `amplify_repository_url` and
`amplify_github_access_token` (via `TF_VAR_amplify_github_access_token`,
never in a tfvars file) to use the older PAT-based flow - accepted, but the
token still ends up in state as described above.

A `config_guard` precondition enforces that these two variables are either
both set or both unset - never one without the other.

## Production HTTPS and DNS

`api_certificate_arn` is optional in `dev`/`test` (HTTP-only is fine for
initial bring-up before a certificate exists) but **required in `prod`** -
a `config_guard` precondition refuses to `plan`/`apply` prod with it unset.
Setting `api_certificate_arn` also requires `api_domain_name` (the ALB's own
DNS name is never a valid ACM certificate subject), which in turn requires
`api_route53_zone_id` (the ID of an existing Route53 hosted zone that
`api_domain_name` belongs to - e.g. a zone for `bu.edu` or a subdomain
delegated to your account; Terraform does not create hosted zones for a
domain it doesn't own). When both are set, Terraform creates an ALIAS `A`
record for `api_domain_name` pointing at the ALB, and `api_url`/
`VITE_API_BASE_URL` both use `https://<api_domain_name>` instead of the raw
ALB DNS name.

Terraform cannot verify that the ACM certificate you supply actually covers
`api_domain_name` - there is no AWS provider data source for looking up a
certificate's Subject Alternative Names by ARN. Confirm this yourself before
applying:

```bash
aws acm describe-certificate --certificate-arn <arn> \
  --query 'Certificate.SubjectAlternativeNames'
```

## NAT Gateway / private subnets for the API

By default (`enable_nat_gateway = false`, `use_private_subnets_for_api =
false`), the API's ECS tasks run in the public subnets with a public IP,
matching how the existing dev environment actually works today - the tasks
reach the internet (needed for the live OpenAI provider) directly via the
Internet Gateway. This is not defense-in-depth best practice, but changing
it is a real infrastructure change, not a Terraform default flip, so it's
opt-in: set both `enable_nat_gateway = true` and `use_private_subnets_for_api
= true` to run the tasks in private subnets with egress through a NAT
Gateway instead (recommended for production; adds NAT Gateway cost).

## Stateful-resource protection

The RDS instance supports the native `deletion_protection` flag (a
variable, on by default in `prod`), plus `multi_az` (standby replica for
automatic failover - `false` in dev/test, `true` in prod by default) and
`apply_immediately` (`true` in dev/test so changes land right away; `false`
in prod so disruptive changes - e.g. an instance class resize - wait for
the next `maintenance_window` instead of applying mid-day). Prod also pins
`database_maintenance_window`/`database_backup_window` to specific
off-peak UTC times rather than letting AWS assign random ones, so deferred
changes land somewhere predictable.

The two S3 buckets (`data`, `documents`) and the Cognito User Pool (when
`manage_cognito = true`) additionally carry a hardcoded
`prevent_destroy = true` lifecycle block (`modules/s3/main.tf`,
`modules/cognito/main.tf`) - Terraform's lifecycle meta-arguments can't be
driven by a variable, so this applies to every environment, including dev.
If you genuinely need to destroy a dev environment's buckets or pool,
either comment out that block for the run or `terraform state rm` the
resource first; see the comment directly above each resource.

Cognito also supports its own native `deletion_protection` (`ACTIVE`/
`INACTIVE`, independent of the lifecycle block above - `INACTIVE` in
dev/test, `ACTIVE` in prod by default) and `mfa_configuration` (`OFF`/`ON`/
`OPTIONAL`, TOTP/software-token only - `OFF` in dev/test, `OPTIONAL` in prod
by default), plus `advanced_security_mode` (`OFF`/`AUDIT`/`ENFORCED` -
`AUDIT` in dev/test, `ENFORCED` in prod by default), all set via
`cognito_deletion_protection`/`cognito_mfa_configuration`/
`cognito_advanced_security_mode`.

## Destroy procedure

```bash
cd terraform/environments/<env>
terraform plan -destroy   # review first, always
terraform destroy
```

`terraform destroy` will refuse on the S3 buckets (see above) and, in any
environment with `database_deletion_protection = true`, on the RDS
instance - both deliberately. Remove those protections explicitly if a full
teardown is really what you want.

## Migration / cutover notes

- **This Terraform is not currently applied cleanly against the live dev
  environment.** A `terraform plan` run before this refactor showed drift
  (a deleted-then-recreated RDS instance, and an API task definition several
  revisions behind what's actually running - see the audit report from this
  work). Reconcile that drift (confirm the real RDS state, and either
  `terraform import`/`taint` or manually align the task definition) with a
  clean `terraform plan` showing no unexpected changes *before* applying
  this refactored configuration against the existing dev account.
- When moving the UI/Cognito from their current manually-managed state into
  Terraform (`manage_cognito`/`manage_amplify = true`), do this for a new
  environment first, not by flipping the flag on dev out from under a
  running deployment - creating a second User Pool means every existing
  user's session/credentials are for the *old* pool, and switching the
  API/UI to a new pool is a real user-facing migration, not a Terraform
  detail.
- Populate the OpenAI secret (step 5 above) and any `additional_api_environment_variables`
  needed for the AI features *before* relying on `terraform apply` to
  manage the API task definition going forward - otherwise the next apply
  will revert those to whatever's in this configuration, same as the drift
  problem above.

## Validation

```bash
terraform fmt -check -recursive .
terraform validate   # run inside each environment directory, after init
```

Also run `tflint` and/or `checkov`/`tfsec` if available in your environment
- they were not installed when this configuration was last audited; add
  them to CI if possible.
