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

Terraform owns the task definition, so rolling back by `update-service`
alone leaves config and reality disagreeing — the next apply would re-apply
the change. Prefer reverting the input:

```sh
# Remove api_database_secret_arn from terraform.tfvars, then:
terraform apply -target=module.api_service
```

For an **urgent** rollback, pin the service back and reconcile afterwards:

```sh
aws ecs update-service --cluster research-archive-platform-dev-api \
  --service research-archive-platform-dev-api \
  --task-definition research-archive-platform-dev-api:81 --force-new-deployment
```

Revision 81 carries the `archive_admin` credential and stays ACTIVE. If you
use this form, revert the tfvars change in the same session — otherwise the
next apply silently re-applies the switch.

`archive_api` itself can stay: an unused role grants nothing to anyone.

---

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
