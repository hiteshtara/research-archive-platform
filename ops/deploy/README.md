# DEV API deployment — proposed, not applied

`dev-api.proposed.taskdef.json` differs from live revision **81** in exactly
one field: the container image, pinned **by digest**
(`sha256:ccfb5d598d9b…199d1`) rather than by tag, because the repository is
MUTABLE.

Everything else is byte-identical: cpu 512, memory 1024, `awsvpc`, both IAM
roles, port 8080, all 8 environment variables, all 5 Secrets Manager
references, and the log configuration. Revision 81 defines **no health
check**, and none is added.

**No authorization setting is added.** There is no `APP_AUTHORIZATION_*`
variable, so `enforcementEnabled` stays at the Java default `false` and every
gate remains inert — verified inside the ECR image's own jar, where neither
packaged `application.yml` nor `application-local.yml` contains an
`authorization` or `enforcement` key.

## ⚠️ Finding: the API connects as the database owner

`POSTGRES_USER` resolves from the **same secret the loader uses to run
migrations** — RDS master user **`archive_admin`** (`createdb`, `createrole`;
not superuser). So the API process holds owner rights over the whole
database, including the new `authz` schema.

This is **pre-existing** and not introduced by this deployment. It matters
more now than it did yesterday: `authz` holds the access-control data and an
append-only audit table whose protection is a trigger the owner could drop.
Least privilege would give the API a role with DML on `archive` and `authz`
and no DDL.

Changing it is **out of scope here** — it needs a new role, a secret, and its
own testing, and doing it as a side effect of a routine image bump would be
wrong. Recorded for a separate decision.

## Deploy (requires approval)

```bash
aws ecs register-task-definition \
  --cli-input-json file://ops/deploy/dev-api.proposed.taskdef.json
aws ecs update-service --cluster research-archive-platform-dev-api \
  --service research-archive-platform-dev-api \
  --task-definition research-archive-platform-dev-api:<new> --force-new-deployment
```

## Rollback — one command, no data involved

```bash
aws ecs update-service --cluster research-archive-platform-dev-api \
  --service research-archive-platform-dev-api \
  --task-definition research-archive-platform-dev-api:81 --force-new-deployment
```

Revision 81 stays ACTIVE. Rolling back the API needs **no** migration
rollback: the old image never reads the `authz` tables.

## Verifying authz row counts — needs the privileged role

`archive_analyst` has no `USAGE` on `authz`, so it cannot count rows in
`admin_role_member` or `access_grant`, and **must not be granted it merely to
verify**. The approved route is a read-only one-off task under the loader
role:

```bash
aws ecs run-task \
  --cluster research-archive-platform-dev-etl --launch-type FARGATE \
  --task-definition research-archive-platform-dev-loader:254 \
  --network-configuration 'awsvpcConfiguration={subnets=[subnet-00fba12ee73ff0e3b,subnet-0c5b92d15314b93ed],securityGroups=[sg-0817befcc5b4affc9],assignPublicIp=DISABLED}' \
  --overrides '{"containerOverrides":[{"name":"loader","command":["python3","-c",
    "import boto3, sqlalchemy as sa; from archive_etl.config.ecs import resolve_postgres_credentials; from archive_etl.upload.postgres import create_postgres_engine; import os; v=resolve_postgres_credentials(boto3.client(\"secretsmanager\")); os.environ.update({k:str(x) for k,x in v.items()}); e=create_postgres_engine(); c=e.connect(); print(\"migrations:\", [r[0] for r in c.execute(sa.text(\"SELECT version FROM public.schema_migration WHERE version>=82 ORDER BY version\"))]); print(\"admin_role_member:\", c.execute(sa.text(\"SELECT count(*) FROM authz.admin_role_member\")).scalar()); print(\"access_grant:\", c.execute(sa.text(\"SELECT count(*) FROM authz.access_grant\")).scalar())"
  ]}]}'
```

**SELECT only.** It is offered for approval, not run: every task execution in
this rollout has been separately approved, and a read-only query is no reason
to break that.
