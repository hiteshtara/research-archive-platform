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


def should_return() -> tuple[LabelledQuery, ...]:
    return tuple(q for q in LABELLED_QUERIES if q.label is Label.SHOULD_RETURN)


def should_reject() -> tuple[LabelledQuery, ...]:
    return tuple(q for q in LABELLED_QUERIES if q.label is Label.SHOULD_REJECT)
