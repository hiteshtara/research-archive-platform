# Record-authorization administration CLI

`authz_admin.py` administers the archive's private `authz` schema (migrations V082 and
V083): the KIM principal crosswalk, grants, identity links and suspensions. It is the only
supported way to write these tables by hand.

Rules the tool enforces:

- **One transaction per command.** Each write and its `authz.access_audit` row(s) commit
  together, or not at all. `--dry-run` does all of the work, then rolls back.
- **The connection string comes only from `AUTHZ_ADMIN_DATABASE_URL`.** It is never an
  argument and is never printed, not even in error messages.
- **Reports print counts and row numbers**, never crosswalk attribute values.
  `list --institutional-id` prints the rows of the one person you asked for.
- **No self-service.** A grantee can't grant to or approve for themselves. A `CENTRAL`
  grant needs an approver who is not the granter. Every grant needs `--approved-by` and
  `--reason`.

## Running

```bash
export AUTHZ_ADMIN_DATABASE_URL='postgresql://<user>@<host>:<port>/<database>'   # from your secret store
uv run --with 'psycopg[binary]' scripts/authz-admin/authz_admin.py list
```

`crosswalk-validate` needs no database and no third-party packages. Plain `python3` works:

```bash
python3 scripts/authz-admin/authz_admin.py crosswalk-validate kim_principals.tsv principal_crosswalk.tsv --attribute <approvedAttributeName>
```

## Commands

| Command | Does | Audit action |
|---|---|---|
| `crosswalk-validate P X --attribute A [--rolodex-ids F] [--allow-shared-entity]` | validates both files; prints counts and row numbers | (none) |
| `crosswalk-import P X --attribute A --load-ref R --actor W` | validates, then upserts every principal and inserts every accepted crosswalk row, in one transaction. Any validation failure or conflict imports **nothing** | `CROSSWALK_IMPORTED` |
| `grant-add --type CENTRAL\|UNIT\|IO --grantee ID [--unit U [--include-descendants]] [--io V] --granted-by G --approved-by A --reason T [--expires-at ISO]` | adds one grant | `GRANT_ADDED` |
| `grant-revoke --grant-id N --revoked-by W --reason T` | revokes one grant | `GRANT_REVOKED` |
| `link-revoke (--link-id N \| --institutional-id ID) --revoked-by W --reason T` | revokes ACTIVE identity link(s) | `LINK_REVOKED` |
| `crosswalk-revoke (--crosswalk-id N \| --prncpl-id P --attribute A) --revoked-by W --reason T` | revokes ACTIVE crosswalk row(s) | `CROSSWALK_REVOKED` |
| `suspend` / `unsuspend --institutional-id ID --changed-by W --reason T` | suspension overrides every grant | `PERSON_SUSPENDED` / `PERSON_UNSUSPENDED` |
| `list [--institutional-id ID]` | counts, or one person's links, grants, crosswalk rows, status and recent audit | (read only) |

Every write command accepts `--dry-run`.

Re-importing the same files is idempotent: unchanged rows are counted and skipped.

The import never replaces an existing ACTIVE mapping. To re-map a value or a principal,
revoke the old row with `crosswalk-revoke`, then import the new one.

After `crosswalk-revoke`, the API revokes any automatically verified identity link that
depended on that row at its next request (`ENROLLMENT_LINK_REVOKED`). `link-revoke` takes
effect at once.

## Crosswalk file format

There are two tab-separated UTF-8 files, each with exactly the header row shown below.

### `kim_principals.tsv`

This file holds the minimal KIM principal columns, filtered to the principals named in
the crosswalk.

| Column | Rule |
|---|---|
| `prncpl_id` | KIM principal id, as stored in Award/Proposal contact `PERSON_ID` |
| `entity_id` | KIM entity id; used only to detect several principals for one entity |
| `actv_ind` | `Y` or `N` |

Inactive principals are loaded as inactive. Their crosswalk rows are reported and skipped.

### `principal_crosswalk.tsv`

| Column | Rule |
|---|---|
| `attribute_name` | the **one** approved, verified sign-in attribute; must equal `--attribute` |
| `attribute_value` | the value the identity provider releases for this person. Never an email address; no whitespace |
| `prncpl_id` | must be in `kim_principals.tsv` |
| `evidence_ref` | where the pairing was verified (ticket or batch id); no personal data |
| `verified_by` | the person or process that verified it |

### Validation (`validate_crosswalk.py`)

The import is rejected, and nothing is written, if any of these checks fails:

1. Headers are exact, and required columns are non-empty.
2. Exactly one `attribute_name` appears, and it is the approved one.
3. No value looks like an email address, and none contains whitespace or control
   characters.
4. Uniqueness holds both ways: one value maps to one principal, and one principal comes
   from one value.
5. Every principal is in the principal file.
6. No principal is a non-employee (rolodex) id. Rolodex ids come from contact rows'
   `rolodex_id` and are passed with `--rolodex-ids`.
7. No two principals share one KIM entity, unless `--allow-shared-entity` is given.

The schema enforces the same uniqueness among ACTIVE rows, and a foreign key to
`authz.kim_principal`, so a non-principal id can't be stored at all.

### Boundaries

- A KIM principal alone grants nothing. Access comes from qualifying contact rows (policy
  `VERIFIED_PRINCIPAL`) or from explicit grants.
- The crosswalk never creates grants.
- There is no email mapping and no login-name mapping.

## Tests

```bash
# unit tests (no database)
cd scripts/authz-admin && uv run --no-project --with pytest --with 'psycopg[binary]' python -m pytest -q
# integration test: throwaway `docker run postgres` on a random local port, V082 + V083 only, removed afterwards
scripts/authz-admin/test_integration.sh
```
