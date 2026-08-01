# Award Notepad — Design and Implementation Record

## Purpose

Design, then implement, incremental UPSERT support for
`archive.award_notepad` — the last piece of the Award Attachments
subsystem (`archive.award_attachment` already shipped on its own
batch-framework track) — completing that subsystem and closing out one
more row in `KUALI_ARCHIVE_COVERAGE.md`. BU Oracle has 34 real
`AWARD_NOTEPAD` rows; it is not empty, so it belongs in the archive
before the Award domain can be declared complete.

## Scope

Strictly `AWARD_NOTEPAD` / `archive.award_notepad`. Does not touch
Award Reporting, Award Budget, Time and Money, SAP, Proposal,
Negotiation, Subaward, or Protocol. Does not touch
`archive.award_attachment` or its own batch-framework track (a
different Kuali feature — attachments vs. free-text notes — that
happens to share a UI tab, "Notes and Attachments," but has no FK or
data relationship to notes at all).

## Source material used

- Upstream Kuali Coeus source (`/Users/mukadder/kuali-project/kuali-research`,
  read-only): `coeus-impl/src/main/resources/org/kuali/kra/award/repository-award.xml`
  — the `Award` class-descriptor's `awardNotepads` named collection
  (line 203, `inverse-foreignkey field-ref="awardId"`, `orderby
  name="ENTRY_NUMBER" sort="ASC"`) and the full `AwardNotepad`
  class-descriptor (`AWARD_NOTEPAD`, lines 1381–1398).
- `coeus-impl/src/main/java/org/kuali/kra/award/home/Award.java` (the
  `add(AwardNotepad)` method, lines 1588–1592) and
  `coeus-impl/src/main/java/org/kuali/kra/award/notesandattachments/notes/AwardNotepadBean.java`
  (the `addNote` UI action) — read to resolve exactly how
  `entryNumber`/`awardNumber`/`awardId` get assigned at note-creation
  time, since the OJB mapping alone left this ambiguous (see Findings).
- **Real Oracle DDL**, not just the Java OJB mapping (same
  double-verification discipline used for Award Contacts):
  `coeus-db/coeus-db-sql/src/main/resources/co/kuali/coeus/data/migration/sql/oracle/kc/bootstrap/V300_107__schema.sql`
  (the original `CREATE TABLE AWARD_NOTEPAD`, lines 1228–1262,
  including its `UQ_AWARD_NOTEPAD` index) and
  `.../bootstrap/V310_1_030__TBL_AWARD_NOTEPAD.sql` (the later `ALTER
  TABLE ... ADD CREATE_USER` migration, confirming `CREATE_USER` was
  backfilled from `UPDATE_USER` and made `NOT NULL` after the fact).
- BU 7.3 reference tree (`reference/kuali/award/repository-award.xml`):
  the same `awardNotepads` collection is present, confirming BU
  actively uses this feature (consistent with the 34 real rows already
  observed).
- `AWARD_DOMAIN_DECOMPOSITION.md` (Tier 1 Award Attachments entry) and
  `AWARD_CONTACTS_DESIGN.md` (the most recent precedent for
  reintroducing/verifying a table via both Java mapping and real DDL).

## Assumptions

- `NOTE_TOPIC` is a short free-text label typed by the user (not a
  lookup-table code) — confirmed by its `VARCHAR2(60)` type with no
  corresponding `<reference-descriptor>` in the OJB mapping. Stored as
  a bare string, no lookup.
- `RESTRICTED_VIEW` is a Y/N visibility flag (`OjbCharBooleanConversion`
  in the Java mapping) — modeled the same way as every other
  Y/N-flag column in this domain: a bare `VARCHAR` value, not a native
  `BOOLEAN`.

## Findings

### Object graph

```
Award (AWARD)
└── AwardNotepad (AWARD_NOTEPAD)   [MISSING - this work]
```

The simplest object graph of any Award subsystem so far: one flat leaf
table, no children of its own, no lookup-table references at all (no
`<reference-descriptor>` anywhere in its class-descriptor — every
other field is either a scalar or a plain FK-shaped id/code).

### Oracle table, PK/FK mapping

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `AWARD_NOTEPAD_ID` | `NUMBER(12)` | PK | Own dedicated sequence `SEQ_AWARD_NOTEPAD_ID` — **not** `SEQUENCE_AWARD_ID` |
| `AWARD_NUMBER` | `VARCHAR2(12)` | `NOT NULL` | The true family/business key |
| `ENTRY_NUMBER` | `NUMBER(4)` | `NOT NULL` | A per-family ordinal (see below) |
| `NOTE_TOPIC` | `VARCHAR2(60)` | `NOT NULL` | Free text, bare |
| `COMMENTS` | `CLOB` | `NOT NULL` | The note body — can be long |
| `RESTRICTED_VIEW` | `CHAR(1)` | `NOT NULL` | Y/N flag |
| `CREATE_TIMESTAMP` | `DATE` | `NOT NULL` | |
| `CREATE_USER` | `VARCHAR2(60)` | `NOT NULL` | Added later (`V310_1_030`), backfilled from `UPDATE_USER` |
| `UPDATE_TIMESTAMP` | `DATE` | `NOT NULL` | |
| `UPDATE_USER` | `VARCHAR2(60)` | `NOT NULL` | |
| `VER_NBR` | `NUMBER(8)` | `NOT NULL`, default 1 | |
| `AWARD_ID` | `NUMBER(22)` | `NOT NULL` | The specific award_id current when the note was created — see below |
| `OBJ_ID` | `VARCHAR2(36)` | nullable | Not extracted — same deliberate gap as every other Award child table since Custom Data |

`AWARD_NOTEPAD_ID`'s own sequence (`SEQ_AWARD_NOTEPAD_ID`) is yet
another table that does **not** share `SEQUENCE_AWARD_ID` — the fourth
so far (after `award_custom_data_id`, `award_sponsor_term_id`,
`award_rep_terms_recnt_id`), reinforcing that "shares
`SEQUENCE_AWARD_ID`" must be checked per table, never assumed.

There is **no Oracle-level FK constraint** from `AWARD_NOTEPAD` to
`AWARD` at all — only the `AWARD_NOTEPADP1` primary key and the
`UQ_AWARD_NOTEPAD` index (see below). The parent relationship is
enforced only at the Java/OJB layer (`inverse-foreignkey
field-ref="awardId"`), matching `auto-delete="none"` — Kuali itself
never cascade-deletes notes when an Award document is deleted.

### Business meaning

A free-text note/comment thread attached to an Award, entered by staff
through the "Notes and Attachments" tab — an audit-trail-style running
log (topic + comments + who + when), not part of any approval workflow
or calculated data.

### Whether historical sequence versions own notes / family vs. version

**The real unique index, `UQ_AWARD_NOTEPAD ON AWARD_NOTEPAD (AWARD_NUMBER,
ENTRY_NUMBER)`, is the strongest evidence available**: entry numbering
is scoped to the whole **award_number family**, not to one
`sequence_number`/`award_id` — there is no `SEQUENCE_NUMBER` column on
this table at all, the only Award child table found so far without
one. `Award.add(AwardNotepad)` (Java) confirms this at the point notes
are created: `awardNotepad.setEntryNumber(awardNotepads.size() + 1)`
and `awardNotepad.setAwardNumber(this.getAwardNumber())` are both set
explicitly, independent of `sequence_number`.

**One genuine ambiguity could not be fully resolved from static source
alone**: whether Kuali's award-versioning process copies existing
`AwardNotepad` rows forward to a new `award_id` when a new sequence is
created, or leaves them permanently attached to whichever `award_id`
was current at creation time. No `AwardNotepad`-specific copy-forward
code was found in `Award.java` or the versioning-adjacent classes
searched, though Kuali's generic versioning framework could plausibly
handle this reflectively for any child collection without
`AwardNotepad`-specific code existing at all. **This does not weaken
the archive design**: `AWARD_ID` is `NOT NULL` on every note row
regardless of which specific `award_id` it references, and this
subsystem reuses the exact same family-widening mechanism every other
Award child table already uses — `read_award_children_matching_award_ids`
scoped to the full set of a family's `award_id` values. Whether Kuali
copies notes forward or pins them to one historical `award_id`, every
note that exists for a family will have an `AWARD_ID` inside that
family's already-resolved `family_award_ids` set, so it will always be
captured correctly either way. Resolving the ambiguity is not a
prerequisite for a correct archive.

`UQ_AWARD_NOTEPAD` is a **plain, non-unique** `CREATE INDEX` in the
real DDL despite its name (the same "named like a constraint, isn't
one" pattern already seen once before) — not re-enforced as a hard
`UNIQUE` constraint in the archive schema, to avoid rejecting real
Oracle data on an assumption Oracle itself does not actually enforce.

### Update timestamps

Uniquely among Award child tables archived so far, `AWARD_NOTEPAD`
carries **both** `CREATE_TIMESTAMP`/`CREATE_USER` and
`UPDATE_TIMESTAMP`/`UPDATE_USER` — every other table to date has only
the latter pair. Both are captured here (`source_create_timestamp`/
`source_create_user` added alongside the standard
`source_update_timestamp`/`source_update_user`), a small, genuine
schema difference from the established convention, not an
inconsistency to paper over.

### Delete behavior

`auto-delete="none"` at the Java/OJB layer, no Oracle FK to cascade
through anyway (see above) — notes are never deleted when their parent
Award document is deleted. Confirms this is an append-only,
audit-trail-style record: the deletion/reconciliation question that
matters is the same one already open for every other Award child table
(rows no longer returned by Oracle), not a new concern specific to
notes.

### Current archive coverage

`archive.award_notepad` — missing, this work. (`archive.award_attachment`
is unrelated, already archived on its own track — see Scope.)

### Proposed target table (new migration)

`database/migrations/V042__create_award_notepad.sql` (additive only —
`CREATE TABLE IF NOT EXISTS` + indexes):

```sql
CREATE TABLE IF NOT EXISTS archive.award_notepad (
    award_notepad_id          BIGINT PRIMARY KEY,
    award_id                  BIGINT NOT NULL
                                  REFERENCES archive.award_version(award_id)
                                  ON DELETE CASCADE,
    award_number              VARCHAR(50) NOT NULL,
    entry_number              INTEGER NOT NULL,

    note_topic                VARCHAR(200),
    comments                  TEXT,
    restricted_view           VARCHAR(10),

    source_create_timestamp   TIMESTAMP,
    source_create_user        VARCHAR(100),
    source_update_timestamp   TIMESTAMP,
    source_update_user        VARCHAR(100),
    source_version_number     BIGINT,

    loaded_at                 TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                   BIGINT REFERENCES archive.load_run(load_id)
);
CREATE INDEX ix_award_notepad_award ON archive.award_notepad (award_id, award_notepad_id);
CREATE INDEX ix_award_notepad_number ON archive.award_notepad (award_number, entry_number);
```

`comments`/`note_topic` are nullable in Postgres despite being `NOT
NULL` in Oracle — matching this project's existing convention of not
mirroring Oracle's own `NOT NULL` constraints onto archive columns
(e.g. `archive.award_person.full_name` is nullable though Oracle's
`FULL_NAME` is `NOT NULL`); `award_number`/`entry_number` **are** kept
`NOT NULL` here because they are this table's actual business identity
(mirroring `archive.award_version.award_number`'s own `NOT NULL`, not a
new precedent). `(award_number, entry_number)` is a plain index, not a
`UNIQUE` constraint — see Findings above for why.

### UPSERT conflict key

`award_notepad_id` — the table's own surrogate PK, unique via its own
dedicated `SEQ_AWARD_NOTEPAD_ID` sequence (table-scoped, safe
regardless of not sharing `SEQUENCE_AWARD_ID`).

### Load order

Within `_run_load_award_id`'s and the bulk `_run_load_award_batch`'s
existing family-widened transaction, after the existing thirteen
tables: `award_notepad` has no FK dependency on anything added in this
pass or any previous one, so its position in the load order is
arbitrary — appended last, after `unit_contact`.

No new Oracle family-resolution scan, no new top-level load function —
reuses `read_award_children_matching_award_ids` exactly as every flat
(non-grandchild) child table does, scoped to the same
`family_award_ids` already resolved for `award_version`.

### Batch behavior

No new batch domain/entity_type. Rides along for free on
`--create-batch`/`--load-batch`/`--show-batch`, and on the bulk
`_run_load_award_batch` refactor's "read each table once for the whole
batch" design — this is simply a 14th table added to that same
already-bulk read/UPSERT structure.

### Reconciliation strategy

Deferred, identically to every other Award child table so far — no
hard-delete, no soft-delete marking implemented. Same
recommended-but-unimplemented default already recorded in
`AWARD_IMPLEMENTATION_ROADMAP.md`. Not re-decided here.

## Open questions

- Whether Kuali's versioning process copies `AwardNotepad` rows forward
  to a new `award_id` on a new sequence, or pins them permanently to
  the `award_id` current at creation time — could not be confirmed
  from static source alone (see Findings). Does not block or weaken
  this implementation (family-widening captures every note either
  way), but worth resolving if it ever becomes relevant to a UI/API
  layer built on top of this archive.
- Same deletion/reconciliation and ID-reuse open questions already
  recorded in `AWARD_IMPLEMENTATION_ROADMAP.md` apply equally here.

## Decisions

- `archive.award_notepad` has no `sequence_number` column — a
  deliberate omission matching the real Oracle schema, not an
  oversight. `award_number` is `NOT NULL` (the real family key);
  `award_id` is retained (also `NOT NULL`, matching Oracle) as the
  FK-scoped, family-widening join column every other child table also
  uses, even though it denormalizes only one specific historical
  `award_id` snapshot rather than the whole family directly.
- `(award_number, entry_number)` is indexed but not made `UNIQUE` in
  Postgres, matching the real Oracle index's actual (non-unique)
  semantics rather than its misleading `UQ_`-prefixed name.
- `source_create_timestamp`/`source_create_user` are added as new
  columns (this table's only genuine schema difference from the
  established convention) rather than discarding `CREATE_TIMESTAMP`/
  `CREATE_USER` to force-fit the existing update-only pattern.

## Recommended implementation order

1. ~~Design: object graph, Oracle PK/FK mapping, business meaning,
   family-vs-version resolution, update timestamps, delete behavior,
   archive mapping, UPSERT key, load order, reconciliation strategy~~ —
   done.
2. ~~Migration (`V042`), verified against a throwaway database~~ — done.
3. ~~Oracle extraction SQL (flat, no join needed)~~ — done.
4. ~~`prepare_notepad`, `upsert_award_notepad`~~ — done.
5. ~~Extend `_run_load_award_id`/`_run_load_award_batch` (the bulk
   implementation)~~ — done.
6. ~~Tests (SQL/column contract, insert/update/unchanged, dry-run
   rollback, unrelated-award isolation, bulk batch propagation,
   idempotent rerun, one-transaction rollback behavior) + full
   validation (`pytest` 529 passed, `ruff` clean, `mypy` clean)~~ —
   done.
7. Local smoke test against the same real award_id (52) used in prior
   Award subsystems' validation — plan prepared, not yet run (no
   Oracle/RDS connectivity available in this session).
8. ~~Update `KUALI_ARCHIVE_COVERAGE.md` (row 12), `AWARD_DOMAIN_DECOMPOSITION.md`
   (Award Attachments tier), and `AWARD_IMPLEMENTATION_ROADMAP.md`~~ —
   done.

## Date last updated

2026-07-31 (design and implementation complete; local real-data smoke
test not yet run — see step 7 above).
