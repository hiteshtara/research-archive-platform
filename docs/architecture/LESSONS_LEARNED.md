# Lessons Learned — Operational Incidents

Cross-cutting operational lessons from standing up the Award API, Cognito,
and Amplify UI hosting for BU dev. Each entry is deliberately short - full
incident detail (exact commands, exact error text, exact fix) lives in
`docs/architecture/AWARD_IMPLEMENTATION_ROADMAP.md`'s dated same-day
follow-up entries, cross-referenced below. This document exists so the
*pattern* of each mistake is easy to recognize again quickly, without
re-reading the full incident.

## 1. Cross-account Cognito misconfiguration

`terraform.tfvars` had `manage_cognito = false` with a comment claiming
the User Pool was "created outside Terraform," supplying a specific pool
ID/client ID/issuer URI as that existing pool. The pool didn't exist in
the BU account at all - it existed in an unrelated personal AWS account
that happened to have identically-named resources. The API's issuer and
the UI's Cognito config were both silently pointed at an issuer BU didn't
own, with no error until someone actually tried to authenticate.

**Pattern to watch for:** a config comment asserting "this was created
elsewhere" is a claim, not a fact - verify the referenced resource
actually exists in the account you're pointed at
(`aws sts get-caller-identity` first, then describe the resource) before
trusting it.

See: "Twelfth same-day follow-up."

## 2. Deploy script hardcoded to the wrong AWS account

`ops/deploy-api.sh` hardcoded `ACCOUNT_ID="589744711110"` - a personal
AWS account, not BU's. Because that personal account happened to have
identically-named ECS/ECR resources, the script "worked" - it just
silently built and deployed to the wrong account, for an unknown period,
with no error at any step.

**Pattern to watch for:** any hardcoded AWS account ID in a script is a
latent version of this bug, even if it currently matches the intended
account. Fixed by resolving the account from `aws sts get-caller-identity`
at runtime and aborting before any mutating step if it doesn't match an
explicit expected value.

See: "Eleventh same-day follow-up," `ops/deploy-api.sh`'s own header
comment.

## 3. Amplify manual-to-Git conversion

An Amplify app created via Terraform with no repository attached
defaults every branch to "manually deployed." Attempting to attach a
repository to an app with existing manual branches fails outright:
`"Cannot connect your app to repository while manually deployed branch
still exists."` Branches must be deleted first - and deleting the
Terraform-managed branch via `terraform destroy -target` turned out to
be unsafe (see #5 below), so the fix used `terraform state rm` (removes
Terraform's bookkeeping only) plus a direct `aws amplify delete-branch`
call, then recreated the branch fresh once the repository was attached.

**Pattern to watch for:** an Amplify app's "shape" (manual vs.
Git-connected) is decided by whether *any* branch was ever created
before a repository existed - order of operations matters, and undoing
it isn't just "add the repository later."

See: "Fourteenth same-day follow-up."

## 4. Terraform provider issue with `aws_amplify_app` repository attachment

Setting `repository` + `access_token` on an existing `aws_amplify_app`
via Terraform fails with `BadRequestException: You should at least
provide one valid token` - even with a valid, correctly-scoped classic
PAT. The identical `repository` + `--access-token` via a raw
`aws amplify update-app` CLI call succeeds immediately (webhook
created). This is a provider bug, not a token problem or an AWS API
limitation - confirmed by isolating the exact same inputs across two
different clients. Worked around by attaching the repository via the
raw CLI call, then `terraform apply -refresh-only` to resync state
before letting Terraform manage anything else (like creating the
branch, which needs no token at all).

**Pattern to watch for:** when a Terraform resource update fails with a
vague AWS-side validation error, test the identical operation via the
raw AWS CLI before concluding the input (token, value, permission) is
actually wrong - it isolates provider bugs from real input problems in
one step.

See: "Fourteenth same-day follow-up." Worth filing upstream against
`terraform-provider-aws`.

## 5. API CORS dependency on the Amplify branch resource

`local.cors_allowed_origins` (feeding the API's `APP_CORS_ALLOWED_ORIGINS`
env var) was built from `module.amplify[0].branch_url`, which is derived
from the Amplify *branch resource's* own `branch_name` attribute - not
just the app. This created a real Terraform dependency:
`aws_ecs_task_definition.api → aws_amplify_app.ui` (confirmed via
`terraform graph`). Destroying/recreating the Amplify branch (needed for
#3 above) would have cascaded into a plan that also destroyed the
**live API's ECS task definition and service** - caught by running
`terraform plan -destroy -target=...` *before* actually applying it, not
after.

**Pattern to watch for:** deriving one resource's configuration from
another module's output can silently create a dependency on a specific
*resource* inside that module, not just a stable value. Prefer deriving
from the most stable/highest-level attribute available (here,
`aws_amplify_app.ui.default_domain`, an app-level attribute, instead of
the branch-derived `branch_url` - same resulting string, no dependency
on the branch resource). Always read a `-target`/`-destroy` plan's full
resource list before applying it, especially when it touches anything
also relied on by a live service.

See: "Fourteenth same-day follow-up."

## 6. HTTPS rollout requiring the ACM two-stage deployment

Adding HTTPS to the ALB requires a certificate ARN that doesn't exist
until ACM finishes DNS validation - but the HTTPS listener's `count`
(0 or 1, depending on whether a certificate is configured) must be known
at Terraform *plan* time, before that validation can possibly have
finished in the same apply. One `terraform apply` cannot both request a
certificate and create everything that depends on its ARN.

**Pattern to watch for:** any resource whose `count`/`for_each` depends
on another resource's computed output that doesn't exist until a
slow/asynchronous external process finishes (DNS validation here; also
true of things like waiting for an ACM certificate, an RDS snapshot
restore, or a long-running import) needs a deliberate two-stage
apply (`-target` the prerequisite first) - it isn't something a
cleverer single Terraform graph can resolve away.

See: `docs/architecture/HTTPS_DEPLOYMENT.md` for the full pattern, and
"Fifteenth same-day follow-up" for this specific rollout.

## Date last updated

2026-08-01
