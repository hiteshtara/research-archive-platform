package edu.bu.archive.application.document;

/*
 * Normalizes a raw user search term into a safe Postgres ILIKE pattern.
 * Deliberately mirrors edu.bu.archive.application.award.AwardSearchPattern
 * exactly (same escaping/wildcard rules) rather than reaching into that
 * package-private class - Document Search is its own domain spanning
 * five modules, not an Award feature. See that class for the full
 * rationale; the contract here is identical: the result is always
 * passed as a single bound parameter (JdbcClient's .param(...)), never
 * concatenated into SQL text, so this has no SQL-injection surface -
 * only literal '%'/'_' pattern-injection is guarded against.
 */
final class DocumentSearchPattern {

    private DocumentSearchPattern() {
    }

    static String toLikePattern(String rawQuery) {
        String escaped = rawQuery
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");

        boolean hasApplicationWildcard = rawQuery.contains("*");

        String withWildcards = escaped.replace("*", "%");

        return hasApplicationWildcard
                ? withWildcards
                : "%" + withWildcards + "%";
    }
}
