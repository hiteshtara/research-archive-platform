package edu.bu.archive.application.service;

import java.util.regex.Pattern;

/*
 * Classifies a Global Search query as "obviously an exact identifier" -
 * Award numbers, Proposal numbers, Subaward codes, workflow/document
 * numbers, protocol identifiers - so GlobalSearchService can skip the
 * semantic branch for it entirely (structured search alone is
 * sufficient and faster). A name or topic query ("Jennifer Smacher",
 * "diabetes research") matches neither pattern below, so semantic search
 * still runs for it.
 */
final class LikelyIdentifierDetector {

    private static final Pattern PURE_NUMERIC = Pattern.compile("^\\d+$");
    private static final Pattern AWARD_NUMBER_SHAPE =
            Pattern.compile("^\\d{4,8}-\\d{2,5}$");

    private LikelyIdentifierDetector() {
    }

    static boolean looksLikeIdentifier(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String trimmed = query.trim();
        return PURE_NUMERIC.matcher(trimmed).matches()
                || AWARD_NUMBER_SHAPE.matcher(trimmed).matches();
    }
}
