# DEV API deployment, after the five PRs merge

Prepared, not executed. Nothing here has been run, and step 0 is a hard gate.

Deploying this release **does not activate authorization**. No
`APP_AUTHORIZATION_*` variable is added at any point below; enforcement is a
separate, separately-approved change (approval 5 in
[`../security/DEV_ACTIVATION_APPROVAL_CHECKLIST.md`](../security/DEV_ACTIVATION_APPROVAL_CHECKLIST.md)).

## Step 0 — the gate

| Check | Required |
|---|---|
| All five PRs merged to `main` | yes |
| The merge commit recorded | `git rev-parse origin/main` — this is the commit being released |
| Deployment approved | approval 1 |

Build from a **clean detached worktree at that exact commit**, never from a
live working directory. A working directory can contain an untracked
migration or a half-finished edit, and the image would carry it without
anything saying so.

```
git worktree add --detach /tmp/rap-release <merge-commit>
cd /tmp/rap-release
```

## Step 1 — build, push, deploy

```
AWS_PROFILE=bu-nprd ops/deploy-api.sh --check-only   # identity, region, resources
AWS_PROFILE=bu-nprd ops/deploy-api.sh
```

**What it preserves, verified by reading it rather than assumed:** it reads
the task definition the service is currently running, changes **only**
`containerDefinitions[0].image`, and strips just the AWS-managed metadata
(`taskDefinitionArn`, `revision`, `status`, `requiresAttributes`,
`compatibilities`, `registeredAt`, `registeredBy`, `tags`,
`deregisteredAt`) before re-registering. Every environment variable, secret
reference, CPU and memory setting, network configuration and IAM role carries
over untouched.

It aborts before any Docker login, push, registration or service update if
the account, region or resources are wrong — the account is resolved from
`aws sts get-caller-identity` every run and checked, because an earlier
version hardcoded a personal account and deployed there silently.

## Step 2 — re-pin the task definition by DIGEST  ⚠️ not automatic

**This is the step that is easy to miss, and it has been missed before.**

`deploy-api.sh` registers the task definition referencing the image **by
tag**:

```
task_def['containerDefinitions'][0]['image'] = '<repo>:<IMAGE_TAG>'
```

The ECR repository is `imageTagMutability=MUTABLE`, so that tag can later be
moved to different bytes and the task definition would pull different code on
its next task start. This is exactly what happened at revision 86: it was
registered by tag, and revision 87 exists solely to re-pin the same image by
digest.

So after step 1:

```
DIGEST=$(AWS_PROFILE=bu-nprd aws ecr describe-images \
  --repository-name research-archive-platform-dev-api \
  --image-ids imageTag="$IMAGE_TAG" \
  --query 'imageDetails[0].imageDigest' --output text)
```

Register one further revision with `...dev-api@${DIGEST}` in place of the
tag, and update the service to it. The service then runs an **immutable**
reference.

Confirm:

```
AWS_PROFILE=bu-nprd aws ecs describe-services \
  --cluster research-archive-platform-dev-api \
  --services research-archive-platform-dev-api \
  --query 'services[0].taskDefinition' --output text
# then describe that revision and check the image contains "@sha256:"
```

## Step 3 — synchronise the operator task definitions

```
AWS_PROFILE=bu-nprd ops/sync-operator-taskdefs.sh
```

It reads the image the **service** is running — not the latest revision of
the family, which can differ — rewrites both committed definitions, registers
nothing, and warns if `securityReadiness.json` still records a different
digest.

Then refresh `securityReadiness.json`'s deployment block
(`apiTaskDefinition`, `imageCommit`, `imageDigest`, `verifiedAt`), review the
diff, and commit both changes together. That refresh is **part of the
deployment, not a follow-up**: `OperatorTaskDefinitionTest` derives the
expected digest from it.

Confirm:

```
cd api && mvn test -Dtest=OperatorTaskDefinitionTest
AWS_PROFILE=bu-nprd scripts/verify-grant-admin-taskdef.sh ops/grant-admin/dev-grant-admin.taskdef.json
AWS_PROFILE=bu-nprd scripts/verify-grant-admin-taskdef.sh ops/grant-admin/dev-link-identity.taskdef.json
```

Both scripts exit non-zero on a mismatch and name the two digests. Verified by
pointing a definition at the old digest and watching it refuse.

**Do not register or run an operator task definition until this step is
done.** A definition left behind starts a container that runs older CLI code
against today's database, and nothing else would say so.

## Step 4 — verify the deployment, without enabling anything

| Check | Expected |
|---|---|
| Service stable | `deployments[0].status = PRIMARY`, running = desired |
| Image reference | contains `@sha256:` |
| `APP_AUTHORIZATION_*` | **absent** — enforcement is not part of this |
| Global Search for "NSF" | NSF-sponsored Awards as **direct** matches; no "Material Transfer Agreement" rows |
| Global Search result count | "Showing N of M matching records" with a per-type breakdown |
| A Negotiation card | reads `Negotiation <id> · Document <number>` |
| A capped record type | offers "see all …" linking to that module's own search |
| QA Testing page, TC-041 | shows the reopened notice; TC-040 still passes |

The search checks are the user-visible half of #124 and #125 and are worth
doing in the browser, because the measured corpus numbers predict them: Award
titles matching "NSF" should drop from **298** to **107**, and Negotiation
agreement-type matches from **4,953** to **0**.

## Rollback

```
AWS_PROFILE=bu-nprd aws ecs update-service \
  --cluster research-archive-platform-dev-api \
  --service research-archive-platform-dev-api \
  --task-definition research-archive-platform-dev-api:<previous revision>
```

One service deployment. Task definition revisions are immutable and retained,
so the previous one is still exactly what it was. **No data is written by any
step above**, no migration is applied and no schema changes, so there is
nothing else to undo.

If the operator definitions were already re-registered at the new digest,
deregister those revisions too — otherwise they point at an image the service
is no longer running, which is the drift this release exists to prevent,
pointing backwards.

## The UI is a separate deployment

Amplify auto-builds from **`origin`** (the personal remote), not `bu`. A
commit pushed only to `bu` is invisible to it. Confirm the merge commit exists
on `origin` before expecting a build, and check the job's commit matches.
