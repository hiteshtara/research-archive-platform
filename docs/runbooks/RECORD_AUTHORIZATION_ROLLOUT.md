# Record authorization: rollout runbook (enrollment, crosswalk, grants)

This runbook turns on record-level authorization for a deployment. It is generic: every
`<placeholder>` must be filled in from your own environment and your identity provider's
answers. Nothing here has been applied to any environment.

**What exists, all OFF by default:**

- **Enforcement** (`app.authorization.enforcement-enabled`). It decides record access from
  grants and verified contact relationships.
- **Enrollment** (`app.authorization.enrollment.enabled`). Server-side, it links a signed-in
  Cognito profile to an existing KIM principal through the crosswalk.
- **Administration CLI** (`scripts/authz-admin/authz_admin.py`). It manages the crosswalk,
  grants, links and suspensions, with an audit row for every write.

Background: `docs/architecture/RECORD_AUTHORIZATION_STATUS.md`.

## 0. Prerequisites (answers from your identity provider)

Do not start until all four are answered in writing:

1. **A stable, never-reassigned SAML NameID for the Cognito service provider.** Cognito
   recognises a returning federated user by NameID. If the NameID changes, the same person
   gets a new Cognito profile (new `sub`) and must enrol again.
2. **The ONE verified attribute that resolves the existing KIM principal.** You need its
   SAML name/OID, and a guarantee that it is never reassigned, survives a login-name
   change, and has defined behaviour on leave and return.
3. **An approved, read-only extract** of the principal ids, entity ids and active flags
   for the people to be enrolled, plus the attribute-value → principal pairs (the
   crosswalk). See `scripts/authz-admin/README.md` for the file format.
4. **The approved policy settings** (`version-scope`, `department-match`,
   `research-staff-roles`, `contact-derivation`). None of them has a default.

## 1. Apply the migrations BEFORE deploying API code

V082 (`authz` identity/grants/audit) and V083 (`authz.kim_principal`,
`authz.principal_crosswalk`) are additive and create empty tables. As with every
migration in this repository, **Spring does not apply them**. The ETL migration runner
does.

1. Confirm the loader image you will run contains V083: its source commit must descend
   from the commit that added it:
   `git merge-base --is-ancestor <v083-commit> <loader-image-commit>`.
2. Run the loader in `--migrate-only` mode against the target database, using the same
   execution path you use for every other migration.
3. Verify the migrations landed:

   ```sql
   SELECT version, installed_at FROM public.schema_migration WHERE version IN (82, 83);
   SELECT to_regclass('authz.identity_link'), to_regclass('authz.principal_crosswalk');
   ```

4. Only then deploy the API build that contains the enrollment code. With both flags
   off, the new code reads none of these tables.

## 2. Infrastructure (templates; review the plan, apply through your normal process)

### 2.1 Cognito SAML identity provider, attribute mapping, app client

```hcl
# The custom attribute that will carry the ONE verified value. Adding a custom attribute
# is in-place on an existing pool. Confirm `terraform plan` shows an update, not a
# replacement (prevent_destroy on the pool blocks a replacement).
resource "aws_cognito_user_pool" "this" {
  # ... existing settings ...
  schema {
    name                     = "<identifier_attribute>"      # becomes custom:<identifier_attribute>
    attribute_data_type      = "String"
    mutable                  = true                          # required for SAML attribute mapping
    developer_only_attribute = false
    string_attribute_constraints {
      min_length = 1
      max_length = 256
    }
  }
}

resource "aws_cognito_identity_provider" "institution_saml" {
  user_pool_id  = aws_cognito_user_pool.this.id
  provider_name = "<SamlProviderName>"
  provider_type = "SAML"

  provider_details = {
    MetadataURL = "<https://idp.example.edu/idp/shibboleth>"
    IDPSignout  = "false"
  }

  attribute_mapping = {
    "custom:<identifier_attribute>" = "<urn:oid:released.attribute.oid>"
  }
}

resource "aws_cognito_user_pool_client" "app" {
  # ... existing settings ...
  supported_identity_providers = ["COGNITO", aws_cognito_identity_provider.institution_saml.provider_name]
  allowed_oauth_scopes         = ["openid", "email", "profile"]   # never aws.cognito.signin.user.admin
  read_attributes              = ["email", "custom:<identifier_attribute>"]
  write_attributes             = ["email", "custom:<identifier_attribute>"]   # needed for SAML mapping
}
```

**Attribute writability.** A mapped attribute must be writable by the app client. A token
holding the `aws.cognito.signin.user.admin` scope could therefore change it with
`UpdateUserAttributes`. Before go-live, test that a federated user's access token can't
do this. Also confirm federated users have no password and so cannot use SRP sign-in.

Enrollment also requires that the profile's `identities` record names
`<SamlProviderName>`. That record is set by Cognito, and a native (password) profile
never has it.

### 2.2 IAM: the API task role may read profiles from this pool only

```hcl
data "aws_iam_policy_document" "api_enrollment" {
  statement {
    sid       = "ArchiveEnrollmentReadProfile"
    actions   = ["cognito-idp:AdminGetUser"]
    resources = [aws_cognito_user_pool.this.arn]
  }
}

resource "aws_iam_role_policy" "api_enrollment" {
  name   = "<project>-<env>-api-enrollment"
  role   = "<api-task-role-name>"
  policy = data.aws_iam_policy_document.api_enrollment.json
}
```

No credentials are configured in the application. The AWS SDK's default provider chain
uses the task role.

## 3. Application configuration

```yaml
app:
  authorization:
    enforcement-enabled: false            # turn on LAST (section 7)
    version-scope: <PER_VERSION|FAMILY_WIDE>
    department-match: <EXACT_LEAD_UNIT|LEAD_UNIT_WITH_DESCENDANTS>
    research-staff-roles: <PI,MPI,COI>
    contact-derivation: VERIFIED_PRINCIPAL
    enrollment:
      enabled: true
      user-pool-id: <region>_<poolId>
      region: <region>
      saml-provider-name: <SamlProviderName>
      identifier-attribute: custom:<identifier_attribute>
      crosswalk-attribute-name: <approvedAttributeName>   # authz.principal_crosswalk.attribute_name
      refusal-retry-seconds: 60          # a refused sign-in is re-tried after this; 0 = every request
      # endpoint-override: only for a local lab's simulated user pool; never in a deployment
```

The same keys work as environment variables, e.g. `APP_AUTHORIZATION_ENROLLMENT_ENABLED=true`
or `APP_AUTHORIZATION_ENROLLMENT_USER_POOL_ID=<region>_<poolId>`.

If `enrollment.enabled=true` and any of `user-pool-id`, `region`, `saml-provider-name`,
`identifier-attribute` or `crosswalk-attribute-name` is missing, **the API refuses to
start**, and the error message names the missing keys.

Enrollment runs only while enforcement is on. With enforcement off, the Cognito client
is created but never called.

## 4. Enrollment procedure

1. **Validate** the extract without a database:

   ```bash
   python3 scripts/authz-admin/authz_admin.py crosswalk-validate kim_principals.tsv principal_crosswalk.tsv \
       --attribute <approvedAttributeName> --rolodex-ids <rolodex_ids.txt>
   ```

2. **Dry-run the import**, then import it:

   ```bash
   export AUTHZ_ADMIN_DATABASE_URL='<from your secret store>'
   uv run --with 'psycopg[binary]' scripts/authz-admin/authz_admin.py crosswalk-import \
       kim_principals.tsv principal_crosswalk.tsv --attribute <approvedAttributeName> \
       --rolodex-ids <rolodex_ids.txt> --load-ref <batch-id> --actor <your-admin-id> --dry-run
   # repeat without --dry-run
   ```

   The command prints counts only. Any validation failure, or any conflict with an
   existing ACTIVE mapping, imports nothing.

3. **What happens at sign-in.** On the user's first request with enforcement on, the API
   runs these checks in order:
   1. read the token's `username` claim;
   2. call `AdminGetUser`;
   3. require the profile `sub` to equal the token `sub`;
   4. require the federation record to name `<SamlProviderName>`;
   5. read `custom:<identifier_attribute>`;
   6. require exactly one ACTIVE crosswalk row, pointing to an ACTIVE principal;
   7. require that this profile has no revoked link and that no other profile holds the
      identifier.

   If every check passes, it writes an `AUTO_VERIFIED` identity link. The link's
   `institutional_identifier` is the attribute value, and its `kuali_person_id` is the
   principal. `login_name` stays empty.

   Every refusal links nothing and is audited. The user sees "access not provisioned".

## 5. Grant administration

Grants are keyed on the **institutional identifier**: the crosswalk attribute value, as
stored on the identity link. Grants can be added before a person first signs in.

```bash
authz_admin.py grant-add --type CENTRAL --grantee <identifier> --granted-by <admin-a> --approved-by <admin-b> --reason "<ticket>"
authz_admin.py grant-add --type UNIT --grantee <identifier> --unit <unit-number> [--include-descendants] --granted-by ... --approved-by ... --reason ...
authz_admin.py grant-add --type IO --grantee <identifier> --io <account-number> --granted-by ... --approved-by ... --reason ...
authz_admin.py grant-revoke --grant-id <n> --revoked-by <admin> --reason "<ticket>"
authz_admin.py list --institutional-id <identifier>
```

**Research Staff access needs no grant.** Under `VERIFIED_PRINCIPAL`, a linked principal
sees the Awards and Proposals on which they are a qualifying contact.

The CLI refuses three things:

- a self-grant;
- self-approval;
- a `CENTRAL` grant whose approver is also its granter.

## 6. Audit, revocation and suspension

Every change is recorded in `authz.access_audit`, an append-only table:

- **The API (actor `api-enrollment`)** writes `ENROLLMENT_LINKED`,
  `ENROLLMENT_ALREADY_LINKED`, `ENROLLMENT_REFUSED`, `ENROLLMENT_FAILED` and
  `ENROLLMENT_LINK_REVOKED`. The specific reason is in `detail->>'outcome'`, e.g.
  `REFUSED_UNKNOWN_PERSON`.
- **The CLI** writes `CROSSWALK_IMPORTED`, `GRANT_ADDED`, `GRANT_REVOKED`,
  `LINK_REVOKED`, `CROSSWALK_REVOKED`, `PERSON_SUSPENDED` and `PERSON_UNSUSPENDED`.

A refused sign-in is audited at most once per `refusal-retry-seconds` per API instance.

**Revocation applies on the next request,** not when the token expires. Every request
re-checks the identity link, suspension and grants in the database, and re-checks
`AUTO_VERIFIED` links against the crosswalk and principal:

| Action | Effect |
|---|---|
| `crosswalk-revoke`, or a principal re-imported as `actv_ind=N` | link revoked by the API on the next request; access denied |
| `link-revoke` | denied on the next request; never re-linked automatically (`REFUSED_PREVIOUSLY_REVOKED`) |
| `suspend` | denied on the next request; overrides every grant |
| `grant-revoke` | removes exactly what that grant supplied, on the next request |

**Token lifetime caveat.** A revoked user still holds a valid Cognito session: the access
token (lifetime set on the app client) and the refresh token (up to its validity). They
can still authenticate, but every API request is refused. To stop new tokens as well,
disable the Cognito user or sign them out globally through your normal account process.

**Attribute changes caveat.** An existing link isn't re-read from Cognito on each request.
If the identity provider starts releasing a different value for a person, revoke the old
link or crosswalk row. Their next sign-in then follows the normal refusal and re-link
path, under administrator control.

## 7. Acceptance and activation order

**Enforcement must not be turned on in an environment until enrollment and the
crosswalk resolve real users there.** Enrollment runs only under enforcement, so first
prove it in a non-production environment.

1. **Synthetic.** Run the automated suites:
   - `IdentityEnrollmentIntegrationTest`
   - `RecordAuthorizationEnforcementIntegrationTest`
   - `scripts/authz-admin/test_integration.sh`
2. **Non-production, real federation, enforcement on.** Import a crosswalk for a small
   named test group. Grant `CENTRAL` to two administrators, by identifier, before they
   sign in. Then verify:
   - **Positive:** a test PI with no grant row sees exactly their contact Awards. An
     administrator sees everything. `/api/v1/me/access` reports the expected grant kinds.
   - **Negative:** a person not in the crosswalk; an inactive principal; a native
     (password) Cognito account; a revoked crosswalk row (denied on the next request);
     a suspended person. Each gets "access not provisioned" or "access denied", with the
     matching audit row.
   - **Check:** `UpdateUserAttributes` on the identifier attribute is refused for a
     federated user's token (section 2.1).
3. **Production.**
   1. Apply the migrations (section 1) and deploy with enforcement **off** and
      enrollment configured.
   2. Import the full crosswalk and the approved grants.
   3. Turn on enforcement in a planned window.
   4. Repeat the positive and negative checks with real users.

## 8. Rollback

- **Set `app.authorization.enforcement-enabled=false` and redeploy or restart.** This
  restores today's behaviour exactly ("record authorization not enforced"). Enrollment
  stops at once, because it never runs while enforcement is off.
- **Set `app.authorization.enrollment.enabled=false`** to stop new links while keeping
  enforcement. Existing links keep working. They are no longer re-checked against the
  crosswalk, though suspension and revocation still apply.
- **Data stays.** Links, grants, crosswalk rows and audit rows remain for the next
  attempt. The migrations are additive, and no down-migration is needed or provided.
- **The IAM policy and SAML provider can stay.** They grant nothing to users on their own.
