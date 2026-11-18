# A8 manual UAT plan: mukadder@bu.edu

Status: **approved 2026-11-18, for Friday 2026-11-20, 10:30–11:30 AM ET,** subject
to the DEV-user notice having been sent and the 09:00 execution checks passing
(package §7.3). Nothing has been executed. No account, link, grant or DEV setting
has been changed to prepare it. Your administrator role and CGS grant 2 stay
unchanged.

## 1. What your DEV identity is today (read-only inspection, 2026-11-18)

| Item | Finding |
|---|---|
| BU Web Login (Shibboleth) user | `BUShibboleth_mukadder@bu.edu`, sub `b438a418-…4038`. Cognito group: only the pool's Shibboleth group |
| Native Cognito user | sub `045894f8-…6af7`, group `ArchiveAttachmentViewer`. **Not linked** to any archive identity |
| Archive identity | `ARP-FK7QY195-QW5HV7G0`, link 4, ADMIN_VERIFIED, no Kuali person ID |
| Administrator | **yes**: the only ACTIVE ARCHIVE_ADMIN (member 3, bootstrapped 2026-10-29) |
| Grants | **one**: grant 2, `UNIT 1250000000` (CGS), no descendants, granted 2026-11-11 by yourself under DEV-APPROVAL-3, expires **2026-12-31T00:00Z** |

Two Awards fall under CGS 1250000000: `212137-00001` (id 3798004) and
`212137-00002` (id 3798014), *Diversifying Curriculum to Improve Student
Learning*.

## 2. Does any of this interfere with testing?

| Question | Answer, with the evidence |
|---|---|
| Does being an administrator widen what records you see? | **No.** Record scope comes only from grants (`AccessScopeResolver`). The administrator role decides only whether you can use Security Administration. With enforcement on you still see Security Administration, and records only through grant 2 |
| Does grant 2 overlap the test records A, B and C? | **No.** A and B are in 2573180018 and C is in 1330420000. Grant 2 has no descendants, so it covers 1250000000 only |
| Can your own account be the Department tester? | **Yes, with no change.** Grant 2 is a narrow, already-expiring Department grant. CGS is your Department, and A/B/C are the "other department" records |
| Can your own account test IO? | **Not without a change that cannot be made today.** It would need an IO grant on `ARP-FK7QY195-…`. `ArchiveAdminService` refuses a self-grant ("a second administrator must"), and you are the only administrator. Creating a second identity for you, or bypassing the service, would defeat that control, so neither is proposed |
| Can your own account test No-Grant? | **Not without revoking grant 2,** which changes your permissions and is not proposed |
| Why can't the synthetic accounts just sign in at nonprod.kuali-archive.bu.edu? | The deployed UI sends every sign-in straight to BU Web Login, with no password screen. A UI you run locally (`localhost:5173`, already a registered callback and allowed by the DEV API's CORS) can use the password sign-in. That needs no DEV change |
| Attachments? | Your BU Web Login user is not in `ArchiveAttachmentViewer`, so Archived Files and attachment tabs return 403 today, with or without enforcement (US-DEP-002). They are not part of this test |

## 3. Proposed approach: no new permissions on your account

| Scenario | Account | Change needed |
|---|---|---|
| **D: Department** | your own BU Web Login account (grant 2, CGS) | none |
| **IO** | synthetic S3 `rap-synth-a6-oav@example.invalid` in the local UI | none (the A8 grant on S3 is already approved) |
| **No-Grant** | synthetic S4 `rap-synth-a6-none@example.invalid` in the local UI. Optionally also your **unlinked** native user, which shows the same message for a different reason | none |
| IO on your own account | deferred until a second administrator is seated, who would issue `IO 9500312705` to you with an expiry of window end + 24h | requires a second administrator (not available) |
| **Central** | deferred until the independent approval workflow is operational | — |

The manual session needs enforcement on, so it can only happen inside an
approved A8 window: the 10:30–11:30 slot in package §7.3, or a second window.
It needs its own approval.

## 4. Before the session (about 10 minutes, the day before)

1. Local UI. In `ui/`, create `.env.local` with:
   ```
   VITE_API_BASE_URL=https://api-dev.app-nprd.aws-cloud.bu.edu
   VITE_AWS_REGION=us-east-1
   VITE_COGNITO_USER_POOL_ID=us-east-1_VJ4ekQ27c
   VITE_COGNITO_CLIENT_ID=seqgmc8sccr22sq8lcafqjcpb
   VITE_COGNITO_DOMAIN=research-archive-platform-dev-770203350335.auth.us-east-1.amazoncognito.com
   VITE_COGNITO_REDIRECT_URL=http://localhost:5173/
   VITE_COGNITO_LOGOUT_URL=http://localhost:5173/
   ```
   Leave `VITE_COGNITO_IDENTITY_PROVIDER` **unset**, so the Cognito password
   screen appears. Then run `npm run dev`.
2. Passwords. Fetch them yourself when needed; they are never shown in chat.
   For example:
   `aws secretsmanager get-secret-value --secret-id rap/dev/synthetic/a6/oav --query SecretString --output text | jq -r .password | pbcopy`.
   The sign-in name is the account's `@example.invalid` address.
3. Use **one browser profile or private window per account**: one for your BU
   login on nonprod, one for S3 on localhost and one for S4 on localhost.
   Sign-in tokens are kept per site, and mixing accounts in one window confuses
   the results.

## 5. The session: what to click and what you should see

Record each step as **PASS**, **FAIL** or **NOT TESTED**, with a screenshot.
Any record from the "must not appear" column appearing is a **stop**: tell the
operator, who rolls back.

### D. Department: your BU account at https://nonprod.kuali-archive.bu.edu

| # | Click | Expected |
|---|---|---|
| D1 | Sign in with BU Web Login. Open **Security Administration → Overview** | Page loads; enforcement shows **ON**, administrator writes **disabled** |
| D2 | **Dashboard** | Awards **2**. Every other count is small and far below today's 40,926 / 33,960 / 10,775 / 3,511 / 491,678 (exact values recorded) |
| D3 | **Awards → Search**, type `212137`, search | Exactly **212137-00001** and **212137-00002** |
| D4 | Open **212137-00001** | Summary loads, lead unit CGS |
| D5 | Its **Time & Money** tab | 5 history entries, as before enforcement |
| D6 | Its **SAP** tab | 2 transmissions |
| D7 | Its **Proposals** (funding proposals) tab | Proposal **01444988 is not listed**: it belongs to sub-unit 1250400000 (CGS Administration), which a no-descendants grant does not cover. *If it is listed, record FAIL-or-REVIEW; it would mean Proposal access follows the Award* |
| D8 | Awards search `212128-00002` (A), then `211144-00007` (B), then `209407-00002` (C) | **No results** each time; no title shown |
| D9 | Browser address bar: `/awards/3784681` (A), then `/awards/3476042` (C) | "not found" page; no title, no sponsor, no unit |
| D10 | **Global Search** for `Alzheimer`, then `National Center on Improving Literacy` | No A, B or C result |
| D11 | **Negotiations**; then address bar `/negotiations/12373` | List shows no Biomedical Genetics or WCEHD negotiation; 12373 is "not found" |
| D12 | **Subawards**, search `3259` | Subaward 3259 (C's) does not appear |
| D13 | **Proposals**, search `01092706` | Not found (Biomedical Genetics proposal) |
| D14 | **Documents**, search `212128-00002`, then `212137-00001` (your own CGS award) | An access-denied message **both times**, never a list. Document search spans all five modules, so under enforcement it is refused for any scoped account rather than filtered (package §7.2). The API says "Access to the archive is denied for this account."; record the exact wording the page shows. A list of documents, even for 212137, is a **FAIL** |
| D15 | **Archived Files** | 403 as today (your BU login is not in `ArchiveAttachmentViewer`): **NOT TESTED**, not a result |
| D16 | **Global Search** for `IRB` or a known protocol number | No IRB results: shared search drops IRB while enforcement is on |

Must not appear anywhere in D: `212128-00002`, `211144-00007`, `209407-00002`,
the titles *Genetics of Alzheimer Disease…*, *Asian Cohort for Alzheimer's
Disease*, *National Center on Improving Literacy*.

### IO: synthetic S3 at http://localhost:5173

| # | Click | Expected |
|---|---|---|
| I1 | Sign in as `rap-synth-a6-oav@example.invalid` | Dashboard loads; no Security Administration menu |
| I2 | **Dashboard** | Awards **1**; no Proposals, Subawards or Negotiations (IO reaches Awards only) |
| I3 | Awards search `209407-00002` | One result, C |
| I4 | Open C → **Summary**, **Time & Money**, **Versions** | All load; every one of C's 14 versions is reachable (`FAMILY_WIDE`) |
| I5 | C → **Subawards** tab | **Empty**: C's 8 subawards are not shown, because IO never reaches Subawards |
| I6 | Awards search `212128-00002`; address bar `/awards/3784681` | No result; not found |
| I7 | Global Search `Alzheimer` and `Diversifying Curriculum` | No results |
| I8 | Negotiations, Subawards, Proposals pages | Empty lists, not an error |
| I9 (only if the package §7.5 group step was applied) | C → **Attachments** tab | Loads C's attachment list |
| I10 (same condition) | Address bar `/awards/3784681` (A) → Attachments; and **Archived Files**, search `209407-00002` | A: not found. Archived Files: access-denied message (refused for scoped accounts), never a file list |

### NG: No-Grant, synthetic S4 at http://localhost:5173

| # | Click | Expected |
|---|---|---|
| N1 | Sign in as `rap-synth-a6-none@example.invalid` | Every archive page shows **"No archive access has been provisioned for this account"** |
| N2 | Dashboard, Awards search, Global Search, address bar `/awards/3476042` | The same message; **never** an empty archive, a count or a record |
| N2a (only if the §7.5 group step was applied) | Address bar `/awards/3476042`, Attachments tab, and **Archived Files** | The same "not provisioned" message; no file names |
| N3 (optional) | Sign out. Sign in on localhost as your **native** user `mukadder@bu.edu` | The same message (reason: no identity link). Your native user is unlinked, so this shows the unlinked case on your own account without changing anything |

## 6. Cleanup

- **Your account:** nothing to undo; nothing was changed. Grant 2 still
  expires on 2026-12-31; whether to revoke it earlier is a separate decision.
- **Local UI:** sign out of each account (the `/signed-out` page confirms it),
  stop `npm run dev`, and delete `ui/.env.local` if you prefer. It is gitignored.
- **Synthetic accounts and their grants:** removed in the window's cleanup
  (package §2.7): revoke, unlink 5/7/6, disable, schedule secret deletion.
  After that, I1 and N1 sign-ins fail, as intended.

## 7. What this can and cannot prove

- Your session gives **business-observed** evidence for the Department
  scenario on a real BU login, and **operator-observed** evidence for IO and
  No-Grant on synthetic accounts.
- It does not replace BU's own named-account testing, and it changes no BU
  criterion by itself.
- BU completion stays at 25/47 until live evidence supports a change.
