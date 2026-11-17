package edu.bu.archive.application.authorization.admin;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one rule for an administrative reason (decision D4).
 *
 * <p>Every mutating method of {@link ArchiveAdminService} calls
 * {@link #require(String)}, so the HTTP API, the bulk path and the operator
 * CLIs cannot disagree about what a reason is. The controller marks the
 * parameters required as a convenience; this is the control.
 *
 * <p>After normalisation (trim, collapse internal whitespace) a reason must:
 * <ol>
 *   <li>be 10 to 500 characters long;</li>
 *   <li>contain at least two words that contain letters, so "1234567890" and
 *       "----------" fail;</li>
 *   <li>not be one character repeated or one word repeated
 *       ("aaaaaaaaaa", "access access access");</li>
 *   <li>not consist only of placeholders from {@link #PLACEHOLDERS}, compared
 *       case-insensitively with punctuation ignored, alone, repeated or
 *       combined ("n/a", "TBD - TODO", "test, testing").</li>
 * </ol>
 *
 * <p>The denylist is a deterrent, not a guarantee: a meaningless sentence
 * still passes. The real control is that the reason is kept in the audit
 * trail and shown to whoever reviews it.
 */
public final class Reasons {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 500;

    /**
     * The placeholder denylist, in its normalised form: lower case, every
     * character that is not a letter, digit or space removed, single-spaced.
     * "n/a" is therefore "na", and "n / a" is "n a", so both are listed.
     *
     * <p>Mirrors {@code PLACEHOLDER_PHRASES} in
     * {@code ui/src/features/securityAdmin/reasonRule.mjs} entry for entry;
     * {@code ReasonsTest} pins the same example strings as that file's tests
     * so the two rules cannot drift. The server is the authority.
     */
    public static final List<String> PLACEHOLDERS = List.of(
            "test", "testing", "na", "n a", "none", "null", "reason", "no reason",
            "asdf", "qwerty", "xxx", "tbd", "todo", "placeholder", "see above",
            "as discussed", "per request", "lorem ipsum");

    /** Unicode-aware, to match JavaScript's {@code \s} in the UI rule. */
    private static final Pattern WHITESPACE =
            Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern NOT_WORD_OR_SPACE =
            Pattern.compile("[^\\p{L}\\p{N}\\s]", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern NOT_WORD =
            Pattern.compile("[^\\p{L}\\p{N}]", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern HAS_LETTER = Pattern.compile(".*\\p{L}.*");

    private static final List<String[]> PLACEHOLDER_TOKENS = PLACEHOLDERS.stream()
            .map(phrase -> phrase.split(" "))
            .toList();

    private Reasons() {
    }

    /** Trim and collapse every run of whitespace to one space. This is what is stored. */
    public static String normalize(String raw) {
        return raw == null ? "" : WHITESPACE.matcher(raw).replaceAll(" ").strip();
    }

    /**
     * Validate and normalise a reason. The checks run in the same order as
     * {@code reasonProblem} in the UI rule.
     *
     * @return the reason trimmed, with internal whitespace collapsed to single spaces
     * @throws ReasonRequiredException when the reason does not meet the rule
     */
    public static String require(String raw) {
        String reason = normalize(raw);
        if (reason.isEmpty()) {
            throw new ReasonRequiredException("a reason is required for every administrative change");
        }
        if (isPlaceholder(reason)) {
            throw new ReasonRequiredException(
                    "the reason is placeholder text; describe the actual reason");
        }
        if (reason.length() < MIN_LENGTH) {
            throw new ReasonRequiredException(
                    "the reason must be at least " + MIN_LENGTH + " characters");
        }
        if (reason.length() > MAX_LENGTH) {
            throw new ReasonRequiredException(
                    "the reason must be at most " + MAX_LENGTH + " characters");
        }
        String compact = NOT_WORD.matcher(reason.toLowerCase(Locale.ROOT)).replaceAll("");
        if (!compact.isEmpty() && compact.chars().distinct().count() == 1) {
            throw new ReasonRequiredException("the reason cannot be one character repeated");
        }
        String[] tokens = comparableTokens(reason);
        if (tokens.length > 1 && Arrays.stream(tokens).allMatch(tokens[0]::equals)) {
            throw new ReasonRequiredException("the reason cannot be one word repeated");
        }
        long words = Arrays.stream(reason.split(" "))
                .filter(word -> HAS_LETTER.matcher(word).matches())
                .count();
        if (words < 2) {
            throw new ReasonRequiredException("the reason must be at least two words");
        }
        return reason;
    }

    /** True when the reason would be accepted. For callers that only need to ask. */
    public static boolean isAcceptable(String reason) {
        try {
            require(reason);
            return true;
        } catch (ReasonRequiredException refused) {
            return false;
        }
    }

    /**
     * True when the reason is made up entirely of placeholder phrases, alone,
     * repeated or combined. Case and punctuation are ignored. A sentence that
     * merely contains a placeholder word is not a placeholder.
     */
    public static boolean isPlaceholder(String raw) {
        String[] tokens = comparableTokens(normalize(raw));
        return tokens.length > 0 && isOnlyPlaceholders(tokens);
    }

    private static String[] comparableTokens(String reason) {
        String folded = NOT_WORD_OR_SPACE.matcher(reason.toLowerCase(Locale.ROOT)).replaceAll("");
        return Arrays.stream(WHITESPACE.split(folded))
                .filter(token -> !token.isEmpty())
                .toArray(String[]::new);
    }

    /**
     * True when the tokens can be split, start to finish, into placeholder
     * phrases. Covers one placeholder, a placeholder repeated, and several
     * combined ("tbd todo", "see above as discussed").
     */
    private static boolean isOnlyPlaceholders(String[] tokens) {
        boolean[] coveredUpTo = new boolean[tokens.length + 1];
        coveredUpTo[0] = true;
        for (int end = 1; end <= tokens.length; end++) {
            for (String[] phrase : PLACEHOLDER_TOKENS) {
                int start = end - phrase.length;
                if (start >= 0 && coveredUpTo[start] && matches(tokens, start, phrase)) {
                    coveredUpTo[end] = true;
                    break;
                }
            }
        }
        return coveredUpTo[tokens.length];
    }

    private static boolean matches(String[] tokens, int start, String[] phrase) {
        for (int i = 0; i < phrase.length; i++) {
            if (!tokens[start + i].equals(phrase[i])) {
                return false;
            }
        }
        return true;
    }
}
