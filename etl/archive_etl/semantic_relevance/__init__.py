"""Measuring whether a semantic result is relevant, for QA TC-041.

TC-041 asked whether Global Search returns its five nearest vectors
regardless of relevance. It does: there is no similarity threshold anywhere
in the pipeline. The earlier attempt to add one was reverted (PR #28) on the
grounds that relevance is a presentation problem, and a prior threshold
experiment had found that no single global cutoff worked.

This package exists so the question can be settled on measurement instead of
opinion. It holds:

* ``labelled_queries`` - the query set, with WHY each one is labelled.
* ``evaluate`` - the candidate rejection strategies and their false-positive
  and false-negative behaviour.

Nothing here chooses a cutoff. Choosing one needs the measured distances, and
those require read access to ``archive.search_embedding`` on DEV plus Bedrock
embedding calls - see ``etl/measure_semantic_relevance.py``.
"""
