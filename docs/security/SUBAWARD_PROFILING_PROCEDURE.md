# Running the Subaward funding profile

A step-by-step for one task: executing
[`subaward-funding-profile.sql`](subaward-funding-profile.sql) against dev
PostgreSQL and bringing the numbers back.

This is the **last outstanding prerequisite** before Subaward authorization
enforcement can be considered — see
[`SUBAWARD_AUTHORIZATION.md`](SUBAWARD_AUTHORIZATION.md). It is read-only and
changes nothing.

For the full environment reference, the one-time role setup, and the general
query cookbook, see
[`../runbooks/CLOUDSHELL_DATABASE_ACCESS.md`](../runbooks/CLOUDSHELL_DATABASE_ACCESS.md).
This page does not repeat them.

## Before you start

- **~10 minutes.** The queries are counts over ~89k Subaward rows.
- You need the AWS console for account `770203350335`, and this repo checked
  out on the Mac.
- Everything you run is a `SELECT`. The `archive_analyst` role is read-only
  at the database level (`default_transaction_read_only=on`), so a stray
  write fails rather than lands.

### The one constraint that shapes this procedure

The CloudShell security group has **one** egress rule: RDS on TCP 5432.
There is no internet from that shell. It **cannot** `git clone`, `curl`, or
`pip install`. So the SQL gets there by **paste**, not by download. That is
expected, not a misconfiguration — do not "fix" it by widening the security
group.

## Credential handling — read once

- The helper copies the password to the clipboard and **never prints it**.
  Keep it that way.
- Use `--password` so psql prompts. **Never** use `PGPASSWORD=… psql …` — it
  lands in shell history and is visible in the process list.
- psql echoes nothing while a password is typed or pasted. That is normal.
- Never paste the password into the SQL file, a terminal command, this repo,
  a GitHub comment, a screenshot, or chat.
- CloudShell keeps `$HOME` between sessions. Do not write the credential to a
  file there.

## Steps

### 1. On the Mac — confirm the right AWS account

```bash
buaws
AWS_PROFILE=bu-nprd AWS_REGION=us-east-1 \
  aws sts get-caller-identity --query Account --output text
```

Expect `770203350335`. **Stop if anything else appears.**

> If this fails with `ExpiredToken` right after a successful BU login, the
> Chrome SAML-to-STS extension is disabled — re-enable it and retry.

### 2. Open the VPC CloudShell environment

AWS console → account `770203350335` → region `us-east-1` → CloudShell →
select the environment named **`research-archive-analysis`**.

Not the default CloudShell environment — that one has no route to the
private database. If it needs recreating, use exactly VPC
`vpc-0590614d7cfcdedf6`, subnet `subnet-00fba12ee73ff0e3b`, security group
`sg-002be83bf1cf249fa`. Do not substitute others, and do not create new ones.

Sanity check at the `~ $` prompt:

```bash
psql --version     # expect psql (PostgreSQL) 16.x
```

### 3. In CloudShell — paste the profiling SQL into a file

Open [`subaward-funding-profile.sql`](subaward-funding-profile.sql) on the
Mac and copy the **whole file**. Then in CloudShell type the first line,
paste, and type the last line:

```bash
cat > subaward-profile.sql <<'PROFILE_SQL'
<paste the entire file here>
PROFILE_SQL
```

The quoted `'PROFILE_SQL'` marker matters: it stops the shell expanding
anything inside. Verify it arrived whole:

```bash
wc -l subaward-profile.sql      # expect 250
grep -c '^SELECT\|^WITH' subaward-profile.sql
```

### 4. On the Mac — put the password on the clipboard

```bash
cd ~/projects/research-archive-platform
scripts/mac-show-analyst-password.sh
```

It prints the connection command and nothing secret. Leave the helper
waiting — do not press Enter yet.

### 5. In CloudShell — connect and run

```bash
psql "host=research-archive-platform-dev-postgres.clb9d4mkglfd.us-east-1.rds.amazonaws.com \
port=5432 dbname=research_archive user=archive_analyst sslmode=require connect_timeout=10" \
  --password \
  -f subaward-profile.sql | tee subaward-profile-output.txt
```

Paste the password at the `Password:` prompt (Cmd+V, then Enter). Nothing
will appear as you paste — expected.

Then go back to the Mac helper and **press Enter to clear the clipboard.**

### 6. Confirm it really ran read-only

If you want the identity check, reconnect interactively and run:

```sql
SELECT current_user, current_database();
SHOW transaction_read_only;
```

Expect `archive_analyst`, `research_archive`, `on`.

### 7. Bring the numbers back

```bash
cat subaward-profile-output.txt
```

Copy the text out of the terminal.

**The output is safe to share** — paste it into the PR, a ticket, or chat.
The script was written for that: it emits counts, percentages, string
lengths and MD5-truncated references only. No titles, no sponsor names, no
people, no unhashed identifiers.

That guarantee covers **this script as written**. If you modify it or run ad
hoc queries, re-check the output before sharing.

### 8. Tidy up

```bash
rm -f subaward-profile.sql subaward-profile-output.txt
```

Neither contains a credential; this just avoids leaving stale numbers in a
persistent `$HOME`.

## What the answers are for

Ten questions, but **two decide something**:

| Question | Why it matters |
| --- | --- |
| **Q2 / Q3** — Subaward families with ≥1 valid funding Award, and with none | **The blast radius.** Every Subaward with no resolvable funding Award becomes invisible to every non-Central user once enforcement is on, because no explicit Subaward grant type exists. A small share is uncontroversial; a large one is a business decision before enabling enforcement — not a reason to invent a `requisitioner_unit` fallback. |
| Q6 — unresolvable funding references | Informational. Already handled: the INNER join means a dangling `award_id` cannot authorise. |
| Q7 / Q10 — version vs family grain, funding varying across versions | Informational. Already handled by per-version scoping. |
| Q4 / Q5 — one funder vs several | Informational. Sizes how often the ANY-match rule actually does work. |
| Q8 / Q9 — `requisitioner_unit` match quality | **Evidence only.** Nothing reads this column. It exists so that if a fallback rule is ever proposed, the discussion starts from data instead of assumption. |

## If something goes wrong

| Symptom | Cause |
| --- | --- |
| `psql: command not found` | You are in the default CloudShell, not the VPC environment |
| Connection hangs, then times out | Same — or the environment lost its VPC settings |
| `password authentication failed` | The clipboard held something else. Re-run the helper and check it reported success |
| `--More--` and the output stops | The pager. Press `q`; the script sets `\pset pager off` itself |
| A `SELECT` is refused | Expect this never to happen; if it does, stop and report it rather than working around it |
