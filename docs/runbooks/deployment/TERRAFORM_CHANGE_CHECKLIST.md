# Terraform change checklist — `environments/dev`

Run before every `terraform apply`. Written after 2026-10-23, when a plan
proposed destroying the working `BUShibboleth` identity provider and was
caught only because the plan was read line by line rather than by its summary.

## Why this exists

Two Terraform behaviours make a *partial* configuration actively destructive:

1. **Count-gated resources vanish when their variable is absent.**
   `aws_cognito_identity_provider.shibboleth` is
   `count = var.shibboleth_idp_metadata_url == null ? 0 : 1`. A resource that
   exists in AWS but whose gating variable is missing from `terraform.tfvars`
   is not "left alone" — it is **destroyed**.
2. **Lists and maps are replaced wholesale.**
   `aws_cognito_user_pool_client.logout_urls`, `callback_urls` and
   `aws_amplify_app.environment_variables` are not merged with what is live.
   Anything present in AWS and absent from `terraform.tfvars` is **deleted**.

Both hazards are silent in the plan *summary*. `0 to add, 2 to change,
0 to destroy` can still be removing two Amplify environment variables.

## Checklist

### Before planning

- [ ] **Plan from a worktree at `origin/main`.** The primary working directory
      is frequently on a feature or QA branch. On 2026-10-23 it was 34 commits
      behind `main` and its `.tf` files contained neither the Shibboleth IdP
      nor the CloudShell declarations; a plan run there reported
      `2 to add, 5 to change, 9 to destroy` — entirely an artifact of stale
      configuration.
      ```
      git -C <repo> worktree add -b <branch> <path> origin/main
      cp terraform/environments/dev/{terraform.tfvars,backend.hcl} <path>/terraform/environments/dev/
      ```
- [ ] Confirm `git rev-list --count HEAD..origin/main` is `0`.
- [ ] Refresh credentials with `buaws`. Confirm
      `aws sts get-caller-identity` shows account **770203350335**.
- [ ] Confirm `terraform init -backend-config=backend.hcl` does **not** offer
      to migrate or copy state.

### Reading the plan

- [ ] Run an **untargeted** plan. A `-target` plan cannot prove that nothing
      else moved, and proving that is the point.
- [ ] Read the plan **JSON**, not the summary:
      ```
      terraform show -json <plan> | jq -r '
        .resource_changes[]
        | select(.change.actions != ["no-op"])
        | "\(.change.actions|join(",")) \(.address)"'
      ```
- [ ] For every changing resource, diff the attributes — not just the name.
- [ ] For `logout_urls`, `callback_urls` and `environment_variables`,
      explicitly list what is **removed**, not only what is added. The
      expected removal count is almost always zero.

### Hard stops

Treat each of these as a **STOP**. Do not apply; reconcile the configuration
to live reality first, then replan.

- [ ] 🛑 **Any `delete` on `aws_cognito_identity_provider.*`.** This removes a
      federated login for every user. It is never a routine change. It
      requires explicit, named review and a written reason before anyone
      applies it — if the intent is not literally "decommission BU Web Login",
      the configuration is wrong, not the infrastructure.
- [ ] 🛑 Any `delete` or `replace` on `aws_cognito_user_pool`,
      `aws_cognito_user_pool_domain`, or `aws_cognito_user_group` — these
      destroy users, the Hosted UI domain, or group membership that
      attachment authorization depends on.
- [ ] 🛑 Any removal from `supported_identity_providers`.
- [ ] 🛑 Any Amplify environment variable in `before` but not `after`.
- [ ] 🛑 Any `delete`/`replace` on RDS, ECS task definitions or services, ECR
      lifecycle policies, Route 53 records, or the Subaward nightly schedule
      and its alarms.

### Before applying

- [ ] The plan matches what was reviewed, exactly, resource and attribute.
- [ ] Apply the **saved plan file**, not a re-plan. A saved plan binds to the
      state serial and refuses if anything moved underneath it.
- [ ] Note the rollback for the specific change before starting.

### After applying

- [ ] Read the changed values back from AWS with the CLI and compare against
      intent. The plan says what Terraform meant to do; only the API says what
      is true.
- [ ] If the change affects the UI build, remember that **changing an Amplify
      environment variable does not trigger a rebuild**. A merge to the
      tracked branch does.

## Protecting environment-specific values

`terraform/environments/dev/terraform.tfvars` is git-ignored
(`.gitignore:33`), so the reconciled values live on one machine and nothing
stops another developer planning from an incomplete copy. Options, with the
recommendation first:

| Option | Assessment |
|---|---|
| **AWS SSM Parameter Store** (recommended) — store the file as one `SecureString` per environment, e.g. `/research-archive-platform/dev/terraform.tfvars`, fetched into place before planning | Already in the account and in the trust boundary; versioned with history; IAM-controlled; no new system. Values here are not secrets but are environment-defining, and Parameter Store gives an authoritative copy with an audit trail |
| S3, beside the Terraform state | Same bucket already holds state, so no new trust boundary; weaker versioning ergonomics than SSM |
| Commit it to the repository | **Not recommended.** It would remove the drift risk entirely, but it puts account IDs, pool IDs and user `sub` values in a repo with two remotes, one of them a personal account |
| 1Password / shared vault | Workable but outside the AWS trust boundary and awkward to script |
| Leave as-is | **Not acceptable.** The 2026-10-23 near-miss is the direct consequence |

Whichever is chosen, the invariant is the same: **`terraform.tfvars` must be
reconciled against live AWS before it is trusted.** Before planning in an
environment you have not personally applied recently, read back the live
Cognito client, identity providers and Amplify environment variables and
compare. `docs/operations/TERRAFORM_DRIFT_RECONCILIATION.md` has the commands.

A longer-term fix worth considering separately: add `prevent_destroy` to
`aws_cognito_identity_provider.shibboleth`, so the hazard fails at plan time
rather than depending on a human reading carefully. That is a code change and
is deliberately **not** part of this documentation update.
