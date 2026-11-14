# U12 pre-check — is Lalitha an active ARCHIVE_ADMIN?

**Status: NOT YET ANSWERED.** The query below has not been run.

## Why I could not run it

The approved route is the CloudShell **VPC environment**, and two of its
steps are outside what this session may do:

| Runbook step | Blocker |
|---|---|
| 2. Open the `research-archive-analysis` CloudShell environment | AWS **console** only — CloudShell has no API for executing commands |
| 4. `scripts/mac-show-analyst-password.sh` | Retrieves the `archive_analyst` credential. Instructed not to retrieve or expose database credentials |

So the SQL is prepared for you to run. Nothing below writes anything:
three `SELECT`s, no DDL, no DML.

## The identity mapping — resolved, not assumed

`authz.admin_role_member` keys on `institutional_identifier`, which is
**not** an email and **not** a login name. The chain is:

```
Cognito user   BUShibboleth_lhuval@bu.edu
  -> sub       74187408-40b1-7029-f3da-a01795c5424a    (AdminGetUser, read-only)
  -> authz.identity_link.cognito_subject   (status = 'ACTIVE')
  -> institutional_identifier
  -> authz.person_status.suspended         (a suspended identity is denied)
  -> authz.admin_role_member               (status = 'ACTIVE')
```

The sub above was read from Cognito with `admin-get-user`; it is the only
part I could resolve without database access.

`ux_identity_link_active_cognito` is unique on
`(cognito_issuer, cognito_subject) WHERE status = 'ACTIVE'`, so at most one
active link can exist for her — the `Ambiguous` outcome cannot arise
through this path.

## Run this in the CloudShell VPC environment

Connect per `docs/runbooks/CLOUDSHELL_DATABASE_ACCESS.md` steps 1-5, as
`archive_analyst`, then:

```sql
\set sub '74187408-40b1-7029-f3da-a01795c5424a'

-- (a) Her identity link history. Zero rows = never provisioned.
SELECT identity_link_id, institutional_identifier, login_name,
       kuali_person_id, method, status, verified_at, revoked_at
FROM authz.identity_link
WHERE cognito_subject = :'sub'
ORDER BY verified_at DESC;

-- (b) THE DECISIVE ANSWER.
SELECT il.institutional_identifier,
       il.login_name,
       COALESCE(ps.suspended, false)            AS identity_suspended,
       (arm.admin_member_id IS NOT NULL)        AS is_active_archive_admin,
       arm.granted_at
FROM authz.identity_link il
LEFT JOIN authz.person_status ps
       ON ps.institutional_identifier = il.institutional_identifier
LEFT JOIN authz.admin_role_member arm
       ON arm.institutional_identifier = il.institutional_identifier
      AND arm.status = 'ACTIVE'
WHERE il.cognito_subject = :'sub'
  AND il.status = 'ACTIVE';

-- (c) Context: every active administrator, to sanity-check (b).
SELECT institutional_identifier, granted_by, granted_at
FROM authz.admin_role_member
WHERE status = 'ACTIVE'
ORDER BY granted_at;
```

## How to read the result

| Query (b) returns | Meaning | U12 |
|---|---|---|
| **zero rows** | no ACTIVE identity link → `NotProvisioned` → denied | **Qualifies** |
| `is_active_archive_admin = false` | linked, holds no admin role | **Qualifies** |
| `identity_suspended = true` | `Suspended` → denied regardless | Qualifies for the toggle check, but she may not be able to use the archive normally — tell me and I will adjust steps 2-3 |
| `is_active_archive_admin = true` | she **is** an administrator | **Does not qualify** — stop, we need a different tester |

Query (c) is a cross-check: if her identifier from (a) appears in (c),
(b) must have said `true`. If those two disagree, stop and report it
rather than trusting either.

## Do not

- Paste the `archive_analyst` password anywhere, including back to me
- Paste query output containing anything beyond the columns above
- Run anything that is not a `SELECT`

Send me (b) and (c) and I will record the outcome and finalise U12.
