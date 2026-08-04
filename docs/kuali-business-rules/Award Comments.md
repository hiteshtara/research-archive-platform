# Award Comments

## The rule

Kuali's Award Comments screen shows a small, fixed subset of comment
types — not every `COMMENT_TYPE_CODE` that appears on `AWARD_COMMENT`
rows — and it shows each type's history across the **whole Award number
family**, not just the version currently being viewed.

Specifically:

- `COMMENT_TYPE.AWARD_COMMENT_SCREEN_FLAG = 'Y'` is the filter
  (`AwardCommentServiceImpl.retrieveCommentTypes()`). A comment type with
  `screen_flag = 'N'` — e.g. "Current Action Comments" (code `21`) — is
  real, archived data, but Kuali never shows it on this screen.
- `retrieveCommentHistoryByType(commentTypeCode, awardId)` resolves the
  passed-in `awardId` to its `awardNumber` and then queries by
  `awardNumber`, not `awardId` — every version of the Award family is in
  scope, not just the one the user happened to click into.
- Real BU Oracle data (confirmed live, not generic Kuali seed data) has
  only **2 of 23** `COMMENT_TYPE` codes with `screen_flag = 'Y'`: code
  `2` ("General Comments") and code `3` ("Fiscal Report Comments").
  Every other code — Invoice Instructions, Proposal Comments, Current
  Action Comments, Subaward Organization Comments, etc. — is
  screen-hidden. This confirms the earlier caveat about generic Kuali
  seed data (see `docs/DECISIONS.md`'s `UNIT_ADMINISTRATOR_TYPE` note):
  the *codes* are BU's real production values, but which ones are
  screen-visible was not something that could be assumed from the
  generic Kuali demo data alone.

## Why this matters

A naive read of `archive.award_comment` — filtering by the current
`award_id`, showing the raw `comment_type_code` — reproduces neither
Kuali behavior: it under-shows (misses every other version's comments)
and over-shows (surfaces internal-only comment types the real UI hides).
Both bugs existed in this archive's first Award Comments implementation
before this was investigated.

A distinct-but-related discovery — which entries survive when the same
comment type has multiple archived rows across many versions — is
covered separately in [Comment History](Comment%20History.md), since
it's a general pattern, not specific to comments.

## Evidence

- `AwardCommentServiceImpl.java` (`retrieveCommentTypes`,
  `retrieveCommentHistoryByType`, `filterAwardComment`) —
  `coeus-impl/src/main/java/org/kuali/kra/award/service/impl/`.
- Real Award 100330-00001 (`award_id` 3038231): 12 `award_comment` rows
  for type `2`, confirmed live via the deployed ECS loader task after
  loading the real `COMMENT_TYPE` reference table from Oracle.

## See also

[`docs/architecture/AWARD_COMMENT_DESIGN.md`](../architecture/AWARD_COMMENT_DESIGN.md)
for the full schema/ETL mapping, and migration
`V057__create_comment_type.sql` for the archived reference table.
