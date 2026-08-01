package edu.bu.archive.application.award;

/*
 * Normalizes a raw user search term into a safe Postgres ILIKE pattern
 * - a pure string transformation, never SQL text itself. The resulting
 * pattern is always passed to the database as a single bound parameter
 * (JdbcClient's .param(...)), never concatenated into a SQL string, so
 * this class has no SQL-injection surface at all; what it does guard
 * against is *pattern* injection - a literal '%' or '_' typed by a user
 * (LIKE/ILIKE's own wildcard characters) being misinterpreted as a
 * wildcard instead of the literal character the user meant.
 *
 * Rules, applied in this order:
 *   1. Escape literal backslash, '%', and '_' (Postgres's ILIKE
 *      metacharacters), so a search for e.g. "50%" matches the literal
 *      substring "50%", never "50" followed by anything.
 *   2. Translate this API's own wildcard syntax - a literal '*' in the
 *      user's query - into an (unescaped) ILIKE '%'. Postgres has no
 *      native meaning for '*' in ILIKE; this is purely an
 *      application-level convention (e.g. "*105698*").
 *   3. If the user did not supply their own wildcard, default to a
 *      substring ("partial") match by wrapping the whole term in '%'.
 *      An exact match is handled separately by the caller (a plain
 *      equality comparison), not by this pattern.
 */
final class AwardSearchPattern {

    private AwardSearchPattern() {
    }

    static String toLikePattern(String rawQuery) {
        String escaped = rawQuery
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");

        // Checked on the *original* input, not the escaped/translated
        // result - a literal '%' in the user's own query is escaped to
        // "\%" above and would otherwise still satisfy a contains("%")
        // check, wrongly treating "an escaped literal percent" the same
        // as "the user typed their own wildcard".
        boolean hasApplicationWildcard = rawQuery.contains("*");

        String withWildcards = escaped.replace("*", "%");

        return hasApplicationWildcard
                ? withWildcards
                : "%" + withWildcards + "%";
    }
}
