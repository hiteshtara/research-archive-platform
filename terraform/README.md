# Terraform deployment

This directory provisions the Research Archive Platform's AWS infrastructure:
VPC/networking, RDS PostgreSQL, ECR, ECS (API + ETL loader), the API's load
balancer, S3 document/data storage, and (optionally) Cognito and Amplify.

```
terraform/
  bootstrap/                One-time: creates the S3 state bucket (local state)
  environments/
    dev/                    Existing BU dev account (589744711110)
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
uses **local** state to solve this, once:

```bash
cd terraform/bootstrap
cp terraform.tfvars.example terraform.tfvars   # fill in your account ID and a bucket name
terraform init
terraform plan
terraform apply
```

Note the `state_bucket_name` output - you'll need it in step 3.

You only need to do this once per AWS account, no matter how many
environments (dev/test/prod) you run in it.

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

## 5. Populate the OpenAI secret (if using the live provider)

Terraform creates the Secrets Manager container for the OpenAI API key but
deliberately never sets its value (so the key is never written to state or
tfvars). After the first apply:

```bash
aws secretsmanager put-secret-value \
  --secret-id "$(terraform output -raw openai_secret_name)" \
  --secret-string '{"apiKey":"sk-..."}'
```

Then set `additional_api_environment_variables` in your tfvars to actually
enable the live provider, e.g.:

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
`cognito_client_id`, `data_bucket_name`, `documents_bucket_name`, and
`openai_secret_name`. Run `terraform output <name>` for a single value, or
`terraform output -raw <name>` to strip the quotes for scripting.

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

- **`false`** (dev's current setting): you supply `cognito_issuer_uri` /
  `cognito_client_id` for an existing User Pool, and don't set
  `manage_amplify` at all - your UI is hosted some other way.
- **`true`**: Terraform creates a Cognito User Pool + app client (and,
  optionally, a Hosted UI domain), and/or an Amplify app connected to a
  GitHub repository. Use this for a brand-new BU account that doesn't have
  either yet.

`terraform.tfvars.example` in each environment shows both options.

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
variable, on by default in `prod`). The two S3 buckets (`data`,
`documents`) additionally carry a hardcoded `prevent_destroy = true`
lifecycle block in `modules/s3/main.tf` - Terraform's lifecycle
meta-arguments can't be driven by a variable, so this applies to every
environment, including dev. If you genuinely need to destroy a dev
environment's buckets, either comment out that block for the run or
`terraform state rm` the bucket resources first; see the comment directly
above the resource in `modules/s3/main.tf`.

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
