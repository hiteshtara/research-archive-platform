# Shibboleth via Cognito SAML Federation — Implementation Plan (Option 3)

Companion to [SHIBBOLETH-PLAN.md](SHIBBOLETH-PLAN.md). That document
compares three architectural approaches; this document is the detailed
implementation plan for the one selected: **Option 3 — Cognito SAML
Federation**, with two refinements agreed in discussion:

1. Cognito must **not** show its Hosted UI identity-provider picker —
   `signInWithRedirect()` goes straight to the Shibboleth IdP.
2. Users must be **auto-provisioned** on first successful Shibboleth
   login (JIT provisioning) — no invitations, no passwords, no manual
   admin step. Cognito merely "shadows" identity that Shibboleth
   already owns.

Everything else — `SecurityConfiguration.java`,
`AttachmentAuthorizationService.java`, every controller-level
authorization check, the `cognito:groups` → `ROLE_*` mapping — stays
unchanged. Only *how a user gets a Cognito session* changes; the
authorization model that already exists is preserved as-is.

---

## Correction: No Private Key Belongs in the App

A false assumption to rule out up front, since it shapes several
decisions below: **Cognito is the SAML Service Provider, not the React
UI or any app code.** When BU's Shibboleth IdP requires signed
`AuthnRequest`s, the signing keypair belongs to Cognito and is fully
AWS-managed:

- Cognito auto-generates its own SP signing certificate when a SAML
  IdP is configured on the User Pool.
- The private key is **not exportable** and never leaves AWS.
- You retrieve Cognito's **SP metadata document** (entity ID, ACS URL,
  public certificate) and hand that to BU IT to register on the
  Shibboleth IdP side.
- Enabling request signing is a single Terraform attribute
  (`aws_cognito_identity_provider.provider_details.RequestSigningAlgorithm`),
  not a secret you generate or store.

This is a deliberate contrast with Option 1 (`shibboleth-sp-at-edge`),
where **your own code** was the SP and genuinely needed `SAML_PK`/
`SAML_CERT` in Secrets Manager. In Option 3 that entire concern moves
inside AWS. **No `VITE_*` variable should ever hold a private key** —
`VITE_*` values are bundled verbatim into the public JS served to every
browser; a private key there would be both architecturally wrong and a
real credential leak.

**Open verification item**: confirm BU's Shibboleth IdP accepts
Cognito's auto-generated SP signing certificate/algorithm — this is an
external coordination question for BU IT, not an application change.

---

## Current State (What Changes, What Doesn't)

| Area | Today | After this plan |
|---|---|---|
| Login entry point | `signInWithRedirect()` → Cognito Hosted UI login form | `signInWithRedirect({ provider: { custom: ... } })` → straight to Shibboleth SSO, no Cognito-branded screen |
| Identity source | Cognito-native username/password | Shibboleth SAML assertion, JIT-provisioned into Cognito |
| Group membership | Manually curated via `aws_cognito_user_in_group` (Terraform allowlist) | Auto-synced from SAML entitlements via a Pre Token Generation Lambda trigger |
| `SecurityConfiguration.java` | Reads `cognito:groups` | **Unchanged** |
| `AttachmentAuthorizationService.java` | Checks `ROLE_ArchiveAttachmentViewer` | **Unchanged** |
| UI (`ui/src/auth.ts`, `AuthGate.tsx`) | Always requires a Cognito token, no local bypass | Same requirement, plus a new **opt-in local-dev bypass flag** (doesn't exist today) |
| Local dev auth | Frontend always redirects to Cognito login regardless of backend `app.security.enabled`; only the *backend* has a permit-all local profile (`application-local.yml`, `LocalSecurityConfiguration.java`) | Frontend gains its own explicit override for local iteration without a real login (see Phase 1) |

---

## Phase 1 (Minimum Goal): Authenticate via Shibboleth into `localhost`

This is the only phase required to satisfy "at least be able to
authenticate with Shibboleth and get into the app through localhost."
Group sync (Phase 2) and Hosted UI removal polish are **not** required
for this milestone — a successful login that reaches the app is enough,
even if the resulting session has no `ArchiveAttachmentViewer` group
yet.

### 1.1 Terraform: Register Shibboleth as a Cognito SAML IdP

New resource in `terraform/modules/cognito/main.tf`:

```hcl
resource "aws_cognito_identity_provider" "shibboleth" {
  user_pool_id  = aws_cognito_user_pool.this.id
  provider_name = "BUShibboleth"
  provider_type = "SAML"

  provider_details = {
    MetadataURL              = var.shibboleth_idp_metadata_url
    # Or MetadataFile = file("...") if BU provides static XML instead of a URL
    IDPSignout                 = "true"
    RequestSigningAlgorithm    = var.shibboleth_require_signed_requests ? "rsa-sha256" : null
  }

  attribute_mapping = {
    email    = "urn:oid:0.9.2342.19200300.100.1.3"   # mail
    username = "urn:oid:1.3.6.1.4.1.5923.1.1.1.6"     # eduPersonPrincipalName (eppn)
    # Custom attribute to carry raw entitlements for the group-sync
    # Lambda in Phase 2 - harmless to map now even if unused in Phase 1.
    "custom:entitlement" = "urn:oid:1.3.6.1.4.1.5923.1.1.1.7" # eduPersonEntitlement
  }
}
```

New variables in `terraform/modules/cognito/variables.tf`:
- `shibboleth_idp_metadata_url` (string) — BU's IdP metadata endpoint
- `shibboleth_require_signed_requests` (bool, default `true`) — gates the `RequestSigningAlgorithm` line above

Update `aws_cognito_user_pool_client.app` — reference the identity
provider's own `provider_name` attribute rather than a hardcoded
string, so Terraform's dependency graph guarantees the IdP is created
before the client is updated to reference it:
```hcl
supported_identity_providers = ["COGNITO", aws_cognito_identity_provider.shibboleth.provider_name]
```
(Keep `"COGNITO"` alongside Shibboleth during transition — this lets
existing native-login test accounts keep working while Shibboleth is
validated, and gives Phase 1 a fallback if the SAML exchange isn't
working yet. Drop `"COGNITO"` later once Shibboleth is confirmed
end-to-end, if native login is no longer wanted.)

**Exact attribute OIDs above are placeholders** — confirm the real
`urn:oid:...` values BU's Shibboleth IdP actually releases (via BU IT
or the IdP's published attribute release policy) before applying.

**Wiring the new module variables through the environment layer.**
Adding the resource and variables to the module alone isn't enough —
the environment's own `module "cognito"` block must pass them through,
and the environment needs its own variables to hold real values.

`terraform/environments/dev/variables.tf` — add:
```hcl
variable "shibboleth_idp_metadata_url" {
  description = "BU Shibboleth IdP SAML metadata URL."
  type        = string
}

variable "shibboleth_require_signed_requests" {
  description = "Require Cognito to sign outbound SAML AuthnRequests."
  type        = bool
  default     = true
}
```

`terraform/environments/dev/main.tf` — add two lines to the existing
`module "cognito" { ... }` block:
```hcl
  shibboleth_idp_metadata_url        = var.shibboleth_idp_metadata_url
  shibboleth_require_signed_requests = var.shibboleth_require_signed_requests
```

`terraform/environments/dev/terraform.tfvars` (gitignored, not
committed) — add real values:
```hcl
shibboleth_idp_metadata_url = "https://shib.bu.edu/idp/shibboleth"
```

**Console commands to apply this** (per `terraform/README.md`'s
documented workflow; this is a routine incremental change to an
already-existing environment, not a first-time deployment, so the
special ECR-first two-step doesn't apply):

```bash
aws sts get-caller-identity   # verify the Account field matches dev before touching anything

cd terraform/environments/dev
# edit terraform.tfvars to add the two new variable values above

terraform init -backend-config=backend.hcl   # only if not already initialized on this machine
terraform plan     # review: only the new IdP, the client's provider list diff, and Phase 2's Lambda (once added) should appear
terraform apply
```

### 1.2 UI: Direct-to-IdP Redirect

`ui/src/auth.ts` — one-line change to `login()`:

```typescript
export async function login(): Promise<void> {
  await signInWithRedirect({ provider: { custom: identityProviderName } });
}
```

New Vite env var (identifier only — **not secret**, safe to bundle):

```
VITE_COGNITO_IDENTITY_PROVIDER=BUShibboleth
```

Read the same way the existing Cognito vars are read at the top of
`auth.ts`, with the same fail-fast pattern already used there for
missing values.

### 1.3 UI: Local-Dev Auth Bypass (New Capability)

Today, `AuthGate.tsx` **unconditionally** calls `login()` when no token
is present — there is no existing frontend override, only the backend's
separate `app.security.enabled` permit-all profile. Add an explicit,
opt-in bypass so Shibboleth-less local iteration remains possible
without being forced through a real BU login every time:

```typescript
// AuthGate.tsx
const authRequired = import.meta.env.VITE_AUTH_REQUIRED !== "false"; // default true

useEffect(() => {
  if (!authRequired) {
    setAuthenticated(true);
    setChecking(false);
    return;
  }
  // ...existing checkAuthentication() logic unchanged
}, []);
```

New Vite env var:
```
VITE_AUTH_REQUIRED=true   # set to "false" in .env.local to skip login entirely for local dev
```

Defaults to `true` everywhere (including `.env.example`) so this can
never accidentally ship disabled — it must be explicitly opted into
per-developer in a gitignored `.env.local`, mirroring how
`app.security.enabled=false` is confined to the backend's `local`
Spring profile and never the default in `application.yml`.

**Note**: this bypass only affects what the *frontend* renders — if
`app.security.enabled=true` on the API side (the real deployed
default), API calls will still 401 without a real token. This flag is
for UI-only iteration (e.g., working on non-authenticated pages/layout)
paired with `VITE_AUTH_REQUIRED=false`, and is orthogonal to whether
you're also running the API in its `local` permit-all profile.

### 1.4 `.env.example` Additions

```
# Cognito ↔ Shibboleth federation
# Name of the SAML identity provider configured in Cognito (terraform
# output: shibboleth_identity_provider_name) - NOT a secret, safe to
# expose; identifies which IdP signInWithRedirect() should target.
VITE_COGNITO_IDENTITY_PROVIDER=BUShibboleth

# Local-dev only: set to "false" in .env.local to skip the login
# redirect entirely and render the app unauthenticated. Never set this
# in a committed file or deployed environment - Terraform never sets
# this variable, so it silently stays at its safe "true" default in
# every real environment.
VITE_AUTH_REQUIRED=true
```

No key material, secrets, or certificates are added to any `VITE_*`
variable — see the correction section above.

### 1.5 BU Coordination (External Dependency, Not Application Work)

- Request BU's Shibboleth IdP metadata URL/XML
- Confirm attribute release: at minimum `eppn`/`mail`; ideally
  `eduPersonEntitlement` or equivalent group/role attribute for Phase 2
- Register Cognito's auto-generated SP metadata (entity ID + ACS URL +
  public cert) with BU IT
- Confirm whether BU's IdP mandates signed `AuthnRequest`s, and that
  Cognito's signing algorithm/cert satisfies that requirement

### 1.6 Phase 1 Verification

1. `cd ui && npm run dev`, set `VITE_COGNITO_IDENTITY_PROVIDER` in
   `.env.local` to the real configured IdP name, leave
   `VITE_AUTH_REQUIRED` at its default `true`
2. Visit `http://localhost:5173/` → confirm redirect goes straight to
   BU's Shibboleth SSO page (no Cognito-branded login form)
3. Authenticate with a real BU credential
4. Confirm redirect lands back on `http://localhost:5173/` with a
   valid Cognito session (`fetchAuthSession()` returns tokens)
5. Confirm the landing page (`DashboardPage`) renders — either against
   a real API/Postgres, or the mock dashboard stub server in 1.7 below
6. Confirm a **new** Cognito User Pool record was created for that
   user with `UserStatus: EXTERNAL_PROVIDER` (`aws cognito-idp
   list-users`) — no invitation email sent, no password set
7. Separately confirm `VITE_AUTH_REQUIRED=false` in `.env.local` skips
   the redirect entirely and renders the app immediately

### 1.7 Optional: Mock Dashboard Stub Server (No API/DB Required)

`DashboardPage` unconditionally calls `GET /api/dashboard` on render
and shows only an error `Alert` (never the real landing page content)
if that call fails. Since Phase 1's goal is narrowly "authenticate and
reach the landing page," a real Spring Boot API and Postgres/RDS are
unnecessary for that alone — a single mocked response is sufficient,
and cheaper than standing up the real stack. (Spring Boot's own
`application.yml` datasource is configured unconditionally against
Postgres with eager HikariCP connection validation, so the real API
process typically won't even start without a reachable database —
another reason not to reach for the real stack just for this
milestone.)

**Can a `VITE_*` variable drive this?** Partially, and the existing
`VITE_API_BASE_URL` already does the relevant part. `VITE_*` values are
injected into the bundled frontend by Vite at dev-serve/build time —
they aren't available to a separate, standalone Node process the way
the stub server runs. So:
- **Pointing the frontend at the stub** (vs. the real API) reuses the
  *existing* `VITE_API_BASE_URL` — no new `VITE_*` variable needed.
- **Configuring the stub server itself** (port, allowed origin) uses
  plain (non-`VITE_`-prefixed) environment variables read via
  `process.env`, since Vite's env injection doesn't reach an external
  script.

New disposable script, `ui/scripts/mock-dashboard-server.mjs`:

```javascript
#!/usr/bin/env node
// Disposable, dev-only stand-in for GET /api/dashboard so Phase 1 can
// be verified without a running Spring Boot API or Postgres/RDS. Not
// part of the build - run directly with `node`, never imported by app
// code.
import { createServer } from "node:http";

const port = Number(process.env.MOCK_DASHBOARD_PORT ?? 4000);
const allowedOrigin =
  process.env.MOCK_DASHBOARD_ALLOWED_ORIGIN ?? "http://localhost:5173";

const payload = {
  awards: 0,
  awardHistoryRecords: 0,
  proposals: 0,
  proposalHistoryRecords: 0,
  negotiations: 0,
  subawards: 0,
  documents: 0,
};

createServer((req, res) => {
  res.setHeader("Access-Control-Allow-Origin", allowedOrigin);

  if (req.url === "/api/dashboard") {
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify(payload));
    return;
  }

  res.statusCode = 404;
  res.end();
}).listen(port, () => {
  console.log(`Mock dashboard stub listening on http://localhost:${port}`);
});
```

Usage:

```bash
# Terminal 1: start the stub (defaults to port 4000)
node ui/scripts/mock-dashboard-server.mjs

# Terminal 2: point the UI at it instead of a real API, in .env.local
# VITE_API_BASE_URL=http://localhost:4000
cd ui && npm run dev
```

Only `GET /api/dashboard` is mocked — any navigation past the landing
page (Awards, Proposals, search, etc.) will error against the stub,
which is expected and out of scope for this milestone.

---

## Phase 2: Group Sync (Real Authorization Parity)

Required before this replaces the existing manually-curated
`aws_cognito_user_in_group` allowlist in
`terraform/modules/cognito/main.tf`. Not required for Phase 1's "can I
log in" milestone.

### 2.1 Why This Is Needed

SAML `attribute_mapping` (Phase 1) populates Cognito **user
attributes** (e.g. `custom:entitlement`) — it does **not** populate
real Cognito **group membership**. `SecurityConfiguration.java` reads
`cognito:groups`, which only reflects actual group membership. Without
this phase, every JIT-provisioned user has no groups and fails every
`AttachmentAuthorizationService.requireAttachmentAccess()` check.

### 2.2 Pre Token Generation Lambda Trigger

New small Lambda (Node.js or Python), wired via
`aws_cognito_user_pool.lambda_config.pre_token_generation`:

```typescript
const ENTITLEMENT_TO_GROUP: Record<string, string> = {
  "urn:mace:bu.edu:group:ArchiveViewer": "ArchiveAttachmentViewer",
};

exports.handler = async (event: any) => {
  const raw = event.request.userAttributes["custom:entitlement"] ?? "";
  const entitlements = raw.split(",").map((s: string) => s.trim());
  const groups = entitlements.map((e) => ENTITLEMENT_TO_GROUP[e]).filter(Boolean);

  for (const group of groups) {
    await cognito.adminAddUserToGroup({
      UserPoolId: event.userPoolId,
      Username: event.userName,
      GroupName: group,
    }).promise();
  }

  return event;
};
```

Runs on every login, so entitlement changes at BU (e.g. a user losing
group access) are reflected on next sign-in without any manual step —
matching the "shadow, don't own" model.

### 2.3 Terraform Additions

- Lambda function resource + IAM role (`cognito-idp:AdminAddUserToGroup`,
  `cognito-idp:AdminRemoveUserFromGroup` scoped to this User Pool only)
- `aws_lambda_permission` allowing Cognito to invoke it
- `lambda_config { pre_token_generation = aws_lambda_function.group_sync.arn }`
  on `aws_cognito_user_pool.this`

### 2.4 Phase 2 Verification

1. Confirm a test account with the mapped BU entitlement receives
   `ROLE_ArchiveAttachmentViewer` after login (inspect the ID/access
   token's `cognito:groups` claim)
2. Confirm a test account **without** the entitlement does not receive
   the group, and attachment endpoints correctly return 403
3. Confirm removing the BU-side entitlement removes group membership
   on next login (not just at initial JIT provisioning)

---

## Phase 3: Cutover and Cleanup

1. Remove `"COGNITO"` from `supported_identity_providers` once
   Shibboleth is validated in the target environment (if native login
   should no longer be offered)
2. Replace the manually-curated `aws_cognito_user_in_group`/
   `attachment_viewer_usernames` allowlist with the Phase 2 Lambda as
   the sole source of group membership
3. Update UI copy/branding if any "Sign in" button text assumed native
   Cognito login
4. Update `docs/api/reference.md` and any onboarding docs describing
   how users get access

---

## Relevant Files

- `terraform/modules/cognito/main.tf` — new `aws_cognito_identity_provider`, updated `supported_identity_providers`, Phase 2 Lambda wiring
- `terraform/modules/cognito/variables.tf` — new `shibboleth_idp_metadata_url`, `shibboleth_require_signed_requests` variables
- `terraform/environments/*/main.tf` — pass through new Cognito module variables, add `VITE_COGNITO_IDENTITY_PROVIDER` to Amplify `environment_variables`
- `ui/src/auth.ts` — `login()` targets the named IdP directly
- `ui/src/AuthGate.tsx` — new `VITE_AUTH_REQUIRED` local-dev bypass
- `ui/.env.example` — new `VITE_COGNITO_IDENTITY_PROVIDER`, `VITE_AUTH_REQUIRED` entries
- New Lambda source (Phase 2 only) — location TBD (e.g. `terraform/modules/cognito/lambda/group-sync/`)

## Explicitly Out of Scope

- CloudFront/Lambda@Edge (that's Option 1 — see `SHIBBOLETH-PLAN.md`)
- Any change to `SecurityConfiguration.java`'s claim name or JWT
  decoder — Cognito continues issuing the same token shape, so no
  backend code changes at all
- Any private key, certificate, or SAML signing material in `VITE_*`
  variables, browser code, or frontend build output

---

**Document Version**: 1.0
**Date**: 2026-09-19
**Branch**: `shibboleth`
**Status**: Draft for review
