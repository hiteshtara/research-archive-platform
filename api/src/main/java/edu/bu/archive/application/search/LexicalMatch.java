package edu.bu.archive.application.search;

/**
 * How a typed search term is matched against text, and against identifiers.
 *
 * <h2>The defect this exists to fix</h2>
 *
 * <p>QA TC-040 reported that a search for "NSF" showed the genuinely
 * NSF-funded Award as a merely "related" suggestion. Measured on DEV
 * (revision 87) on 2026-11-10, Award {@code 100527-00001} is titled
 * "NATIONAL SCIENCE FOUNDATION GRADUATE RESEARCH FELLOWSHIP PROGRAM" with
 * sponsor "National Science Foundation" - and contains the letters N-S-F
 * nowhere. Every lexical leg matched with {@code ILIKE '%' || term || '%'},
 * so it could not match at all. What did match, and filled the page:
 *
 * <ul>
 *   <li>"PROBING THE PRINCIPLES OF DYNAMICS AND ENERGY TRA<b>NSF</b>ER IN PROTEINS"
 *   <li>"PIRE: … TRA<b>NSF</b>ORMING A BIODIVERSITY HOTSPOT…"
 *   <li>all 25 "Material Tra<b>nsf</b>er Agreement" Negotiations
 * </ul>
 *
 * <p>Unanchored substring matching had inverted relevance: ordinary English
 * words containing the letters won, and the real answer was demoted.
 *
 * <h2>Word START, not word boundary</h2>
 *
 * <p>The obvious fix - require a whole word - is wrong, and would have
 * traded one complaint for a worse one. {@code \mquantum\M} does not match
 * "quantums"; {@code \mcardio\M} does not match "cardiology". Prefix
 * searching is the common, legitimate use of this box.
 *
 * <p>So the rule anchors the START of a word only: {@code \mterm}. That
 * kills the mid-word match ("nsf" inside "transfer") while keeping every
 * prefix search working. "cardio" still finds cardiology; "nsf" no longer
 * finds transfer.
 *
 * <h2>When it applies, and when it deliberately does not</h2>
 *
 * <p>Anchoring is applied to free TEXT - titles, names, sponsors, units,
 * agreement types - and never to IDENTIFIERS. A person pasting "105698" to
 * find "105698-00001", or a fragment of an account or grant number, is
 * matching mid-string on purpose, and that has to keep working.
 *
 * <p>It is also skipped whenever the term CONTAINS A DIGIT. A term with a
 * digit is far more likely to be an identifier fragment than a word, and
 * guessing wrong there breaks the search people rely on most. And it is
 * skipped when the user typed their own {@code *} wildcard, because they
 * have then said explicitly what they want.
 *
 * <p>The effect is narrow by design: alphabetic terms stop matching inside
 * unrelated words, and nothing else changes.
 */
public final class LexicalMatch {

    /**
     * Below this length a term is not anchored.
     *
     * <p>A single character anchored to word starts matches a large
     * fraction of the archive and reads as noise either way; it is left on
     * the old behaviour rather than given new semantics nobody asked for.
     */
    private static final int MINIMUM_ANCHORED_LENGTH = 2;

    private LexicalMatch() {
    }

    /**
     * The ILIKE pattern for substring matching - unchanged behaviour.
     *
     * <p>Escapes the ILIKE metacharacters so a typed {@code %} or {@code _}
     * is the literal character, and translates this API's own {@code *}
     * wildcard. Always bound as a parameter, never concatenated into SQL.
     */
    public static String likePattern(String rawTerm) {
        String term = rawTerm == null ? "" : rawTerm;
        String escaped = term
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");

        // Checked on the ORIGINAL input: a typed '%' is escaped to "\%"
        // above and would otherwise still satisfy a contains("%") test.
        boolean userWildcard = term.contains("*");
        String withWildcards = escaped.replace("*", "%");

        return userWildcard ? withWildcards : "%" + withWildcards + "%";
    }

    /**
     * Whether this term matches this text, under the same rule the SQL uses.
     *
     * <p>This is what lets a result card say WHY it matched without
     * contradicting the query that produced it. The previous explanation
     * tested a plain {@code contains}, so for a search of "NSF" it reported
     * "Matched on: Title" against "ENERGY TRANSFER IN PROTEINS" - honest
     * about the letters, and meaningless to the reader. With anchoring, the
     * same test now correctly says the title did not match, and the card
     * falls back to asking the database rather than guessing.
     *
     * <p>Mirrors PostgreSQL's {@code \m}: a word character is a letter, a
     * digit or an underscore, so a match counts when the character before it
     * is none of those.
     */
    public static boolean matches(String rawTerm, String text) {
        if (text == null) {
            return false;
        }
        String term = rawTerm == null ? "" : rawTerm.trim();
        if (term.isEmpty()) {
            return false;
        }
        String haystack = text.toLowerCase(java.util.Locale.ROOT);
        String needle = term.toLowerCase(java.util.Locale.ROOT);

        if (!anchorsToWordStart(term)) {
            return haystack.contains(needle);
        }
        for (int from = 0; from <= haystack.length() - needle.length(); ) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return false;
            }
            if (at == 0 || !isWordCharacter(haystack.charAt(at - 1))) {
                return true;
            }
            from = at + 1;
        }
        return false;
    }

    private static boolean isWordCharacter(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * Whether this term should match only at the start of a word.
     *
     * <p>True only for a purely alphabetic term (spaces allowed, so
     * "WILSON WONG" qualifies) of at least {@value #MINIMUM_ANCHORED_LENGTH}
     * characters with no wildcard of its own. Anything carrying a digit,
     * hyphen or other punctuation is treated as a possible identifier and
     * left on substring matching.
     */
    public static boolean anchorsToWordStart(String rawTerm) {
        String term = rawTerm == null ? "" : rawTerm.trim();
        if (term.length() < MINIMUM_ANCHORED_LENGTH || term.contains("*")) {
            return false;
        }
        boolean sawLetter = false;
        for (int i = 0; i < term.length(); i++) {
            char c = term.charAt(i);
            if (Character.isLetter(c)) {
                sawLetter = true;
            } else if (c != ' ') {
                return false;
            }
        }
        return sawLetter;
    }

    /**
     * A POSIX regex matching this term at the start of a word.
     *
     * <p>{@code \m} is PostgreSQL's start-of-word boundary. Used with
     * {@code ~*} for case-insensitive matching, so it is the regex
     * equivalent of ILIKE for these purposes.
     *
     * <p>Every regex metacharacter in the term is escaped. The term is user
     * input: without this, a search containing {@code (} is not a different
     * result, it is a thrown error - and one a caller could provoke
     * deliberately.
     *
     * <p>Returns a pattern that cannot match when the term does not qualify
     * for anchoring, so a caller that ignores {@link #anchorsToWordStart}
     * fails closed rather than silently matching everything.
     */
    public static String wordStartRegex(String rawTerm) {
        if (!anchorsToWordStart(rawTerm)) {
            // Deliberately unmatchable: \m requires a word start, and $ ends
            // the string, so nothing can satisfy both with no character
            // between them.
            return "\\m$a";
        }
        String term = rawTerm.trim();
        StringBuilder regex = new StringBuilder(term.length() * 2 + 2);
        regex.append("\\m");
        for (int i = 0; i < term.length(); i++) {
            char c = term.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                regex.append(c);
            } else {
                // Includes the space in a multi-word term, escaped for the
                // same reason as everything else: the term is input.
                regex.append('\\').append(c);
            }
        }
        return regex.toString();
    }
}
