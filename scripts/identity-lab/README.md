# Local SAML integration—Cognito simulated

**What this is:** a local lab where you sign in through a **real Shibboleth IdP 5.2.3**
with **fictional** accounts.

- A **simulated** Cognito user pool issues the tokens.
- The archive API checks them with its **unchanged production** token validation and
  record enforcement.
- BU Shibboleth is not connected, and no BU credentials are used.
- AWS is not contacted.

**A successful login is not complete authorization.**

How it works is in [`identity-lab/TRUST_FLOW.md`](../../identity-lab/TRUST_FLOW.md).

## Start, test and stop

```
scripts/identity-lab/start.sh          # first start generates TEST keys + salt into .identity-lab/
open "http://localhost:5198/awards/search?q=SYNTHETIC"
scripts/identity-lab/test.sh           # 21 end-to-end tests against the running lab
scripts/identity-lab/acceptance.sh --alternatives   # Award requirement matrix, both policy choices
scripts/identity-lab/test.sh --browser # + Chromium walkthrough (PI, Department, Central, IO)
scripts/identity-lab/stop.sh           # keeps keys, salt, directory and databases
scripts/identity-lab/reset.sh          # deletes all of it: everyone gets new NameIDs
```

- **Requirements:** Docker, Java 21 and Maven, Node 20+, and `uv`.
- **Ports**, all on 127.0.0.1:
  - IdP: 8443
  - simulated Cognito: 9443
  - archive database: 55433
  - API: 8092
  - UI: 5198
- The browser warns about the lab's test certificates on ports 8443 and 9443. They are
  signed by a CA generated per lab, which is not installed anywhere.

## Fictional accounts

These are in `identity-lab/archive/fixtures/users.tsv`. The passwords are test-only.

| Login | Password | Institutional ID | Archive access |
|---|---|---|---|
| `lab-central` | `Lab-Central-2026` | SYN-INST-0001 | CENTRAL: all 9 Award families |
| `lab-dept` | `Lab-Dept-2026` | SYN-INST-0002 | DEPARTMENT SYN-U-100: A and A's child |
| `lab-pat` | `Lab-Pat-2026` | SYN-INST-0003 | contact-derived: A (PI), C (Co-PI/MPI), D (COI); not E (KP), not B; no attachment group |
| `lab-io` | `Lab-Io-2026` | SYN-INST-0005 | OTHER AUTHORIZED VIEWER, synthetic IO SYN-IO-7001: F only |
| `lab-multi` | `Lab-Multi-2026` | SYN-INST-0006 | union: C, D, E, H |
| `lab-nogrants` | `Lab-Nogrants-2026` | SYN-INST-0007 | linked, no grants: not provisioned (it has fictional Kuali-role *evidence*, which grants nothing) |
| `lab-suspended` | `Lab-Suspended-2026` | SYN-INST-0009 | suspended: access denied |
| `lab-stranger` | `Lab-Stranger-2026` | SYN-INST-0099 | not in the crosswalk: not provisioned |
| `lab-noattr` | `Lab-Noattr-2026` | (missing) | the IdP cannot issue a NameID: sign-in fails |
| `lab-kim-pi` | `Lab-Kim-Pi-2026` | SYN-INST-0011 | KIM principal who is PI on Award J, with **no grant rows**: sees J only |
| `lab-kim-only` | `Lab-Kim-Only-2026` | SYN-INST-0012 | KIM principal who is nobody's contact: not provisioned |
| `lab-kim-inactive` | `Lab-Kim-Inactive-2026` | SYN-INST-0013 | principal departed after import: refused |
| `lab-kim-ambiguous` | `Lab-Kim-Ambiguous-2026` | SYN-INST-0014 | ambiguous mapping, rejected at import: unknown |
| `lab-rolodex` | `Lab-Rolodex-2026` | SYN-INST-0016 | non-employee (rolodex) id, rejected at import: unknown |

**Enrollment** is the archive API's production code, `IdentityEnrollmentService`.

- It reads the profile through the simulated Cognito's `AdminGetUser`.
- The crosswalk in `authz.principal_crosswalk` is imported at start with the production admin CLI, `scripts/authz-admin`.
- Every decision is in `authz.access_audit`; `admin.sh status` shows the latest ones.

## Live administration (`scripts/identity-lab/admin.sh`)

```
admin.sh suspend SYN-INST-0002            # applies to the user's next API request
admin.sh unsuspend SYN-INST-0002
admin.sh revoke-grant SYN-INST-0001 CENTRAL
admin.sh restore-grant SYN-INST-0001 CENTRAL
admin.sh rename-login lab-pat lab-pat2    # same person: same NameID and archive identity
admin.sh nameid transient                 # every sign-in becomes a new profile: fails closed
admin.sh nameid persistent
admin.sh status                           # identity links, enrollment decisions, user-pool profiles
```

## What is proven, and what is not

**Proven, with fixtures:**

- real SAML sign-in, logout (SAML single logout), and the archive's token
  validation and enforcement;
- stable NameID behaviour;
- fail-closed enrollment;
- live suspension and revocation;
- network isolation.

**Not proven:**

- BU's real NameID format, attribute names and stable identifier;
- Duo or MFA;
- real Cognito behaviour beyond the simulated subset.

**Still unfinished authorization paths** (the same as in the persona demo):

- Negotiation, Subaward and IRB, report PDFs, Archived File Finder, Explorer,
  Document Explorer and AI are **closed** for non-Central users. "Closed" means
  refused; it is not coverage.
- The real IO field is unresolved.
- The policy choices P3, P4 and P6 use labelled demo settings, which are not
  approved policy.

**Already-issued access tokens stay valid until they expire** (10 minutes in the
lab), as with Cognito. Suspension and grant revocation still apply on the next
request, because the API re-reads them.

## Kept out of deployable builds

- **UI:** the lab code loads only in Vite mode `identity-lab`. CI checks that the
  production `dist/` has no lab strings.
- **API:** the lab code compiles only with `-Pauthz-demo`, into
  `target-authz-demo/`. `DemoClassesAbsentFromDefaultBuildTest` checks that it is
  not on the default classpath.
- **Lab containers:** they live under `identity-lab/` and are not part of any
  archive image.
