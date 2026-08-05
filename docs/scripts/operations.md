# Script operations

These commands can affect shared dev infrastructure or data. Resolve the active
AWS identity first and prefer pinned image URIs for repeatable reruns.

## Get a Cognito access token

```bash
export ACCESS_TOKEN="$(scripts/get-access-token.sh developer@example.edu)"
```

The script prompts on an interactive terminal, places the password in a
mode-0600 temporary JSON file, deletes it on exit, and prints only the access
token to stdout. Defaults point to the dev user pool; use
`COGNITO_USER_POOL_ID`, `COGNITO_CLIENT_ID`, `COGNITO_USERNAME`, and
`AWS_REGION` to override identifiers and routing. Do not enable shell tracing or
paste the resulting token into logs.

## Open a dev database tunnel

Validate identity, endpoint, VPC, SSM instance, DNS, and the local port without
opening a session:

```bash
scripts/start-db-tunnel.sh --check-only
```

Then open the tunnel:

```bash
scripts/start-db-tunnel.sh
```

It defaults to AWS profile `bu-nprd`, BU dev account `770203350335`, region
`us-east-1`, and local port `15432`. An SSM-managed EC2 instance must already
exist with a route to RDS; the script never creates one. An explicit
`--instance-id` is verified, although a cross-VPC instance produces a warning
rather than a hard failure.

## Deploy to dev

For API-only identity and resource validation:

```bash
scripts/deploy-api.sh --check-only
```

A full API deployment builds the Maven artifact and linux/amd64 image, pushes
an immutable tag and `latest`, registers a new ECS task-definition revision,
updates the service, and waits for stability:

```bash
scripts/deploy-api.sh
```

For the broader developer pipeline:

```bash
scripts/dev-deploy.sh --check-only
scripts/dev-deploy.sh --full --no-push
```

`dev-deploy.sh` can run backend tests, frontend lint/build, delegate the API
deployment, verify health and Amplify, and run authenticated smoke checks when
`COGNITO_TEST_USERNAME` and `COGNITO_TEST_PASSWORD` are both set. Without
`--no-push`, the full workflow may push the current branch. Review `--help` and
the working tree before running it.

Both deployment scripts default to a specific dev account and resource names;
`EXPECTED_ACCOUNT_ID` changes only the account guard, not all environment-specific
resource names. They are therefore dev-oriented, not portable release tooling.

## Run Award ETL in ECS

Export the required infrastructure identifiers, using Terraform outputs or the
approved operations runbook rather than copying credentials into the shell:

```bash
export ECR_REPOSITORY_URI=ACCOUNT.dkr.ecr.us-east-1.amazonaws.com/REPOSITORY
export SUBNET_IDS=subnet-a,subnet-b
export SECURITY_GROUP_ID=sg-0123456789abcdef0
export POSTGRES_SECRET_ID=research-archive-platform/dev/postgres
export ORACLE_SECRET_ID=research-archive-platform/dev/oracle
```

Begin with a bounded dry run or read-only report:

```bash
scripts/run-award-loader.sh --load-award-id 209899 --dry-run
scripts/run-award-loader.sh --show-batch 1 --image-uri IMAGE_URI
```

Mutating modes include schema migration, creating batch records, and loading an
Award or batch. The wrapper also builds/pushes an image unless `--image-uri` is
supplied, and always registers a new task-definition revision before launching
Fargate. See the [ETL operations guide](../etl/operations.md) for data-grain and
reconciliation procedures.

## Run attachment ETL in ECS

Use the same infrastructure variables. Start with a bounded diagnostic:

```bash
scripts/run-award-attachment-loader.sh --show-upload-status --file-id 9001 --image-uri IMAGE_URI
scripts/run-award-attachment-loader.sh --file-id 9001 --dry-run --image-uri IMAGE_URI
```

A deterministic batch progresses through create, inspect, load, upload, and
inspect again:

```bash
scripts/run-award-attachment-loader.sh --create-batch 10 --image-uri IMAGE_URI
scripts/run-award-attachment-loader.sh --show-batch BATCH_ID --image-uri IMAGE_URI
scripts/run-award-attachment-loader.sh --load-batch BATCH_ID --image-uri IMAGE_URI
scripts/run-award-attachment-loader.sh --upload --batch-id BATCH_ID --bucket DOCUMENT_BUCKET --image-uri IMAGE_URI
scripts/run-award-attachment-loader.sh --show-batch BATCH_ID --image-uri IMAGE_URI
```

For bulk work, keep the state file on durable operator-controlled storage and
reuse the same path to resume:

```bash
scripts/run-award-attachment-loader.sh \
  --bulk-load 50000 \
  --bulk-batch-size 5000 \
  --upload \
  --state-file "$PWD/bulk-load-state.json" \
  --image-uri IMAGE_URI
```

Do not delete or reuse that state file for a different run until reconciliation
is complete. Use [`scripts/tests/test-bulk-load-reconciliation.sh`](../../scripts/tests/test-bulk-load-reconciliation.sh)
after changing bulk orchestration behavior.

## Query the archive in ECS

The Archive Explorer accepts predefined resources, not arbitrary SQL:

```bash
scripts/run-archive-explorer.sh award --award-number 100012-00002 --image-uri IMAGE_URI
scripts/run-archive-explorer.sh unit --unit-number 1203250000 --output json --image-uri IMAGE_URI
```

Queries are read-only, but the wrapper may build/push an image, register a task
definition, and launch an ECS task. It requires PostgreSQL, subnet, and security
group identifiers; it never requires the Oracle secret.

## Filter an Award child export

```bash
python3 scripts/filter_award_child_export.py \
  --parent /path/to/award_versions.csv \
  --child /path/to/award_child.csv
```

The utility retains child rows whose normalized `AWARD_ID` exists in the parent.
On its first run it copies the original child to `<child>.original`, writes a
temporary filtered file, then replaces the child in place. Subsequent runs do
not overwrite the original backup. Work on a copy and review the printed counts
and rejected-ID preview before moving the result into an ETL input set.
