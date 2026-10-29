# Authorization activation — staged plan

**Nothing in this document has been executed.** Enforcement is OFF, there
are zero research-data grants, and no stage below has started.

## Verified state (2026-10-29)

| | |
|---|---|
| API | revision **84**, 1/1 healthy, `@sha256:c9f3bc99…` |
| Database role | `archive_api` (least privilege), migrations **V082–V086** |
| First ARCHIVE_ADMIN | **`ARP-FK7QY195-QW5HV7G0`**, membership 3, link 4, ACTIVE |
| Research-data grants | **zero** |
| Enforcement | **OFF** — no variable set; property defaults false |
| Admin API | **404** for everyone, by design, while enforcement is off |
| Terraform | no drift |
| Subaward nightly | `loader:237` |

## Stage 1 — operator-only grant CLI

**Design.** A second `ApplicationRunner` beside `ArchiveAdminBootstrapCli`,
same shape, because that shape is now proven twice in DEV:

- `@Profile("grant-admin")` — no deployment sets it
- runs with `spring.main.web-application-type=none`; no port opens
- every value an explicit flag; nothing read from a token or looked up by
  email
- `--dry-run` the default; writing requires `--confirm`
- delegates to the existing `ArchiveAdminService.grantAccess` /
  `modifyAccess` / `revokeAccess`, which are already `@Transactional` and
  already write the paired audit row — **no new write path, no new audit
  path**
- the acting administrator is `ARP-FK7QY195-QW5HV7G0`, so
  `requireAdministrator` is satisfied by a real administrator rather than
  a bypass

```
--aws-account-id --environment --operator --reason
--grantee=<ARP>  --type=CENTRAL|UNIT|IO
--unit-number=<n> [--include-descendants]   (UNIT)
--io-value=<v>                              (IO)
[--confirm]
```

**Why this and not the admin API first:** it adds no HTTP surface, reuses
a verified pattern, and can provision the first grants while enforcement
stays off — which is the only way to avoid a window where enforcement is
on and nobody has a grant.

## Stage 2 — provision approved grants, enforcement still OFF

Grants take effect the moment enforcement is switched on, so they must
exist first. Each grant needs its own written approval: grantee ARP,
type, unit or IO value, and reason.

## Stage 3 — verify grants and audit

Read-only, through the tunnel: `access_grant` rows match what was
approved; one `ACCESS_GRANTED` audit row each, actor =
`ARP-FK7QY195-QW5HV7G0`; no unexpected rows.

## Stage 4 — test against real DEV data, enforcement still OFF

Run the acceptance matrix
([`AUTHORIZATION_ACCEPTANCE_MATRIX.md`](../security/AUTHORIZATION_ACCEPTANCE_MATRIX.md))
in disposable Testcontainers fixtures with enforcement **on in the fixture
only**, seeded from the real record identifiers recorded there. This is
the one stage that can prove the rules without exposing DEV.

## Stage 5 — decouple the admin API (separate change)

`app.authorization.admin-api-enabled`, default false, independent of
record enforcement. The current gate's stated reason — *"changes made
through it would have no effect"* — **is no longer true**: grants made
while enforcement is off take effect when it is switched on.

Security implications, stated plainly: this creates a real administrative
HTTP surface. It is protected by Cognito JWT, `requireAdministrator`
(which needs an ACTIVE membership resolved from an identity link), and an
audit row per change. It does **not** weaken record enforcement — those
are separate flags. The risk is that an administrator's Cognito session
becomes sufficient to change access; today that requires an ECS task and
AWS credentials. That is a genuine reduction in difficulty and should be
approved on its own merits.

## Stage 6 — enable enforcement

One property. Before flipping it:

- grants exist for everyone who needs access (Stage 2)
- the matrix passes (Stage 4)
- rollback is rehearsed (below)

**With zero grants, enabling enforcement makes every record invisible to
every user, including the administrator.** That is correct behaviour, and
it is why Stage 2 precedes Stage 6.

## Stage 7 — acceptance criteria

| Criterion | Measure |
|---|---|
| Authorized access unchanged | matrix cases 1,2,4,5,7,9,10,12,14 return 200 with expected payloads |
| Denials are 404 | cases 3,6,8,11,13,15–20, indistinguishable from missing |
| Counts agree | Dashboard count == page total for the same scope |
| No privilege errors | zero `permission denied` in CloudWatch |
| Audit intact | one row per administrative change, zero unexplained rows |
| Performance | scoped list endpoints within 2× unscoped latency |

## Stage 8 — rollback

Enforcement is a property, so rollback is a task-definition change:
remove the enforcement variable, apply, wait for `services-stable`.
Revisions 84/83/82/81 remain ACTIVE and their images are ECR-protected.

**Grants are not rolled back by disabling enforcement** — they simply stop
being consulted. That is deliberate: a rollback must not destroy
provisioning work.

Target: under 10 minutes, no database change, no grant change.
