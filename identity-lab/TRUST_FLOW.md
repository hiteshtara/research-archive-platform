# Local SAML integration—Cognito simulated: trust flow

**Status:** a local test lab with fictional users, test-only keys and a
simulated Cognito.

- BU Shibboleth is **not** connected, and no BU credentials are used.
- No AWS resources are created, read or changed.
- A successful login here is **not** complete authorization. The unfinished
  paths are listed at the end.

## 1. Packaging choice

| Option | Status | Used? |
|---|---|---|
| Shibboleth IdP 5 installed into Jetty 12 or Tomcat 10/11 (Consortium install guide) | the only **officially supported** way to deploy | no: it's a manual installation, not a disposable lab |
| `i2incommon/shib-idp` (InCommon Trusted Access Platform) | maintained by Internet2 for InCommon members; Shibboleth IdP 5.2.3 on Tomcat 11, Java 17 (Corretto), Rocky 10, multi-arch (amd64 + arm64) | **yes**, pinned to `5.2.3_20260618_rocky10_multiarch` |
| Community images (CSC Finland, DAASI, Unicon) | third-party | no |

**The image contains the real Shibboleth IdP 5.2.3 software, unmodified.** The lab only:

- mounts its own configuration files;
- generates fresh test-only keys at start-up.

These replace the image's bundled `idp-signing`, `idp-encryption`, sealer and
TLS keys, so none of the image's keys are used.

**Pinning:** every image is pinned by its multi-arch index digest, because a
version tag alone can be re-pushed. The IdP image is
`i2incommon/shib-idp:5.2.3_20260618_rocky10_multiarch@sha256:ff03ee2e9ded82b10fa4d6f4af8f27f03e9a6aab3b88157750ca00d3e2a5c35c`.

**Lab changes to the stock IdP configuration:**

- Password login checks the lab directory by bind and search.
- Attributes are resolved from the lab directory.
- The persistent NameID is computed from `labInstitutionalId`, not from the
  login name.
- Sessions are stored server-side in memory, because SAML single logout must
  find a session by NameID.
- The logout page finishes in the main window, so the user returns to the
  application. The stock page completes logout in a hidden iframe and leaves
  the user on the IdP page.

**Outbound traffic, as inspected rather than assumed:**

- **Usage beacon.** `sendtierbeacon.sh` runs from cron and posts the image
  name and version to `collector.testbed.tier.internet2.edu:5001` unless
  `TIER_BEACON_OPT_OUT` is set. The lab sets it.
- **Sealer rotation.** `rotateSealerKey.sh` regenerates the sealer key locally.
  Its scp only targets `idp.sealer._sync_hosts`, which defaults to the
  container itself and is skipped, so it is left at the default.
- **Isolation is not taken from these settings.** Every lab service is on an
  `internal: true` Docker network with no route off the host. The automated
  tests verify that the IdP, the directory and the simulator cannot reach the
  internet.

**Inspected locally** (`docker image inspect`, and the files in the image):

- start-up script `/usr/bin/startup.sh`, which rebuilds the WAR and runs supervisord with Tomcat;
- HTTPS on container port 443;
- configuration in `/opt/shibboleth-idp/conf`;
- TLS key and certificate at `/opt/certs/idp-default.{crt,key}`.

## 2. Components (all on the loopback interface)

| Component | Role | Address |
|---|---|---|
| `lab-ldap` (OpenLDAP on Alpine, built locally) | directory of **fictional** accounts and attributes | Docker network only, no host port |
| `lab-idp` (Shibboleth IdP 5.2.3) | real SAML 2.0 IdP: password login against `lab-ldap`, attribute release, NameID generation, SAML single logout | `https://localhost:8443/idp` |
| `lab-cognito` (Python, pysaml2) | **simulated Cognito user pool**: SAML SP, user-pool profiles, OAuth2 hosted-UI endpoints, token signing, user-pool API subset | `https://localhost:9443` |
| `pool-db` (Postgres 17) | the simulated user pool's own store | internal only |
| `archive-db` (Postgres 17) | the disposable archive database (migrations + synthetic seed), plus the lab crosswalk and the Kuali-role evidence | `127.0.0.1:55433` |
| `edge-*` (socat) | relay host loopback ports into the internal network | publish on `127.0.0.1` |
| API (`-Pauthz-demo`, profile `identity-lab`) | the **production** `SecurityConfiguration`, JWT validation and record enforcement, pointed at the lab issuer | `http://localhost:8092` |
| UI (Vite mode `identity-lab`) | the **production** `auth.ts` Amplify sign-in, pointed at the simulator; banner "Local SAML integration—Cognito simulated" | `http://localhost:5198` |

Every published port is bound to `127.0.0.1`.

**Module boundary:**

- `identity-lab/core/` holds the directory, the IdP, the simulated Cognito
  and the relays, and has no archive knowledge. It runs on its own with an
  example account.
- `identity-lab/archive/` holds the overlay compose file, the fictional
  accounts and groups, the enrollment hook, the crosswalk and evidence tables,
  and the tests.
- The archive API's lab profile is in `api/src/authz-demo`, which is compiled
  only with `-Pauthz-demo`.
- The core reaches the archive only through `LAB_POST_SIGN_IN_HOOK`, an
  optional post-sign-in function (like a Cognito trigger).

## 3. Trust relationships

```
 lab CA (generated per run, .identity-lab/ca)          never committed
   ├─ TLS cert for localhost:8443 (IdP)
   └─ TLS cert for localhost:9443 (simulated Cognito)
       → the API's JVM truststore contains only this CA (lab run only)

 IdP signing key  ── its certificate is in IdP metadata ──▶ trusted by the SP
 SP signing key   ── its certificate is in SP metadata ───▶ trusted by the IdP
 Token signing key (RSA) ── published at the issuer's JWKS ─▶ trusted by the API
```

- **IdP → SP:** the IdP loads the SP's metadata from a local file (no
  metadata federation). The SP loads the IdP's metadata from a local file.
  - Responses must be **signed** by the IdP signing key.
  - Assertions are **encrypted** to the SP encryption key.
- **SP → user pool:** a verified assertion creates or updates the user-pool
  profile. As in Cognito:
  - username = `<provider>_<NameID>`;
  - `sub` is generated once, on the profile's first sign-in;
  - mapped attributes are overwritten on every sign-in;
  - `identities` records the provider name and the NameID.
- **User pool → API:** the API's unchanged `SecurityConfiguration` validates
  the access token:
  - signature, from the JWKS at `{issuer}/.well-known/jwks.json`;
  - `iss`;
  - `exp`;
  - `token_use=access`;
  - `client_id`.

  The issuer and client are set through the same `COGNITO_ISSUER_URI` and
  `COGNITO_CLIENT_ID` variables production uses. **No production Cognito
  setting is changed.**
- **API → archive identity:** an access token says *who signed in*, not what
  they may see.
  - `JwtCurrentIdentityProvider` passes `(iss, sub)` to `authz.identity_link`.
  - Grants and suspension are then evaluated, as in the authorization demo.

## 4. Enrollment: how `(iss, sub)` becomes a person

This is the lab's simulation of design §12.2 ("trusted provisioning"). **It is
not implemented in the API.**

1. After a successful SAML sign-in, the lab enrollment step reads that
   user-pool profile by `sub`.
2. It accepts the profile only if **all** of these hold:
   - `identities` names the lab SAML provider. A native profile is refused.
   - The institutional-identifier attribute is present.
   - That identifier exists in the lab crosswalk (`identity_lab.person_registry`,
     institutional identifier → Kuali person id), which lives in the lab
     application database, **not** in Shibboleth.
   - No **other** active link exists for the same identifier and issuer.
3. If every check passes, it writes `authz.identity_link (method = AUTO_VERIFIED)`.
4. On every later sign-in, it compares the profile's identifier with the
   linked one. A mismatch, such as a reassigned login name, revokes the link
   and records the reason. It never moves the link to the new value.
5. Every decision is written to `identity_lab.enrollment_event`.

A sign-in that fails enrollment still gets a valid token. The API then
answers `ACCESS_NOT_PROVISIONED`, which is the intended fail-closed result.

## 5. Login, token and logout sequence

```
Browser ─▶ UI /login ─▶ Amplify signInWithRedirect
  ─▶ https://localhost:9443/oauth2/authorize (PKCE, state)
  ─▶ SAML AuthnRequest (HTTP-Redirect, signed, NameIDPolicy = persistent)
  ─▶ https://localhost:8443/idp/profile/SAML2/Redirect/SSO
       IdP password form ─▶ LDAP bind as that user
  ◀─ SAML Response (HTTP-POST, signed, encrypted assertion)
  ─▶ https://localhost:9443/saml2/idpresponse
       verify ─▶ update the user-pool profile ─▶ enrollment ─▶ one-time code
  ◀─ redirect to http://localhost:5198/?code=…&state=…
Amplify POSTs /oauth2/token (code + PKCE verifier) ─▶ access, ID and refresh tokens
UI ─▶ API with Bearer access token ─▶ JWT validation ─▶ (iss, sub) ─▶ grants ─▶ results

Logout: Amplify signOut({global:true})
  ─▶ GlobalSignOut on the simulator (refresh tokens revoked)
  ─▶ /logout?client_id&logout_uri ─▶ SAML LogoutRequest ─▶ IdP ends its session
  ─▶ LogoutResponse ─▶ back to the UI
```

**Expected limitation, shown in the lab:**

- An access token already issued stays valid until it expires, as Cognito
  JWTs do. The lab sets a 10-minute lifetime.
- Suspension and grant revocation take effect on the **next API request**,
  because the API re-reads them per request.
- Logging out does not invalidate a copied access token.

## 6. NameID scenarios

| Scenario | NameID | Result |
|---|---|---|
| Normal, repeated sign-in | persistent, computed from **`labInstitutionalId`** with a lab-only salt that survives restarts | the same NameID, profile, `sub` and archive person every time |
| Login name changed (LDAP rename), same institutional ID | **unchanged** | the same profile and person; only the `custom:login` attribute changes |
| Login name reused by a different person (different institutional ID) | different | a new profile, `sub` and person, with **no** permissions inherited from the previous holder of the name |
| Institutional ID attribute missing | none can be computed | the IdP answers `InvalidNameIDPolicy` and sign-in fails |
| Transient (switch set in the simulator) | new on each sign-in | a new profile and `sub` each time. The identifier is already linked to another `sub`, so enrollment refuses: **not provisioned** |

**The salt** is generated once into `.identity-lab/creds/secrets.env` (git-ignored)
and kept by `stop.sh` and `start.sh`. Only `reset.sh` deletes it, which gives
everyone new NameIDs.

## 7. What this lab does not show

- **BU-specific facts**, which stay open questions for BU IAM:
  - BU's real NameID format;
  - BU's real attribute names or OIDs;
  - whether BU will release a stable identifier to a Cognito SP;
  - Duo/MFA behaviour.
- **Real Cognito behaviour** beyond what the simulator implements:
  - attribute-mapping edge cases;
  - case-insensitive pools;
  - the refresh-token lifetime;
  - the hosted-UI error pages.
- **Unfinished authorization paths**, which are listed in
  `scripts/identity-lab/README.md` and are the same as in the authorization
  demo:
  - Negotiation, Subaward and IRB are closed for non-Central users;
  - report PDFs, Archived File Finder, Explorer, Document Explorer and AI are
    closed for non-Central users;
  - the real IO field is unresolved.
