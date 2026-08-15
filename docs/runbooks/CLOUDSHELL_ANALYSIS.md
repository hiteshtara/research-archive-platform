# CloudShell VPC analysis: read-only PostgreSQL access, zero new infra cost

Immediate human/read-only data-analysis access to dev RDS PostgreSQL
using an AWS CloudShell **VPC environment** session - no EC2 instance,
no VPC interface endpoints, no NAT gateway, RDS never made publicly
accessible. This is the immediate option while direct local-Mac access
depends on a BU networking change - see
`docs/runbooks/VPN_RDS_CONNECTIVITY_INVESTIGATION.md` for why a local
DBeaver/VPN connection doesn't work today and can't be fixed with a
single database firewall rule.

Not an ETL execution path - ECS Fargate
(`scripts/run-award-loader.sh` and friends) remains that, unchanged.

**Status: applied and live** (2026-08-14) - `terraform state list` shows
all three resources; live security-group rules verified directly via
`aws ec2 describe-security-groups` (see below).

## The security boundary this design is built around

The CloudShell-analyst security group's egress is scoped to **exactly
one destination: RDS on 5432**. It cannot reach Secrets Manager, SSM,
STS, GitHub, or anything else - a CloudShell VPC environment attached to
it has no network access beyond a raw TCP path to Postgres. This means:

- **Every AWS API call in both workflows below runs on the Mac**, with
  `AWS_PROFILE=bu-nprd` (these are public AWS control-plane endpoints,
  reachable over the normal internet - no VPN or VPC access needed for
  Secrets Manager/SSM calls themselves, only for reaching RDS's private
  IP directly, which the Mac cannot do - see
  `docs/runbooks/VPN_RDS_CONNECTIVITY_INVESTIGATION.md`).
- **CloudShell cannot `git clone` or otherwise fetch this repository** -
  do not assume `database/analysis-role/create_archive_analyst_role.sql`
  or any other repo file exists inside the CloudShell session. The only
  way to get its content there is the Mac's own clipboard, shared with
  the browser CloudShell runs in.
- **CloudShell only ever receives a password through psql's own
  interactive, hidden password prompt** - pasted by you from the Mac's
  clipboard, never fetched by a script, never an environment variable a
  script sets, never a command-line argument, never written to a file.
  Interactive prompts aren't recorded in shell history either.
- Every Mac-side script below uses `pbcopy` - it never prints a password
  to the terminal - and prompts you to confirm the paste before clearing
  the clipboard (`printf '' | pbcopy`).

Terraform created exactly **three** resources - a security group and
its two rules - nothing else:

- `aws_security_group.cloudshell_analyst[0]` (`sg-002be83bf1cf249fa`)
- `aws_vpc_security_group_egress_rule.cloudshell_analyst_to_database[0]`
- `aws_vpc_security_group_ingress_rule.database_from_cloudshell_analyst[0]`

Live-verified (`aws ec2 describe-security-groups`, 2026-08-14):

- CloudShell-analyst SG: **zero ingress rules**, **exactly one egress
  rule** - TCP 5432 to the RDS security group
  (`sg-0ded3637b04384f60`). The explicit `ingress = []` / `egress = []`
  in the Terraform resource is what made Terraform revoke AWS's implicit
  default "allow-all-outbound" rule on creation - confirmed no such rule
  exists.
- RDS SG: gained exactly one new ingress rule, from the CloudShell-
  analyst SG on 5432, alongside its two pre-existing rules (ECS loader,
  API) - unchanged otherwise.

The analyst password itself is **never in Terraform state** - it only
ever exists in SSM Parameter Store (Standard SecureString,
`alias/aws/ssm`) and in your terminal's momentary clipboard.

## Cost

CloudShell (including VPC environments) is a free AWS service - $0
fixed monthly charge; a bare ENI with no other resource behind it has
no hourly cost either. SSM Standard-tier SecureString with
`alias/aws/ssm`: $0 fixed monthly charge (no Standard-tier storage fee,
no $1/month key fee - that only applies to customer-managed KMS keys).
The one honest caveat: SSM's internal KMS encrypt/decrypt calls share
the account's pooled 20,000-free-requests/month KMS tier, then
$0.03/10,000 - for occasional analyst credential fetches this is $0.00
in practice, not a literal zero-under-all-circumstances guarantee.

## Prerequisites

- Applied (see Status above).
- `AWS_PROFILE=bu-nprd`, account `770203350335`, run on the **Mac** for
  every script below (the `Shibboleth-InfraMgt` role already has
  `AdministratorAccess`, so no additional IAM grant is needed).
- macOS with `pbcopy` (all Mac-side scripts require it).

## One-time: create the `archive_analyst` role

**Step 1 - Mac:**

```bash
scripts/mac-show-rds-master-password.sh
```

Copies the `archive_admin` (master) password to your clipboard (never
printed) and prints the psql connection command. Confirm at the prompt
once you've pasted it, to clear the clipboard.

**Step 2 - CloudShell** (in a VPC environment session - console:
CloudShell -> Actions -> "Create VPC environment", subnet = either
private subnet from `terraform output private_subnet_ids`, security
group = `terraform output cloudshell_analyst_security_group_id`
(`sg-002be83bf1cf249fa`); or `aws cloudshell create-environment
--vpc-config subnetIds=...,securityGroupIds=sg-002be83bf1cf249fa`).
Install `psql` if needed (`sudo yum install -y postgresql15`), then:

```bash
psql "host=research-archive-platform-dev-postgres.clb9d4mkglfd.us-east-1.rds.amazonaws.com port=5432 dbname=research_archive user=archive_admin sslmode=require"
# Password for user archive_admin: <paste from step 1, Cmd-V>
```

**Step 3 - Mac** (second terminal, or after finishing step 2's paste):

```bash
scripts/mac-copy-analyst-role-sql.sh
```

Copies `database/analysis-role/create_archive_analyst_role.sql`'s
content to your clipboard (it contains no password - `\password` below
prompts separately).

**Step 4 - CloudShell** (same psql session from step 2): paste
(Cmd-V) the SQL directly at the `research_archive=>` prompt. It runs
`CREATE ROLE archive_analyst WITH LOGIN;`, then pauses at `\password
archive_analyst` and prompts twice, hidden, for the new password -
switch to a Mac terminal and run:

```bash
scripts/mac-generate-analyst-password.sh
```

This generates a strong, alphanumeric-only password, stores it in SSM
immediately, and copies it to your clipboard. Paste it (Cmd-V) into
both of CloudShell's `Enter new password:` / `Enter it again:` prompts,
then confirm at the Mac script's own prompt to clear the clipboard. The
rest of the pasted SQL then runs automatically: the read-only grants
(`CONNECT`/`USAGE`/`SELECT` on `archive`, `default_transaction_read_only
= on`, a default-privilege grant covering future tables). No write,
DDL, ownership, replication, or application-role permissions of any
kind. `\password` safely parameterizes the value server-side - the SQL
file never interpolates the password into a string itself.

## Normal connection workflow

1. `buaws` (refresh AWS access on the Mac).
2. `scripts/mac-show-analyst-password.sh` (Mac) - fetches the stored
   `archive_analyst` credential from SSM and copies the password to
   your clipboard. Never generates a new password - see "One-time" above
   for that.
3. Connect from the `research-archive-analysis` CloudShell VPC
   environment session with the psql command the script prints; paste
   the password (Cmd-V) at its hidden prompt.
4. Verify `current_user`/`transaction_read_only = on` (see "Proving
   writes are denied" below).
5. Return to the Mac and press Enter at the helper's prompt to clear
   the clipboard.

See `docs/runbooks/CLOUDSHELL_DATABASE_ACCESS.md` for the full
step-by-step walkthrough (Bash vs. psql prompts, troubleshooting) and a
query cookbook (schema/FK/constraint inspection, per-domain attachment
architecture, Negotiation integrity queries) - this doc stays the
canonical source for the architecture/setup rationale and the one-time
role-creation steps, so as not to maintain two conflicting procedures.

**Mac:**

```bash
scripts/mac-show-analyst-password.sh
```

Copies the stored `archive_analyst` password to your clipboard (never
printed) and prints the psql connection command. Confirm at the prompt
once you've pasted it, to clear the clipboard. Hardened 2026-08-15 - see
"Password-helper incident" below.

**CloudShell** (VPC environment session):

```bash
psql "host=research-archive-platform-dev-postgres.clb9d4mkglfd.us-east-1.rds.amazonaws.com port=5432 dbname=research_archive user=archive_analyst sslmode=require"
# Password for user archive_analyst: <paste, Cmd-V>
```

Or, for a single approved research query without opening an interactive
`psql` session (note: `scripts/run-cloudshell-analyst-query.sh` itself
must also be pasted into CloudShell via clipboard the first time, or
typed directly, since the repo isn't there - it's short enough to paste
in full, or reproduce the one `psql ... -c "$QUERY"` line manually):

```bash
psql "host=research-archive-platform-dev-postgres.clb9d4mkglfd.us-east-1.rds.amazonaws.com port=5432 dbname=research_archive user=archive_analyst sslmode=require" \
  -v ON_ERROR_STOP=1 -c "SELECT COUNT(*) FROM archive.negotiation"
# Password for user archive_analyst: <paste, Cmd-V>
```

This makes zero AWS API calls (it can't - the security group won't
allow it) and prompts for the password the same way any `psql`
connection does.

Never use the `archive_admin` (master) credential for routine querying -
use it only for the one-time role-creation workflow above.

## Setup incident and password-helper hardening (2026-08-15)

Factual record of the initial `archive_analyst` bring-up, kept here (not
in Git history alone) since it explains why the password helpers look
the way they do. No password, password hash, or any other secret value
appears below or anywhere in source control for this incident.

- Before this work, `archive_analyst` did not exist as a role, and the
  `/research-archive-platform/dev/postgres-analyst` SSM parameter did
  not exist either - both were genuinely absent, not merely
  undocumented.
- The role was created and granted `CONNECT` on the database, `USAGE`
  on the `archive` schema, and `SELECT` (including a default-privilege
  grant covering future tables) - see
  `database/analysis-role/create_archive_analyst_role.sql`. No write,
  DDL, ownership, replication, or application-role privilege of any
  kind.
- `default_transaction_read_only = on` was set at the role level
  (`ALTER ROLE archive_analyst SET default_transaction_read_only =
  on`), applied automatically to every session this role opens - the
  database itself refuses writes regardless of what any client-side
  tooling does or doesn't filter.
- **`scripts/mac-show-analyst-password.sh` had two real defects**,
  confirmed live, not just suspected: (1) `aws ssm get-parameter`'s
  stdout and stderr were captured together (`2>&1`) into the same
  variable that then got parsed as JSON - a warning on an otherwise
  *successful* call could silently corrupt the parse without the
  command's own exit code ever indicating failure; (2) the script
  unconditionally printed "Password copied to your clipboard" with no
  verification that `pbcopy` had actually succeeded or that the
  clipboard genuinely held the new value. Together these meant a run
  could claim success while the clipboard still held an unrelated
  1,237-character SQL string from an earlier copy, producing repeated
  PostgreSQL authentication failures with no indication of the real
  cause. `scripts/mac-generate-analyst-password.sh` had a related but
  distinct defect: it passed connection details to its `python3 -c`
  JSON-construction step as dead trailing positional arguments (after
  `-c '...'`, where the script's own code never read them) rather than
  as environment variables scoped to that one call - it only worked at
  all because of a broad top-of-script `export`, which left those
  values (including the password) in this whole script's environment
  for its entire run rather than only the one subprocess that needed
  them.
- Both scripts were hardened to fail closed (parameter missing, invalid
  JSON, any required field missing/blank, `pbcopy` failure, or a
  clipboard-verification mismatch all abort with a clear message and no
  clipboard change), to verify the clipboard by length and exact
  content before ever claiming success, to never merge a successful
  call's stderr into stdout that gets parsed, to clear the clipboard on
  interruption (Ctrl-C) as well as normal completion, and to pass
  connection details to `python3` via a proper per-call environment
  prefix instead of a broadly exported one. See
  `scripts/tests/test-mac-analyst-password-helpers.sh` for the fully
  mocked regression coverage (no live AWS calls, no real clipboard
  access).
- The final `archive_analyst` connection, after these fixes, succeeded:
  `current_user = archive_analyst`, `transaction_read_only = on`.

## Proving writes are denied (transaction-safe, no archived data touched)

Run this in the same interactive psql session, connected as
`archive_analyst`:

```sql
SHOW transaction_read_only;
SELECT current_user, current_database();
SELECT COUNT(*) FROM archive.negotiation;

BEGIN;
UPDATE archive.negotiation SET document_number = document_number WHERE 1 = 0;
ROLLBACK;
```

`transaction_read_only` should read `on` (set at the role level by
`ALTER ROLE archive_analyst SET default_transaction_read_only = on`,
applied automatically to every session this role opens). The `UPDATE`
is expected to fail immediately - PostgreSQL rejects the statement
before evaluating `WHERE 1 = 0`, so even if the role somehow had
`UPDATE` privilege, zero rows would ever be at risk; it does not in any
case (only `SELECT` was granted), so this fails on two independent
layers (`ERROR: permission denied for table negotiation` and/or
`ERROR: cannot execute UPDATE in a read-only transaction`). The
`ROLLBACK` guarantees no partial effect either way.

## Limitations versus a local DBeaver connection

- CloudShell sessions run in the browser/AWS console, not a local GUI
  tool - no DBeaver, no local file access, no cloned repository.
- Session idle timeout applies (CloudShell disconnects after a period of
  inactivity - reconnect and re-run the Mac-side "show password" script
  as needed).
- Pasting the password once per session is a deliberate tradeoff for
  keeping CloudShell's own AWS API access at zero - see "The security
  boundary this design is built around" above.
- This does not solve local DBeaver/VPN access - that requires the BU
  networking change documented in
  `docs/runbooks/VPN_RDS_CONNECTIVITY_INVESTIGATION.md`, a cross-team
  network change requiring BU networking's own approval.

## What this does NOT change

- ECS Fargate remains the normal ETL execution route.
- RDS is never modified to be publicly accessible.
- Local Postgres (`scripts/run-local.sh`) is still not authoritative for
  anything - see `CLAUDE.md`.
