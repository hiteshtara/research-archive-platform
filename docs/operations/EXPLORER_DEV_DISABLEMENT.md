# Explorer disabled in DEV — change record (2026-11-17)

**Why.** Independent QA finding **H4**. The Archive Explorer
(`/api/v1/explorer/**`) has no record-level authorization:
- proposal discovery returns every proposal's title, PI and sponsor;
- workflows return any award's title and status;
- persons, rolodex and units are ungated.

Explorer is **not in the RAP release scope**. The owner approved disabling it
in DEV, and excluding it from the release configuration, until record-level
authorization exists for it.

**Approval.** Owner, 2026-11-17. This covers DEV only. Production was not touched.

## What changed

| Layer | Before | After | How |
|---|---|---|---|
| API | ECS `research-archive-platform-dev-api:104`, `APP_EXPLORER_ENABLED=true` | **`:105`**, `APP_EXPLORER_ENABLED=false` | Registered from 104 with that one value changed (same image `sha256:b0648a8b…1415`), then `update-service`. Rolling, 100% min healthy; stable |
| UI | Amplify app env `VITE_EXPLORER_ENABLED=true` | `false` | `update-app` with the full env map, one key changed; **Amplify job 207** (BUILD/DEPLOY/VERIFY SUCCEED) |
| Terraform (repo) | `explorer_enabled_ui = true` | `false` | This PR |
| Terraform (local, untracked `terraform.tfvars`) | `APP_EXPLORER_ENABLED = "true"` | `"false"` | Edited in place; backup `terraform.tfvars.bak-pre-explorer-disable-20261117` |

**What did not change.**
- No other environment variable, security flag, image, migration, grant
  or identity.
- Administration writes stay off and record enforcement stays off.
- The ECS service ignores `task_definition` in Terraform
  (`ignore_changes`), so Terraform does not move the service.

## Dependency check (before the change)

- **UI:** only `ExplorerPage` and `ProposalDiscoveryPage` call
  `/api/v1/explorer/**`. Both routes render only when
  `VITE_EXPLORER_ENABLED === "true"`.
- **API:** only `ExplorerController` uses `ExplorerService`. The other matches
  are comments. `DocumentExplorer*` is a separate, unrelated module.
- **Server-side switch:** `ExplorerController` carries
  `@ConditionalOnProperty(name = "app.explorer.enabled", havingValue = "true")`.
  With the flag false, no Explorer route is registered at all.

## Verification (live DEV, signed-in BU session, read-only GETs)

**Before** (revision 104):
- `/api/v1/explorer/proposals` returned 200 with data. The other Explorer
  routes were registered and returned 400 on bad parameters.
- All in-scope checks returned 200.

**After** (revision 105, Amplify job 207):
- All 11 Explorer routes return **404**: `awards`, `award-versions`,
  `workflows`, `units`, `unit-administrators`, `award-contacts`, `persons`,
  `rolodex`, `sponsors`, `attachments` and `proposals`.
- The in-scope checks still return 200:
  - Awards search, Proposals search, Negotiations, Subawards
  - Documents (`/api/v1/documents` and `/api/documents/search`)
  - Dashboard, Global Search
  - Award summary, Award funding-proposals
  - `/api/v1/me/capabilities`
- UI: `/explorer` redirects to the Dashboard and no Explorer link is
  rendered. Bundle `index-CKqfThMd.js`.

## Rollback

```bash
export AWS_PROFILE=bu-nprd   # account 770203350335
# API: return the service to revision 104 (still ACTIVE)
aws ecs update-service --cluster research-archive-platform-dev-api \
  --service research-archive-platform-dev-api \
  --task-definition research-archive-platform-dev-api:104
aws ecs wait services-stable --cluster research-archive-platform-dev-api \
  --services research-archive-platform-dev-api
# UI: flip ONLY VITE_EXPLORER_ENABLED back to true (update-app replaces the
# whole map, so read it, change one key, write it back), then rebuild
aws amplify get-app --app-id d288p9gmoteftb --query 'app.environmentVariables' \
  --output json > env.json
python3 -c "import json;d=json.load(open('env.json'));d['VITE_EXPLORER_ENABLED']='true';json.dump(d,open('env.json','w'))"
aws amplify update-app --app-id d288p9gmoteftb --environment-variables file://env.json
aws amplify start-job --app-id d288p9gmoteftb --branch-name main --job-type RELEASE
# Terraform: revert this PR, and restore the tfvars backup
```

Do not re-enable Explorer until its endpoints apply the record-level
authorization predicate, and the owner approves.

## Release configuration

The release must keep `APP_EXPLORER_ENABLED` unset or `false`. The
application default is `false`. It must also keep `VITE_EXPLORER_ENABLED`
unset or `false`.
