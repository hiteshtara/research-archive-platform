# Comment History (deduplication direction)

## The rule

When a value is copy-forward-repeated unchanged across many versions,
Kuali's history view attributes it to the **first (oldest) version where
it appeared** — not the most recent version that merely still carries the
same, unchanged value. The representative row is the *origin* of a run of
identical values, not the *latest copy* of it.

`AwardCommentServiceImpl.filterAwardComment` (see [Award
Comments](Award%20Comments.md)) walks its result set in
`findMatching`'s natural (insertion/oldest-first) order and keeps only
the **first** row of each run of consecutive identical text:

```java
if (comments.isEmpty() || !awardComment.getComments().equals(comments.get(comments.size()-1))) {
    returnList.add(awardComment);
    comments.add(awardComment.getComments());
}
```

Every later row with the same text is silently dropped from the result —
not shown as a duplicate, not used to "refresh" the displayed date.

## Why this matters

This is easy to get backwards, and getting it backwards produces
plausible-looking but wrong output: the collapsed group's text is
correct either way, but the **metadata attached to it (Award Version,
Updated Date, Updated By) is wrong** if the newest occurrence is kept
instead of the oldest.

Confirmed against real data: Award 100330-00001 / award_id 3038231's
General Comments history has one comment text that was saved unchanged
across **9 separate Award versions spanning 2014-03-11 through
2021-09-20** (award_ids 877063 → 3038231). Kuali attributes that comment
to its 2014-03-11 origin (award_id 877063, sequence 4, user `prokorym`)
— not its 2021-09-20 copy (award_id 3038231, sequence 12, user
`mlmacd`), even though the 2021 row is the one currently attached to the
Award's present-day version.

This project's first implementation of this rule got it backwards (kept
the newest occurrence), and the bug was only caught by comparing live
data against the real Kuali source rather than assuming which direction
was "obviously" correct.

## The general shape

This is a general pattern, not specific to comments: any feature that
walks a version family and collapses runs of unchanged values needs to
decide, explicitly, whether the retained representative is the
introduction point or the latest carry-forward. Kuali's answer, at least
for `AwardComment`, is the introduction point. Don't assume this
generalizes to other version-spanning history views (e.g. Time and
Money's "last action") without checking that feature's own Kuali source
— see [Time and Money](Time%20and%20Money.md) for a case where the
correct scope (family-wide totals vs. version-scoped totals) turned out
to be a *different* axis of the same kind of assumption-checking.

## Evidence

- `AwardCommentServiceImpl.filterAwardComment` —
  `coeus-impl/src/main/java/org/kuali/kra/award/service/impl/AwardCommentServiceImpl.java`.
- Live-verified via a corrected ECS loader run against Award
  100330-00001's real `archive.award_comment` rows (award_ids 224,
  875677, 875833, 877063, 1133724, 1153439, 1284132, 1323390, 1415859,
  3025758, 3037985, 3038231).
