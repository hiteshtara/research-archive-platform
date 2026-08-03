# Developer Deployment

## Status

Implemented as `scripts/dev-deploy.sh`. Covers the dev environment
(account `770203350335`, region `us-east-1`) only.

## What this replaces

Before this script, a normal Award feature deploy meant running each of
these by hand, in the right order, watching each one finish before
starting the next:

```
mvn test                                    (api/)
npm run lint && npm run build               (ui/)
ops/deploy-api.sh                           (build, push, ECS deploy, wait)
curl https://api-dev.app-nprd.aws-cloud.bu.edu/actuator/health
git push bu main && git push origin main
aws amplify list-jobs ...                   (find the auto-triggered build)
aws amplify get-job ... (poll until SUCCEED or FAILED)
```

`scripts/dev-deploy.sh` runs all of it as one command, in this order,
stopping immediately if any step fails:

```
./scripts/dev-deploy.sh
```

## Usage

Run it from anywhere — it resolves its own location and `cd`s
internally, so you don't need to be inside the repo:

```
/path/to/research-archive-platform/scripts/dev-deploy.sh
```

If you're already in the repo, `./scripts/dev-deploy.sh` works the same
way.

### Flags

| Flag              | Effect                                                                 |
|-------------------|-------------------------------------------------------------------------|
| `--check-only`    | Steps 1–3 only (verify / backend tests / frontend build). No Docker build, no ECS deploy, no Amplify, no push. Safe to run anytime. |
| `--skip-backend`  | Skip backend tests and the API Docker/ECS deploy.                      |
| `--skip-frontend` | Skip frontend lint/build and the Amplify wait.                         |
| `--no-push`       | Don't `git push` before checking Amplify (you push yourself).          |
| `--full`          | Ignore change detection; always run both the backend and frontend legs.|
| `-h`, `--help`    | Print usage and exit.                                                  |

Use `--check-only` as a pre-flight sanity check before a real deploy, or
in a pre-push git hook.

## What it does, in order

1. **Verify environment** — checks required tools are installed
   (`aws`, `docker`, `mvn`, `npm`, `git`, `python3`, `curl`), that
   Docker is running, resolves the AWS caller identity fresh (never
   trusts a cached value) and aborts if the account isn't
   `770203350335` or the configured region isn't `us-east-1`, and
   prints the current git branch/commit. Nothing mutating happens
   before this step passes.
2. **Backend tests** — `mvn test` in `api/`. Stops on any failure.
3. **Frontend build** — `npm install` (only if `node_modules` is
   missing or `package-lock.json` is newer), then `npm run lint`,
   `npm run build`, `npm run test`. Stops on any failure.
4. **API Docker build + push + ECS deploy** — delegates to the
   existing `ops/deploy-api.sh` rather than re-implementing its
   account-safety and immutable-tagging logic. Builds the image, tags
   it `<timestamp>-<git-sha>`, pushes to ECR, verifies the tag exists,
   registers a new ECS task definition revision, updates the service,
   and waits for it to stabilize.
5. **Health check** — polls `https://api-dev.app-nprd.aws-cloud.bu.edu/actuator/health`
   (resolved from `terraform output api_url`, with a literal fallback)
   until it reports `"status":"UP"`, retrying for up to a minute.
6. **Amplify** — if there are unpushed commits, pushes them to every
   configured git remote so the Amplify app's webhook picks them up
   (Amplify only ever builds from what's on the remote branch, never
   from local disk). If nothing is new to push but you still want a
   rebuild, triggers one directly via `aws amplify start-job`. Either
   way, polls until the build reaches `SUCCEED`/`FAILED`/`CANCELLED`;
   on failure, fetches and prints each build step's log.
7. **API verification** — always re-checks `/actuator/health`. If
   `COGNITO_TEST_USERNAME` and `COGNITO_TEST_PASSWORD` are both set in
   the environment, also obtains a Cognito access token and exercises
   `GET /api/v1/awards/search`, `.../{awardId}/summary`,
   `.../{awardNumber}/hierarchy`, and `.../{awardId}/versions` against
   whatever real Award the search call returns — no hardcoded test
   Award number. If those two env vars aren't set, these checks are
   reported as `SKIP`, not `FAIL` — **the script never prompts for,
   guesses, or brute-forces a password.**
8. **Report** — a PASS/FAIL/SKIP table for every step, plus git SHA,
   image tag, and total duration. This prints even if the script
   aborted partway through (via a shell `EXIT` trap), so you can always
   see exactly how far it got.

## Change detection (skipping a leg you didn't touch)

If neither `--full` nor `--check-only` is passed, the script diffs your
current branch against its upstream tracking branch (falling back to
`HEAD~1` if there's no upstream, and always including any uncommitted
local changes) to decide whether `api/`/`database/migrations/` or `ui/`
changed:

- Only `ui/` changed → backend tests and the API/ECS deploy are
  skipped.
- Only `api/` changed → the frontend build and Amplify wait are
  skipped.
- Neither changed → the script prints a message and exits immediately;
  nothing is deployed.
- Both changed, or detection is ambiguous (e.g. no git history to diff
  against) → both legs run. Change detection never silently narrows
  scope when it isn't sure — an incorrect "changed" is just a wasted
  test/build; an incorrect "unchanged" would silently skip a real
  deploy.

Pass `--full` any time you want to force both legs regardless.

## Safety

- **Wrong-account protection.** The AWS account is resolved fresh from
  `aws sts get-caller-identity` every run and compared against
  `770203350335` before anything mutating happens — the same discipline
  `ops/deploy-api.sh` already uses (see that script's own header for
  the incident that made this non-negotiable). Set
  `EXPECTED_ACCOUNT_ID` in your environment to deploy to a different,
  intentional target; otherwise a mismatch aborts immediately.
- **No hardcoded credentials.** AWS auth comes entirely from whatever
  credentials/profile are already active in your shell. The only
  optional credentials this script touches — `COGNITO_TEST_USERNAME`
  / `COGNITO_TEST_PASSWORD`, for step 7's authenticated checks — must
  be set by you in your own environment (e.g. via `direnv`/`.envrc`);
  they are never written into this script or any file it creates.
- **No secrets or tokens printed.** The Cognito access token obtained
  in step 7 lives only in a shell variable used inline in an
  `Authorization: Bearer …` header; it is never echoed, logged, or
  included in the final report. If you don't set the two `COGNITO_TEST_*`
  variables, step 7's authenticated checks are skipped outright — this
  script will never prompt you for a password or try to guess one.
- **Never touches Terraform, Cognito, RDS, or ETL.** The only AWS
  mutations are: pushing a Docker image to the existing ECR repository,
  registering a new ECS task definition revision, updating the existing
  ECS service, and triggering an Amplify build on the existing app —
  all of which `ops/deploy-api.sh` and the Amplify Git integration
  already do today. No `terraform apply` runs as part of this script;
  `terraform output` is used read-only, to resolve resource identifiers
  instead of hardcoding them, with literal fallbacks if state isn't
  reachable.

## Prerequisites

- `aws`, `docker`, `mvn`, `npm`, `git`, `python3`, `curl` on `PATH`.
- Docker daemon running locally (for the API image build).
- AWS credentials for account `770203350335`, region `us-east-1`,
  already active in your shell (e.g. `export AWS_PROFILE=bu-nprd` —
  see `ops/AWS_OPERATIONS.md`).
- `terraform` on `PATH` if you want resource identifiers (API URL,
  Amplify app ID, Cognito pool/client IDs) resolved live from state
  rather than the script's literal fallbacks — not required, just more
  robust against those values ever changing.
- Optional, for step 7's authenticated API checks only:
  `COGNITO_TEST_USERNAME` and `COGNITO_TEST_PASSWORD` set to a real
  Cognito dev user's credentials (the pool must have
  `allow_admin_password_auth` enabled, which the dev pool already has —
  see `terraform/modules/cognito`).

To get a token for your own manual `curl`/Postman testing (rather than
this script's own non-interactive use of the same flow), use
`scripts/get-access-token.sh <your-email>` instead — it prompts for your
password interactively and prints only the access token, ready for
`export ACCESS_TOKEN="$(scripts/get-access-token.sh you@bu.edu)"`.

## Troubleshooting

- **"Resolved account (...) != expected"** — you're authenticated to
  the wrong AWS account/profile. Fix your credentials before retrying;
  the script has not changed anything.
- **"ops/deploy-api.sh failed"** — re-run `ops/deploy-api.sh
  --check-only` directly for a more detailed pre-flight check, or read
  its own output above this line for the Docker/ECR/ECS error.
- **Amplify step reports FAILED** — the script already printed each
  build step's log inline; the usual culprits are a frontend build
  error that didn't surface locally (rare, since step 3 already ran the
  same `npm run build`) or an Amplify environment variable drift.
- **All four `/api/v1/awards/...` checks show `SKIP`** — you don't have
  `COGNITO_TEST_USERNAME`/`COGNITO_TEST_PASSWORD` set. This is expected
  and not an error; set both (in your own shell, never in a committed
  file) if you want those checks to run.
