package edu.bu.archive.adapter.in.web.dto;

/**
 * How many records a module actually matched, beside how many it returned.
 *
 * <h2>The question this answers</h2>
 *
 * <p>QA TC-040 asked whether {@code totalResults} counts matching records or
 * returned rows. It counts returned rows, and Global Search caps every module
 * at 25, so for a broad query the two differ by orders of magnitude: a search
 * for "NSF" reported 104 results while the Negotiation module alone matched
 * 234. A reader has no way to tell that "104" is a page size rather than a
 * finding.
 *
 * @param module the record type, as the API names it elsewhere
 * @param returned how many rows for this module are in {@code results}
 * @param totalMatches how many records matched in total, or {@code null} when
 *        this module's search does not compute a count. Award, Negotiation and
 *        Subaward already page their results and so know; Proposal and IRB
 *        return a bare list and would need an extra count query, which the
 *        Global Search performance budget does not have room for. A null is
 *        reported as unknown rather than guessed.
 * @param capped whether the cap was reached - {@code returned == 25}. True
 *        means there are almost certainly more, and it is the honest signal
 *        available even where {@code totalMatches} is null.
 */
public record GlobalSearchModuleTotal(
        String module,
        int returned,
        Long totalMatches,
        boolean capped
) {
}
