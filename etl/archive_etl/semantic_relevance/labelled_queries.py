"""The labelled query set for QA TC-041.

Every query carries a label and a REASON. A set without reasons is a set
nobody can argue with later, and the reason is what makes a disagreement
about relevance a discussion rather than a preference.

``SHOULD_RETURN`` means a semantic result is genuinely useful for this query:
either the archive uses a different word than the searcher did, or the query
is real subject matter. ``SHOULD_REJECT`` means any semantic result is noise.

Sources, so these are not invented:

* The two nonsense strings are Lalitha Sudhireddy's, reproduced on DEV
  (API revision 87) on 2026-11-10.
* The five scientific terms and their lexical/semantic counts were measured
  on DEV on 2026-10-05 and recorded in PR #28's own removed comment.
* "qzxwvnonsense987" was measured on DEV on 2026-10-05, same source.
* The NSF cases come from the TC-040 investigation: Award 100527-00001 is
  titled "NATIONAL SCIENCE FOUNDATION GRADUATE RESEARCH FELLOWSHIP PROGRAM"
  with sponsor "National Science Foundation" and contains no "NSF" at all.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class Label(str, Enum):
    SHOULD_RETURN = "should_return"
    SHOULD_REJECT = "should_reject"


class Kind(str, Enum):
    NONSENSE = "nonsense"
    ABBREVIATION = "abbreviation"
    EXPANDED_NAME = "expanded_name"
    SCIENTIFIC_TERM = "scientific_term"
    SYNONYM = "synonym"
    IDENTIFIER_SHAPED = "identifier_shaped"
    # Added 2026-11-10 to test the corpus-vocabulary gate properly. The first
    # 15 queries could not: every "should return" query among them happened to
    # contain at least one word the corpus already uses, so the gate passed
    # them for reasons that say nothing about whether it preserves
    # semantic-only discovery.
    SPELLING_VARIANT = "spelling_variant"
    SYNONYM_ONLY = "synonym_only"
    ABSENT_BUT_MEANINGFUL = "absent_but_meaningful"


@dataclass(frozen=True)
class LabelledQuery:
    query: str
    label: Label
    kind: Kind
    reason: str
    # What was already measured, where it was. None where it has not been.
    measured_lexical_hits: int | None = None
    measured_semantic_hits: int | None = None


LABELLED_QUERIES: tuple[LabelledQuery, ...] = (
    # --- nonsense: any semantic result is noise -------------------------
    LabelledQuery(
        query="zzzznotfound123",
        label=Label.SHOULD_REJECT,
        kind=Kind.NONSENSE,
        reason=(
            "Lalitha's original string. Returned five Negotiations on 2026-11-10, "
            "four of them sharing one negotiator and all five Abandoned - no "
            "visible relation to the query."
        ),
        measured_lexical_hits=0,
        measured_semantic_hits=5,
    ),
    LabelledQuery(
        query="aavvvlkkjhuipkknbhgkj",
        label=Label.SHOULD_REJECT,
        kind=Kind.NONSENSE,
        reason=(
            "Lalitha's second string. Returned four Awards and a Proposal on "
            "2026-11-10 - a DIFFERENT module mix from the first nonsense query, "
            "which rules out one degenerate cluster being blamed."
        ),
        measured_lexical_hits=0,
        measured_semantic_hits=5,
    ),
    LabelledQuery(
        query="qzxwvnonsense987",
        label=Label.SHOULD_REJECT,
        kind=Kind.NONSENSE,
        reason=(
            "Returned five Awards on 2026-10-05, recorded in PR #28's own removed "
            "comment. A third nonsense string matters because the first two "
            "returned different modules from each other."
        ),
        measured_lexical_hits=0,
        measured_semantic_hits=5,
    ),
    LabelledQuery(
        query="xkcdqq",
        label=Label.SHOULD_REJECT,
        kind=Kind.NONSENSE,
        reason=(
            "A SHORT nonsense term. The two originals are long, and a rule tuned "
            "only on long strings may not hold for a short one - length changes "
            "how an embedding model behaves."
        ),
    ),
    # --- abbreviations: the reason a threshold cannot simply reject ------
    LabelledQuery(
        query="NSF",
        label=Label.SHOULD_RETURN,
        kind=Kind.ABBREVIATION,
        reason=(
            "The case TC-040 found. Award 100527-00001 is NSF-funded and contains "
            "no 'NSF', so before the lexical fix the semantic leg was the ONLY "
            "route to it. Rejecting semantic results for short queries would have "
            "removed the single useful answer."
        ),
    ),
    LabelledQuery(
        query="National Science Foundation",
        label=Label.SHOULD_RETURN,
        kind=Kind.EXPANDED_NAME,
        reason=(
            "The same intent spelled out. Measured beside 'NSF' it shows whether "
            "the distance distribution depends on query LENGTH rather than on "
            "relevance - if the two differ sharply, a length-blind threshold is "
            "already suspect."
        ),
    ),
    LabelledQuery(
        query="NIH",
        label=Label.SHOULD_RETURN,
        kind=Kind.ABBREVIATION,
        reason=(
            "A second abbreviation with a different corpus frequency: many DEV "
            "sponsors are 'NIH/...', so unlike NSF it also matches lexically. One "
            "abbreviation is an anecdote."
        ),
    ),
    # --- real subject matter: must not be rejected -----------------------
    LabelledQuery(
        query="pediatric asthma",
        label=Label.SHOULD_RETURN,
        kind=Kind.SCIENTIFIC_TERM,
        reason=(
            "Measured 2026-10-05: 7 lexical and 3 semantic. It kept every semantic "
            "result under PR #27 only because it also matched lexically - a "
            "coincidence, not a property of the query."
        ),
        measured_lexical_hits=7,
        measured_semantic_hits=3,
    ),
    LabelledQuery(
        query="quantum",
        label=Label.SHOULD_RETURN,
        kind=Kind.SCIENTIFIC_TERM,
        reason=(
            "Measured 2026-10-05: 87 lexical and 4 semantic. A high-frequency "
            "subject term, so its semantic results sit alongside plenty of direct "
            "ones - the case a rejection rule must not disturb."
        ),
        measured_lexical_hits=87,
        measured_semantic_hits=4,
    ),
    LabelledQuery(
        query="climate",
        label=Label.SHOULD_RETURN,
        kind=Kind.SCIENTIFIC_TERM,
        reason=(
            "Measured 2026-10-05: 86 lexical and 2 semantic. Included beside "
            "quantum because they have similar lexical weight but different "
            "semantic yield, which a single cutoff has to tolerate."
        ),
        measured_lexical_hits=86,
        measured_semantic_hits=2,
    ),
    LabelledQuery(
        query="neurodegeneration",
        label=Label.SHOULD_RETURN,
        kind=Kind.SCIENTIFIC_TERM,
        reason=(
            "Measured 2026-10-05: 88 lexical and 1 semantic. A long single word, "
            "which embeds differently from a short acronym - part of testing "
            "whether a rule depends on query length."
        ),
        measured_lexical_hits=88,
        measured_semantic_hits=1,
    ),
    LabelledQuery(
        query="opioid",
        label=Label.SHOULD_RETURN,
        kind=Kind.SCIENTIFIC_TERM,
        reason=(
            "Measured 2026-10-05: 86 lexical and 1 semantic. Its single semantic "
            "result is the kind most at risk from a cutoff tuned on queries with "
            "several."
        ),
        measured_lexical_hits=86,
        measured_semantic_hits=1,
    ),
    # --- synonyms: the case PR #27 was reverted for ----------------------
    LabelledQuery(
        query="heart attack",
        label=Label.SHOULD_RETURN,
        kind=Kind.SYNONYM,
        reason=(
            "The archive is likely to say 'myocardial infarction'. This is exactly "
            "what PR #27 broke and #28 restored: a searcher using a different word "
            "from the archive. A rule that rejects this is the wrong rule, however "
            "well it handles nonsense."
        ),
    ),
    LabelledQuery(
        query="cancer screening in underserved populations",
        label=Label.SHOULD_RETURN,
        kind=Kind.SYNONYM,
        reason=(
            "A long natural-language phrase that may match no title verbatim. Long "
            "queries are where semantic search earns its place, and where a "
            "distance threshold tuned on single words may misbehave."
        ),
    ),
    LabelledQuery(
        query="gene editing",
        label=Label.SHOULD_RETURN,
        kind=Kind.SYNONYM,
        reason=(
            "The archive is likely to say CRISPR. The same synonym case as heart "
            "attack but in a different field, so one discipline's vocabulary is "
            "not mistaken for a general result."
        ),
    ),
    # --- identifier-shaped: semantic is skipped, and must stay skipped ---
    LabelledQuery(
        query="100527-00001",
        label=Label.SHOULD_REJECT,
        kind=Kind.IDENTIFIER_SHAPED,
        reason=(
            "LikelyIdentifierDetector already skips the semantic leg entirely for "
            "these, so no embedding call is made. Included so a change to that "
            "detector shows up here as a measurable regression rather than as a "
            "surprise latency cost."
        ),
    ),
    LabelledQuery(
        query="760067",
        label=Label.SHOULD_REJECT,
        kind=Kind.IDENTIFIER_SHAPED,
        reason=(
            "Negotiation 6447's document number, a real identifier read from DEV. "
            "Semantic search is skipped for it today, and that should stay true."
        ),
    ),
)


# --- the expanded tier, added 2026-11-10 -----------------------------
#
# WHY. The corpus-vocabulary gate scored 15/15 on the original set, and that
# result was not trustworthy: every "should return" query there contained at
# least one word the corpus already uses, so the gate never had to decide a
# case where the searcher's words and the archive's words differ. That is
# precisely the case semantic search exists for, and precisely the case PR #27
# was reverted for breaking.
#
# These queries are chosen to make the gate fail if it is going to. A gate
# that rejects them is not a relevance fix, it is PR #27 wearing a different
# hat.

EXPANDED_QUERIES: tuple[LabelledQuery, ...] = (
    # --- spelling variants: meaningful, and not how a US archive spells it ---
    LabelledQuery(
        query="paediatric",
        label=Label.SHOULD_RETURN,
        kind=Kind.SPELLING_VARIANT,
        reason=(
            "British spelling of a term the archive certainly holds (pediatric "
            "appears in 57 embedded rows). A searcher typing it is not making a "
            "mistake, and semantic search is exactly what should bridge it. The "
            "vocabulary gate will reject it unless the corpus happens to contain "
            "the British form."
        ),
    ),
    LabelledQuery(
        query="tumour",
        label=Label.SHOULD_RETURN,
        kind=Kind.SPELLING_VARIANT,
        reason="British spelling of tumor. Same case, different field.",
    ),
    LabelledQuery(
        query="haematology",
        label=Label.SHOULD_RETURN,
        kind=Kind.SPELLING_VARIANT,
        reason="British spelling of hematology; a third instance so one is not an anecdote.",
    ),
    # --- synonym only: the archive uses a different word entirely ---------
    LabelledQuery(
        query="myocardial infarction",
        label=Label.SHOULD_RETURN,
        kind=Kind.SYNONYM_ONLY,
        reason=(
            "The clinical term for a heart attack. If the archive's titles say "
            "'heart' and not 'myocardial', this is the synonym case with NO token "
            "overlap at all - the one the gate cannot pass by luck."
        ),
    ),
    LabelledQuery(
        query="neoplasm",
        label=Label.SHOULD_RETURN,
        kind=Kind.SYNONYM_ONLY,
        reason="The clinical term for a tumour or cancer; cancer appears in 650 rows.",
    ),
    LabelledQuery(
        query="cerebrovascular accident",
        label=Label.SHOULD_RETURN,
        kind=Kind.SYNONYM_ONLY,
        reason="The clinical term for a stroke. Two words, both likely absent.",
    ),
    # --- abbreviations beyond NSF and NIH ---------------------------------
    LabelledQuery(
        query="NIAID",
        label=Label.SHOULD_RETURN,
        kind=Kind.ABBREVIATION,
        reason=(
            "A real NIH institute. Tests whether the gate's acronym handling "
            "depends on the acronym being in archive.sponsor, where only 772 of "
            "7,246 sponsors carry one."
        ),
    ),
    LabelledQuery(
        query="PCORI",
        label=Label.SHOULD_RETURN,
        kind=Kind.ABBREVIATION,
        reason="A real funder whose acronym may or may not be recorded.",
    ),
    LabelledQuery(
        query="DoD",
        label=Label.SHOULD_RETURN,
        kind=Kind.ABBREVIATION,
        reason=(
            "Department of Defense. Three characters with mixed case, so it also "
            "tests whether the gate is case sensitive where it should not be."
        ),
    ),
    # --- meaningful, and probably absent from the corpus ------------------
    LabelledQuery(
        query="quantum error correction",
        label=Label.SHOULD_RETURN,
        kind=Kind.ABSENT_BUT_MEANINGFUL,
        reason=(
            "A real research area. 'quantum' is in the corpus (72 rows) so the "
            "gate passes it on one token - which is the point: it shows the gate "
            "passing for a reason unrelated to whether the results are good."
        ),
    ),
    LabelledQuery(
        query="mRNA vaccine hesitancy",
        label=Label.SHOULD_RETURN,
        kind=Kind.ABSENT_BUT_MEANINGFUL,
        reason=(
            "A real and recent research area. If no token is in the corpus, the "
            "gate rejects it - and whether that is the RIGHT answer depends on "
            "whether semantic search would have returned anything useful, which "
            "has to be observed rather than assumed."
        ),
    ),
    LabelledQuery(
        query="machine learning fairness",
        label=Label.SHOULD_RETURN,
        kind=Kind.ABSENT_BUT_MEANINGFUL,
        reason="A real area that a 2010s research archive may simply not contain.",
    ),
    # --- more nonsense, including shapes the first set did not have -------
    LabelledQuery(
        query="plorgle",
        label=Label.SHOULD_REJECT,
        kind=Kind.NONSENSE,
        reason=(
            "A PRONOUNCEABLE non-word. The original nonsense strings were all "
            "unpronounceable consonant runs, which a vowel-density test catches "
            "for free; this one does not look like noise and must still be "
            "rejected."
        ),
    ),
    LabelledQuery(
        query="snorfulate biodynamics",
        label=Label.SHOULD_REJECT,
        kind=Kind.NONSENSE,
        reason=(
            "Two pronounceable non-words arranged to look like a research phrase. "
            "The hardest nonsense case, because every shape heuristic passes it."
        ),
    ),
    LabelledQuery(
        query="qqqqqq",
        label=Label.SHOULD_REJECT,
        kind=Kind.NONSENSE,
        reason="A repeated character; a degenerate shape the first set lacked.",
    ),
    LabelledQuery(
        query="asdfghjkl",
        label=Label.SHOULD_REJECT,
        kind=Kind.NONSENSE,
        reason="A keyboard row - the most common accidental input there is.",
    ),
)


def all_queries() -> tuple[LabelledQuery, ...]:
    """The original set plus the expanded tier.

    Kept as two tuples so a reader can see which results came from the set
    that produced the first conclusion and which were added to test it.
    """
    return LABELLED_QUERIES + EXPANDED_QUERIES


def should_return() -> tuple[LabelledQuery, ...]:
    return tuple(q for q in all_queries() if q.label is Label.SHOULD_RETURN)


def should_reject() -> tuple[LabelledQuery, ...]:
    return tuple(q for q in all_queries() if q.label is Label.SHOULD_REJECT)
