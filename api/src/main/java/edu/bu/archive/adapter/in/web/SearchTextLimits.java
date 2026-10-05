package edu.bu.archive.adapter.in.web;

/*
 * How long a free-text search may be, in one place, so the five module
 * searches and Global Search cannot drift apart again (QA TC-018).
 *
 * Before this, Global Search alone enforced a limit and every module
 * search accepted any length - a 2,000-character query took about 2.2
 * seconds and 6,000 took 6.4, on a small dataset, because the work is
 * linear in the pattern length. Global Search's existing limit is the
 * one already agreed and already documented in its API contract, so it
 * is the limit the rest adopt rather than a new number invented here.
 *
 * COUNTING: Java's String.length() counts UTF-16 code units, and so
 * does JavaScript's String.length, so the browser and the API agree on
 * the length of any string - including one containing an emoji or other
 * astral character, which both count as 2. The UI's own constant is
 * checked against this file by a test, so the two cannot drift.
 *
 * MINIMUMS ARE NOT SHARED. Global Search requires at least 2 characters
 * because it fans out across every module; a module search has no
 * minimum at all - an empty query there means "list everything,
 * paginated", which is a legitimate and commonly used search. Only the
 * maximum is common to all of them.
 */
public final class SearchTextLimits {

    /**
     * The longest free-text search the API accepts, for every search
     * endpoint. Referenced from {@code @Size(max = ...)}, so it has to
     * stay a compile-time constant.
     */
    public static final int MAX_SEARCH_TEXT_LENGTH = 200;

    /**
     * Global Search's existing minimum, unchanged. It is declared here
     * only so the annotation on that endpoint reads with its maximum
     * rather than carrying one named bound and one bare number.
     */
    public static final int MIN_GLOBAL_SEARCH_TEXT_LENGTH = 2;

    private SearchTextLimits() {
    }
}
