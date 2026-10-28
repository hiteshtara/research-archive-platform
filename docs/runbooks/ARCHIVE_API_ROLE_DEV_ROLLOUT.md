# DEV rollout: switching the API to the `archive_api` role

**Status: prepared, not executed.** Nothing in this document has been run.
As of 2026-10-28 DEV has roles `archive_admin` and `archive_analyst` only,
two project secrets, API service on `research-archive-platform-dev-api:81`,
and no `archive_api` anything.

Merged in PR #84 (`6971147`). The role script is
[`database/roles/archive_api_role.sql`](../../database/roles/archive_api_role.sql);
its design is [`ARCHIVE_API_DATABASE_ROLE.md`](../security/ARCHIVE_API_DATABASE_ROLE.md).

---

## 0. The thing that will bite you

**The API task definition and the execution role's secret policy are both
Terraform-managed, and neither has `ignore_changes`.**

Verified in `terraform/modules/api_service/main.tf`, not assumed:

| What | Where | Consequence |
|---|---|---|
| `base_secrets` builds all five `POSTGRES_*` from `var.database_secret_arn` | `main.tf:44-50` | a CLI-registered revision pointing elsewhere is **drift** |
| `container_secrets = merge(base_secrets, var.additional_secrets)` | `main.tf:52-55` | `additional_secrets` **overrides** the five — this is the supported hook |
| execution policy = `concat([var.database_secret_arn], local.additional_secret_arns)` | `main.tf:255-267` | a new ARN in `additional_secrets` is granted **automatically** |

So the §4 sketch in the design doc — hand-write an IAM policy, register a task
definition with the AWS CLI — is **wrong for this repository**. It would
work for about as long as it took someone to run the next
`terraform apply -target=module.api_service`, which would silently put
`POSTGRES_USER` back to `archive_admin`. There is a rollback guard for the
*image* (`expected_live_image`) and **none for the secrets**: the revert
would be silent, and the API would quietly regain owner privileges.

Do the secret repoint **in Terraform**.

Two further constraints, both previously established:

- Every apply in this environment must be `-target=module.api_service`.
  An untargeted apply destroys the approved read-only CloudShell DB path
  (3 `cloudshell_analyst` resources live in dev state but not in config).
- `terraform/environments/dev/terraform.tfvars` is **untracked** — only
  `.example` is in git. The operator's local tfvars is the real input.

---

## 0b. Terraform drift found — fix this BEFORE any apply

A complete untargeted plan was run against DEV with the live tfvars
(2026-10-28, read-only, no apply). It was **not** clean:

```
Plan: 0 to add, 0 to change, 9 to destroy.
```

All nine are the SSM DB tunnel stack — `aws_ecs_cluster.db_tunnel`,
`aws_ecs_task_definition.db_tunnel`, `aws_security_group.db_tunnel`,
`aws_vpc_endpoint.ssmmessages`, `aws_cloudwatch_log_group.db_tunnel`,
`aws_iam_role.db_tunnel_task`, `aws_iam_role_policy.db_tunnel_task_ssm`,
`aws_iam_policy.db_tunnel_operator`,
`aws_vpc_security_group_ingress_rule.database_from_db_tunnel` — each
*"because index [0] is out of range for count"*.

**Cause:** `var.manage_db_tunnel_access` defaults to `false` and is absent
from the live `terraform.tfvars`, while all nine resources exist in AWS and
in state. They were created by an apply whose toggle was never written back.
This is pre-existing drift, unrelated to `archive_api`.

**Consequence:** an untargeted apply would destroy the DBeaver / psql access
path to dev RDS — including the one used to verify this very rollout.

**Fixed 2026-10-28 — reconciled, not hidden behind `-target`:**

```hcl
# terraform/environments/dev/terraform.tfvars  (untracked)
manage_db_tunnel_access = true
```

Added to the operator's local tfvars (backed up first, outside the repo —
a `.bak` beside it would **not** be gitignored). Re-planned: the nine
destroys are gone. The file remains untracked and unstageable.

### The one change that remains, and why it is expected

```
Plan: 1 to add, 0 to change, 1 to destroy.
# aws_ecs_task_definition.db_tunnel[0] must be replaced
```

The tunnel task definition deliberately reads its image **from the live
loader task definition** rather than composing one from a tag — see the
comment at `db_tunnel.tf:218-225`, which exists because `loader_image_tag`
is unset and `latest` does not exist in that repository. Loader revision
254 was registered on 2026-10-28 (the `linux/amd64` rebuild), so the data
source now returns
`…dev-loader@sha256:983bab23…` (tag `20261028T163450Z-cead763-amd64`)
instead of the digest behind `20260923T163946Z-443e507`.

So this is the configuration working as designed, following a deliberate
image change forward. It replaces an **ephemeral** task definition that is
launched per session.

**Being fixed separately in PR #86**, which adds a digest-only
`var.db_tunnel_image` so the tunnel stops tracking the loader. That PR also
records a sharper problem found while preparing it: the loader repository's
lifecycle policy keeps only the newest 20 images, and the image the tunnel
runs today is #21 of 27 — about four more loader builds before it is swept
and the tunnel fails to pull. See
[`DB_TUNNEL_IMAGE_PINNING.md`](DB_TUNNEL_IMAGE_PINNING.md).

Until that lands, this one replacement is expected and harmless.

### Baseline for this rollout

```
terraform plan                            →  1 to add, 0 to change, 1 to destroy
                                             (the tunnel task definition only)
terraform plan -target=module.api_service →  No changes.
```

Confirmed from the plan JSON, 2026-10-28: exactly **one** resource has a
planned action. CloudShell, Cognito, Shibboleth, RDS, the Subaward
scheduler, Amplify, Route53 and `module.api_service` all have none.

`api_image_tag` already matches what is live, so the image rollback guard
will not block the plan, and the secrets repoint will be the **only**
difference this rollout introduces.

---

## 1. Generate the credential and create the secret

Never echoed, never in `argv`, never in shell history.

```sh
export AWS_PROFILE=bu-nprd
aws sts get-caller-identity --query Account --output text   # expect 770203350335

# Read the non-secret connection fields from the existing secret.
eval "$(aws secretsmanager get-secret-value \
  --secret-id research-archive-platform/dev/postgres \
  --query SecretString --output text \
  | python3 -c 'import sys,json; d=json.load(sys.stdin); print(f"H={d[\"host\"]}; P={d[\"port\"]}; D={d[\"dbname\"]}")')"

# 32 bytes of CSPRNG, base64, never printed.
python3 - "$H" "$P" "$D" <<'PY' > /dev/null
import base64, json, os, subprocess, sys
host, port, dbname = sys.argv[1:4]
secret = {
    "username": "archive_api",
    "password": base64.urlsafe_b64encode(os.urandom(32)).decode().rstrip("="),
    "host": host, "port": port, "dbname": dbname,
}
subprocess.run(
    ["aws", "secretsmanager", "create-secret",
     "--name", "research-archive-platform/dev/postgres-api",
     "--description", "Least-privilege API database role (archive_api)",
     "--secret-string", "file:///dev/stdin"],
    input=json.dumps(secret), text=True, check=True, stdout=subprocess.DEVNULL)
PY
```

`--secret-string file:///dev/stdin` keeps the password out of `argv`, where
`create-secret --secret-string "$PASSWORD"` would put it.

Record the returned ARN — Terraform needs it. **Expect a random 6-character
suffix** (`…postgres-api-XXXXXX`), as on the existing secret.

---

## 2. Create the role

The script is idempotent and never resets an existing role's password.

```sh
export PGPASSWORD="$(aws secretsmanager get-secret-value \
  --secret-id research-archive-platform/dev/postgres \
  --query SecretString --output text \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["password"])')"

psql "postgresql://archive_admin@127.0.0.1:15432/research_archive?sslmode=require" \
  -v archive_api_password="$(aws secretsmanager get-secret-value \
      --secret-id research-archive-platform/dev/postgres-api \
      --query SecretString --output text \
      | python3 -c 'import sys,json; print(json.load(sys.stdin)["password"])')" \
  -f database/roles/archive_api_role.sql
```

via `scripts/start-db-tunnel.sh` (port 15432). **`scripts/stop-db-tunnel.sh`
when finished** — the tunnel is a running Fargate task.

`-v` is a psql variable, not a shell argument to a program that logs: it does
not appear in `ps`. Both command substitutions run in a subshell and are
never assigned to a printed variable.

---

## 3. Validate privileges before touching the service

Run as `archive_api` itself. This is the same boundary the Testcontainers
suite asserts; here it is being confirmed against real DEV.

```sql
-- expect: t
SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole
       AND NOT rolreplication AND NOT rolbypassrls AND rolcanlogin AS ok
  FROM pg_roles WHERE rolname = 'archive_api';

-- expect: 0
SELECT count(*) FROM pg_class c JOIN pg_roles r ON r.oid = c.relowner
 WHERE r.rolname = 'archive_api';

-- expect: each succeeds
SELECT count(*) FROM archive.award_version;
SELECT count(*) FROM authz.identity_link;

-- expect: each raises "permission denied"
INSERT INTO archive.person (person_id) VALUES ('X');
DELETE FROM authz.access_grant;
UPDATE authz.access_audit SET actor = 'x';
SELECT * FROM public.schema_migration;
CREATE TABLE authz.sneaky (x int);
```

Roll back the probe transaction. Do not proceed if any line behaves
differently from the suite.

---

## 3b. What the existing roles keep

`archive_api_role.sql` contains no `ALTER ROLE`, `REVOKE` or `DROP`
touching any role but `archive_api` — grep it. Specifically:

| Role | Used for | Effect of this rollout |
|---|---|---|
| `archive_admin` | ETL migrations (`--migrate-only`), schema ownership | **none.** Still the owner; still the only role that can run migrations. The loader keeps its own secret and its own task definition |
| `archive_analyst` | DBeaver / psql read-only over the SSM tunnel | **none.** Not referenced by the script at all |
| `archive_api` | the API, and nothing else | created |

The one interaction worth stating: `ALTER DEFAULT PRIVILEGES FOR ROLE
archive_admin IN SCHEMA archive` changes what *future* `archive_admin`-created
tables grant to `archive_api`. It grants `archive_admin` nothing and takes
nothing away.

---

## 4. Repoint the API — in Terraform

In `terraform/environments/dev/main.tf`, the `module "api_service"` block:

```hcl
  additional_secrets = merge(
    var.enable_openai_secret ? { OPENAI_API_KEY = "...unchanged..." } : {},
    {
      POSTGRES_HOST     = "${var.api_database_secret_arn}:host::"
      POSTGRES_PORT     = "${var.api_database_secret_arn}:port::"
      POSTGRES_DB       = "${var.api_database_secret_arn}:dbname::"
      POSTGRES_USER     = "${var.api_database_secret_arn}:username::"
      POSTGRES_PASSWORD = "${var.api_database_secret_arn}:password::"
    },
  )
```

with a new `var.api_database_secret_arn`, set in the operator's untracked
`terraform.tfvars` to the ARN from step 1.

This needs **no module change**: `merge` puts `additional_secrets` last, so
these five override `base_secrets`, and the execution role picks up the new
ARN automatically through `local.additional_secret_arns`.

### Resulting ECS container-definition diff

Only the five `valueFrom` values change. No image change, no environment
change, no command change, no role change:

```diff
   "secrets": [
-    {"name":"POSTGRES_DB",      "valueFrom":".../dev/postgres-4k6Ngz:dbname::"},
-    {"name":"POSTGRES_HOST",    "valueFrom":".../dev/postgres-4k6Ngz:host::"},
-    {"name":"POSTGRES_PASSWORD","valueFrom":".../dev/postgres-4k6Ngz:password::"},
-    {"name":"POSTGRES_PORT",    "valueFrom":".../dev/postgres-4k6Ngz:port::"},
-    {"name":"POSTGRES_USER",    "valueFrom":".../dev/postgres-4k6Ngz:username::"}
+    {"name":"POSTGRES_DB",      "valueFrom":".../dev/postgres-api-XXXXXX:dbname::"},
+    {"name":"POSTGRES_HOST",    "valueFrom":".../dev/postgres-api-XXXXXX:host::"},
+    {"name":"POSTGRES_PASSWORD","valueFrom":".../dev/postgres-api-XXXXXX:password::"},
+    {"name":"POSTGRES_PORT",    "valueFrom":".../dev/postgres-api-XXXXXX:port::"},
+    {"name":"POSTGRES_USER",    "valueFrom":".../dev/postgres-api-XXXXXX:username::"}
   ]
```

`image` stays `…dev-api:20261006T182647Z-b10e4e3`, the revision 81 image.
**Record-level enforcement stays OFF** — it is not set here, and its
property defaults to `false`.

And the IAM policy gains one ARN:

```diff
   "Resource": [
       ".../secret:research-archive-platform/dev/postgres-4k6Ngz"
+  ,   ".../secret:research-archive-platform/dev/postgres-api-XXXXXX"
   ]
```

Both ARNs are present during cutover — deliberate, so rollback needs no IAM
change. Narrowing to the new one alone is §7, after the soak.

```sh
terraform plan -target=module.api_service
```

**Expect exactly two changes**: `aws_ecs_task_definition.api` replaced (new
revision), `aws_iam_role_policy.execution_secret_access` updated (second ARN
added). Anything else — especially anything destroyed — stop.

Note `api_image_tag` must already be pinned to whatever is actually running,
or the rollback guard will refuse the plan. That guard is about the image and
says nothing about secrets.

---

## 5. Deploy and verify

```sh
terraform apply -target=module.api_service
aws ecs wait services-stable --cluster research-archive-platform-dev-api \
  --services research-archive-platform-dev-api
```

Then, in order:

1. `GET /actuator/health` → `UP`.
2. An Award search, a Proposal search, a Dashboard load — plain reads.
3. Confirm the container is actually on the new role — the decisive check,
   because every test above passes just as well on the old credential:
   ```sql
   SELECT usename, count(*) FROM pg_stat_activity
    WHERE datname = 'research_archive' GROUP BY usename;
   ```
   Expect `archive_api` connections and **no new** `archive_admin` ones.
4. Record-level enforcement stays **OFF**. Admin endpoints are expected to
   404 while it is off; that is unchanged behaviour, not a regression.

---

## 6. Rollback

> **Corrected 2026-10-28.** Earlier revisions of this document said
> "revision 81 keeps the `archive_admin` credential and stays ACTIVE."
> **That was an assumption, and it is wrong by default.** Replacing an
> `aws_ecs_task_definition` *deregisters* the superseded revision —
> demonstrated when pinning the DB tunnel left its revision 1 `INACTIVE`,
> and an `INACTIVE` revision cannot be run. Applied unchanged, the
> secrets repoint would have deregistered revision 81 and destroyed the
> rollback target this document names.

### Prerequisite 1 — make revision 81 survive: **DONE 2026-10-28**

`skip_destroy = true` on `aws_ecs_task_definition.api` keeps superseded
revisions `ACTIVE`. Verified by plan to be an **in-place update**, not a
replacement, so it registers no new revision and changes nothing running:

```
module.api_service.aws_ecs_task_definition.api will be updated in-place
Plan: 0 to add, 1 to change, 0 to destroy.
```

**Apply it before the credential cutover, not with it** — two changes in
one apply means a failure is not attributable.

### Prerequisite 2 — protect the known-good image: **DONE 2026-10-28**

`modules/api_ecr` had only a "keep the latest 20" rule and **no
`protected-*` exemption**, unlike the loader repository — which gained one
after the Subaward nightly sync missed 14 consecutive runs
(2026-08-18..09-01) because the image its pinned task definition
referenced had been swept. The API repository is exposed to exactly that:
measured 2026-10-28 the running image is **#21 of 27** by push date,
roughly **five more API builds** of headroom.

**A task definition does not protect its image.** If the image is swept,
no rollback to it is possible however many revisions stay `ACTIVE`.

Add the rule, then tag the image **byte-exactly** — see
[`DB_TUNNEL_IMAGE_PINNING.md`](DB_TUNNEL_IMAGE_PINNING.md) for the
`put-image` trap that otherwise stores a second image and tags the wrong
one.

```
protected-20261006T182647Z-b10e4e3
  -> sha256:4b74cdbc2fc7ed3bf47260f53031df6a5c0accfa3c06607c63fa8ccb37216ce3
```

**Both prerequisites are applied** (PR #88, merged `b97d990`):

| | |
|---|---|
| `skip_destroy` | `false → true`, in place; revision stayed **81**, service never redeployed |
| API ECR policy | priority 1 `protected-*` (36500 days), priority 2 keep-20 |
| Protected tag | `protected-20261006T182647Z-b10e4e3` → `sha256:4b74cdbc…` |
| Duplicate manifest | none — repository still 27 entries |
| Lifecycle preview | 4 images expire, all rule 2; **the protected image is excluded** |
| Follow-up plan | **No changes** |
| API throughout | `dev-api:81`, 1/1, task running since 2026-10-22 (never restarted), `/actuator/health` → 200 `{"status":"UP"}`, ALB target healthy |

Rollback A is therefore the live path: revision 81 is `ACTIVE` and its
image is protected from the lifecycle sweep. Rollback B remains documented
for the case where some future replacement predates `skip_destroy`.

### Known-good baseline, recorded 2026-10-28

| | |
|---|---|
| Service | `research-archive-platform-dev-api`, FARGATE, platform `LATEST`, desired 1, running 1 |
| Task definition | `research-archive-platform-dev-api:81`, ACTIVE |
| Image (tag) | `…/research-archive-platform-dev-api:20261006T182647Z-b10e4e3` |
| Image (**digest**) | `sha256:4b74cdbc2fc7ed3bf47260f53031df6a5c0accfa3c06607c63fa8ccb37216ce3` |
| cpu / memory | 512 / 1024, `awsvpc`, `X86_64` / `LINUX` |
| Execution role | `research-archive-platform-dev-api-execution-role` |
| Task role | `research-archive-platform-dev-api-task-role` |
| Container | `api`, essential, port 8080/tcp, no health check |
| Secrets | 5 × `…/dev/postgres-4k6Ngz:{dbname,host,password,port,username}::` |
| Environment | `SPRING_PROFILES_ACTIVE=aws`, `COGNITO_ISSUER_URI`, `COGNITO_CLIENT_ID`, `ARCHIVE_DOCUMENTS_BUCKET`, `APP_CORS_ALLOWED_ORIGINS`, `APP_EXPLORER_ENABLED=true`, `APP_SEARCH_SEMANTIC_ENABLED=true`, `APP_SEARCH_TIMING_ENABLED=false` |
| Enforcement | **no** authorization or enforcement variable is set — off by property default |

The tag is **mutable** and `20261006T182647Z-b10e4e3` also carries
`latest`, which moves. Any rollback must use the **digest**.

### Rollback A — preferred, revision 81 still ACTIVE

```sh
aws ecs update-service --cluster research-archive-platform-dev-api \
  --service research-archive-platform-dev-api \
  --task-definition research-archive-platform-dev-api:81 --force-new-deployment
aws ecs wait services-stable --cluster research-archive-platform-dev-api \
  --services research-archive-platform-dev-api
```

### Rollback B — revision 81 already INACTIVE

An `INACTIVE` revision cannot be run, but it can still be **read**, so a
fresh one can be registered from it rather than retyped from this table:

```sh
aws ecs describe-task-definition --task-definition research-archive-platform-dev-api:81 \
  --query 'taskDefinition' > /tmp/td81.json

python3 - <<'EOF'
import json
td = json.load(open("/tmp/td81.json"))
for k in ("taskDefinitionArn","revision","status","requiresAttributes",
          "compatibilities","registeredAt","registeredBy","deregisteredAt"):
    td.pop(k, None)
# Pin by digest - the tag is mutable and also carries "latest".
td["containerDefinitions"][0]["image"] = (
    "770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-api"
    "@sha256:4b74cdbc2fc7ed3bf47260f53031df6a5c0accfa3c06607c63fa8ccb37216ce3")
json.dump(td, open("/tmp/td-rollback.json","w"), indent=2)
EOF

aws ecs register-task-definition --cli-input-json file:///tmp/td-rollback.json
aws ecs update-service --cluster research-archive-platform-dev-api \
  --service research-archive-platform-dev-api \
  --task-definition research-archive-platform-dev-api:<new revision> --force-new-deployment
```

It differs from 81 in one respect — the image is addressed by digest
rather than tag — which is deliberate and strictly safer. Rollback B
depends on the image still existing, which is why prerequisite 2 is a
prerequisite and not a nicety.

### Reconciling Terraform and ECS afterwards

Either rollback leaves the service pointing somewhere Terraform did not
put it. Close that drift:

1. Remove `api_database_secret_arn` from the untracked `terraform.tfvars`.
2. `terraform plan -target=module.api_service` → **No changes**.
3. Confirm the service is on the intended revision:
   ```sh
   aws ecs describe-services --cluster research-archive-platform-dev-api \
     --services research-archive-platform-dev-api --query 'services[0].taskDefinition'
   ```
4. Confirm the container is back on the owner credential:
   ```sql
   SELECT usename, count(*) FROM pg_stat_activity
    WHERE datname = 'research_archive' GROUP BY usename;
   ```

Skipping step 1 is the trap: the service would be rolled back while the
configuration still says otherwise, and the next apply would silently
re-apply the switch.

## 7. After a clean soak

Only then narrow the owner-secret access. This **does** need a module change,
because `var.database_secret_arn` is unconditionally in the policy's
`resources`. Treat it as separate, reviewed work — not part of this rollout.

---

## Credential-handling rules observed throughout

| Rule | How |
|---|---|
| Never in git | the password is a psql variable; the script contains no literal |
| Never in Terraform state | the secret is created by CLI; Terraform only ever sees the **ARN** |
| Never in `argv` | `--secret-string file:///dev/stdin`; psql `-v`; no `-c "...password..."` |
| Never in logs | no `echo`; `create-secret` stdout discarded |
| Never in shell history | values only ever inside `$( )` |
