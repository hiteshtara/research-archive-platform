# Identity enrollment — minimum required IAM

**Not applied.** This records the permission operator identity enrollment
needs, why it is the narrowest one that works, and the verified plan.

## The grant

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ReadOneUserForIdentityEnrollment",
      "Effect": "Allow",
      "Action": "cognito-idp:AdminGetUser",
      "Resource": "arn:aws:cognito-idp:us-east-1:770203350335:userpool/us-east-1_VJ4ekQ27c"
    }
  ]
}
```

One action. One pool. No wildcard in either field.

Attached as the inline policy
`research-archive-platform-dev-api-cognito-enrollment` on
`research-archive-platform-dev-api-task-role`.

## Why AdminGetUser alone is sufficient

An operator types an immutable `sub`. `AdminGetUser` takes a `Username`. Those
are usually *different* values in this pool, so the obvious reading is that a
subject-based lookup needs `ListUsers` with a `sub="..."` filter.

It does not. **Verified against the live DEV pool on 2026-11-03:**

| User kind | Count | Username | username == sub |
|---|---|---|---|
| Federated (`BUShibboleth_<name>@bu.edu`) | 6 | differs from sub | **no** |
| Native | 7 | is the sub | yes |

`AdminGetUser` was called with a **federated** user's `sub` in the `Username`
field and resolved it, returning the real `BUShibboleth_…` username. So
Cognito accepts a `sub` as a username alias, for federated users too.

`ListUsers` with a `sub` filter also works — and is **deliberately not
requested**, because it can enumerate the entire pool. Granting an
enumeration capability to obtain a single-record lookup that
`AdminGetUser` already provides would be a strictly larger permission for no
functional gain.

## Why the resolved user is re-checked

`CognitoIdentityProviderDirectory` compares the response's own `sub` attribute
to the requested subject and returns empty on mismatch. Both `Username` and
`sub` are unique within a pool, so this should be unreachable — which is why it
is asserted rather than assumed. Resolving a subject and then trusting whatever
came back would make the lookup a formality. It is the same rule
`ValidatedCognitoIdentity` already prescribes for the token path.

## Why failure is not absence

`UserNotFoundException` means the subject genuinely is not in the pool, and
maps to "absent" — a clean refusal. **Every other failure is rethrown**: no
permission, throttling, the wrong pool, a network fault.

If a missing permission returned "absent" instead, a revoked
`cognito-idp:AdminGetUser` would be reported to the operator as *"that subject
is not in the pool"* — a refusal for the wrong reason, and one they might try
to fix by picking a different subject. A tool error is not a negative result.

## Two boundaries, not one

IAM is the outer boundary. Inside it:

- The Spring bean is behind `@Profile("aws")`
  (`CognitoDirectoryConfiguration`), which only the grant-admin task sets
  (`SPRING_PROFILES_ACTIVE=aws,grant-admin`). No test context activates `aws`,
  so no test can reach Cognito.
- Absence of that bean yields `CognitoDirectory.UNAVAILABLE`, which **refuses a
  confirmed enrollment** and only warns for a dry run. Missing configuration
  degrades to "cannot verify", never to "verified".
- The adapter exposes exactly one public method, `findBySubject`, and never
  returns the SDK client — so nothing downstream can reach a Cognito write
  operation. Both properties are asserted by tests.

**The API service shares this task role with the grant-admin task.** That is
why the grant is narrowed to a single read action on a single pool: the running
service can reach it too, and `AdminGetUser` on one pool is the least that can
be reached.

## Verified plan

Read-only `terraform plan` from a worktree at `main` (`7442a8e`) with the
gitignored `backend.hcl` and `terraform.tfvars` copied in:

```
Plan: 1 to add, 0 to change, 0 to destroy.

  # module.api_service.aws_iam_role_policy.task_cognito_enrollment[0] will be created
      + name   = "research-archive-platform-dev-api-cognito-enrollment"
      + role   = "research-archive-platform-dev-api-task-role"
      + policy = {
          Action   = "cognito-idp:AdminGetUser"
          Effect   = "Allow"
          Resource = "arn:aws:cognito-idp:us-east-1:770203350335:userpool/us-east-1_VJ4ekQ27c"
          Sid      = "ReadOneUserForIdentityEnrollment"
        }
```

Nothing else. No destroy, no replacement, no change to any existing resource.

> **The first plan of this change was wrong, and re-running fixed it.** It
> reported `8 to add, 1 to change, 6 to destroy`, including
> `module.archive_s3.aws_s3_bucket.data has been deleted` and the replacement
> of six dependent S3 resources. The bucket was then checked directly with
> `aws s3api head-bucket`: **it exists**. The refresh had failed transiently
> and the provider read the failure as deletion. An immediate re-plan against
> the same state returned the clean single-resource plan above.
>
> So: a single `terraform plan` can report false drift, and applying that first
> plan would have replaced a live bucket's configuration. Re-run before
> believing a destructive plan, and verify the named resource against AWS. This
> is a second, distinct trap from the stale-branch one recorded in
> `docs/runbooks/GRANT_LIFECYCLE_ACCEPTANCE_TESTS.md`.

## Before applying

- [ ] Confirm `manage_cognito = true` in the live tfvars, so
      `local.cognito_user_pool_arn` resolves from `module.cognito[0]`. When it
      is false, the ARN is built from `var.cognito_user_pool_id`; if that is
      also null the policy is not created at all and a confirmed enrollment
      refuses — which is the safe direction, but it will look like a tooling
      failure if nobody expects it.
- [ ] Re-plan immediately before applying and confirm **1 to add, 0 to change,
      0 to destroy**.
- [ ] Apply with `-target=module.api_service.aws_iam_role_policy.task_cognito_enrollment`
      if any unrelated drift is present in the plan at that moment.
- [ ] Confirm afterwards:
      `aws iam get-role-policy --role-name research-archive-platform-dev-api-task-role
      --policy-name research-archive-platform-dev-api-cognito-enrollment`
- [ ] Then, and only then, run an enrollment **dry run** and confirm the pool
      check reports OK rather than WARN.

## Rollback

Remove the policy resource and apply, or
`aws iam delete-role-policy --role-name research-archive-platform-dev-api-task-role
--policy-name research-archive-platform-dev-api-cognito-enrollment`.

Enrollment then fails closed again: `UNAVAILABLE` refuses confirmed writes. No
identity link, grant or audit row is affected, because the permission is only
read.
