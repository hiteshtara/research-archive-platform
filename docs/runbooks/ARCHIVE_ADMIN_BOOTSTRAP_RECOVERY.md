# Recovering an ARCHIVE_ADMIN membership with no identity link

**This should now be unreachable.** The first bootstrap writes membership,
identity link and both audit rows in one transaction, so a failure leaves
nothing behind and the utility can simply be re-run. This document covers
the case where a half-state exists anyway — a database created before that
change, a direct SQL edit, or a link revoked without a replacement.

## How to recognise it

```sql
SELECT m.institutional_identifier, m.status, m.granted_by,
       (SELECT count(*) FROM authz.identity_link l
         WHERE l.institutional_identifier = m.institutional_identifier
           AND l.status = 'ACTIVE') AS active_links
  FROM authz.admin_role_member m
 WHERE m.status = 'ACTIVE';
```

`active_links = 0` on an ACTIVE administrator is the stranded state.

## Why it strands

| | |
|---|---|
| Re-run the bootstrap utility | refused — *"an active ARCHIVE_ADMIN already exists"* |
| Use the administrative API | **404** while enforcement is off; the tree does not exist |
| Enable enforcement to reach the API | does not help — with no link, no token resolves to that archive identity, so nobody can authenticate **as** the administrator |

There is no in-application route out. That is the whole reason the bootstrap
was made atomic.

## Recovery — operator only, never an endpoint

Deliberately **not** a recovery API. An endpoint that attaches a Cognito
identity to an existing administrator is exactly the capability an attacker
wants; it would have to be either unauthenticated (unthinkable) or
authenticated as the administrator who by definition cannot sign in.

### Approval required

Recovery grants someone administrative control of the archive. Treat it as
equivalent to the original bootstrap:

1. **Two named people**: one operator who runs it, one approver who is not
   the operator and not the person being linked. Mirrors the two-person rule
   V086 enforces for re-linking an identity that holds grants.
2. **Written confirmation of the Cognito subject** from the pool, not from
   an email. Verify `sub` against `admin-get-user`, and against the `sub`
   claim in a token that person actually holds.
3. **A recorded reason** naming the incident.
4. **DEV only** by this procedure. Production needs its own approved path.

### Preferred: remove and re-bootstrap

Cleanest, because the replacement goes back through the normal, tested path.

Run as the database **owner** (`archive_admin`). `archive_api` cannot do
this and must not be given the privileges for a one-off repair.

```sql
BEGIN;
-- 1. Confirm exactly what is stranded.
SELECT admin_member_id, institutional_identifier, status
  FROM authz.admin_role_member WHERE status = 'ACTIVE';

-- 2. Revoke it, keeping the history rather than deleting it.
--    ck_admin_role_member_revoked and ..._revoked_by require status,
--    revoked_at and revoked_by to move together.
UPDATE authz.admin_role_member
   SET status     = 'REVOKED',
       revoked_at = CURRENT_TIMESTAMP,
       revoked_by = '<operator>'
 WHERE status = 'ACTIVE'
   AND institutional_identifier = '<stranded ARP>';

-- 3. Record the intervention. detail is JSONB, not text: the application
--    writes {"note": "..."} and this must match or the insert fails.
INSERT INTO authz.access_audit (actor, action, institutional_identifier, detail)
VALUES ('<operator>', 'ADMIN_ROLE_REVOKED', '<stranded ARP>',
        CAST('{"note":"stranded bootstrap recovery; approved by <approver>; <incident ref>"}' AS JSONB));
COMMIT;
```

Then run the bootstrap utility normally. It mints a **new** ARP; the
stranded one is abandoned, which is correct — it never held grants,
because bootstrap issues none.

**Audit rows: 1 from this procedure** (`ADMIN_ROLE_REVOKED`), **plus 2
written by the utility** (`ADMIN_ROLE_BOOTSTRAPPED` and `IDENTITY_LINKED`)
= **3 in total**.

### Alternative: attach the link directly

Only when the stranded ARP must be kept — it is referenced elsewhere, or
grants were issued against it after the fact.

```sql
BEGIN;
-- method and status are CHECK-constrained; verified_by and verified_at
-- are NOT NULL.
INSERT INTO authz.identity_link
  (cognito_issuer, cognito_subject, institutional_identifier,
   method, status, verified_by, verified_at)
VALUES ('<issuer>', '<verified sub>', '<stranded ARP>',
        'ADMIN_VERIFIED', 'ACTIVE', '<operator>', CURRENT_TIMESTAMP);

INSERT INTO authz.access_audit (actor, action, institutional_identifier, detail)
VALUES ('<operator>', 'IDENTITY_LINKED', '<stranded ARP>',
        CAST('{"note":"stranded bootstrap recovery; approved by <approver>; <incident ref>"}' AS JSONB));
COMMIT;
```

`ux_identity_link_active_identity` (V085) refuses a second ACTIVE link for
the same archive identity, so this cannot silently create a duplicate.

**Audit rows: 1** (`IDENTITY_LINKED`). The administrator is not re-created,
so there is no bootstrap row.

## Verification, in this order

1. `active_links` is 1 for the administrator, and its `status` is `ACTIVE`.
2. Exactly one ACTIVE administrator overall.
3. The audit rows expected for the procedure you used — **3** after
   remove-and-re-bootstrap, **1** after attaching the link directly — each
   naming the operator, with the approver in the `note`:
   ```sql
   SELECT occurred_at, actor, action, institutional_identifier,
          detail ->> 'note' AS note
     FROM authz.access_audit ORDER BY audit_id;
   ```
4. `authz.access_grant` is **unchanged** — recovery grants no data access.
5. The linked person signs in and the token's `sub` equals the stored
   `cognito_subject`.
6. With enforcement on in a disposable fixture, that identity resolves to
   the expected ARP.

## What must not be done

- No recovery endpoint, under any authentication.
- Do not grant `archive_api` the privileges to perform this.
- Do not enable enforcement in DEV merely to reach the admin API.
- Do not reuse a stranded ARP for a different person; mint a new one.
