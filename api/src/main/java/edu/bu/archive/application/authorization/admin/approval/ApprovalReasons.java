package edu.bu.archive.application.authorization.admin.approval;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import edu.bu.archive.application.authorization.admin.approval.PrivilegeApprovalException.Refusal;

/**
 * The reason rule (decision D4), applied to every approval request, approval
 * and revocation.
 *
 * <p>TODO(A-category phase 2): replace with the shared
 * {@code edu.bu.archive.application.authorization.admin.Reasons} once A1's
 * hardening PR (#161) is on main, and delete this class. It is a deliberate
 * copy of that rule, made so this PR does not depend on an unmerged one:
 * after trim and whitespace collapse, 10 to 500 characters; at least two
 * words containing letters; not one character or one word repeated; and not
 * made up only of placeholder phrases. {@code ApprovalReasonsTest} pins the
 * same accept/refuse cases so the switch can be checked mechanically.
 */
public final class ApprovalReasons {

    static final int MIN_LENGTH = 10;
    static final int MAX_LENGTH = 500;

    static final List<String> PLACEHOLDERS = List.of(
            "test", "testing", "na", "n a", "none", "null", "reason", "no reason",
            "asdf", "qwerty", "xxx", "tbd", "todo", "placeholder", "see above",
            "as discussed", "per request", "lorem ipsum");

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

    private ApprovalReasons() {
    }

    static String normalize(String raw) {
        return raw == null ? "" : WHITESPACE.matcher(raw).replaceAll(" ").strip();
    }

    /** @return the normalised reason, which is what is stored */
    public static String require(String raw) {
        String reason = normalize(raw);
        if (reason.isEmpty()) {
            throw refused("a reason is required");
        }
        if (isPlaceholder(reason)) {
            throw refused("the reason is placeholder text; describe the actual reason");
        }
        if (reason.length() < MIN_LENGTH) {
            throw refused("the reason must be at least " + MIN_LENGTH + " characters");
        }
        if (reason.length() > MAX_LENGTH) {
            throw refused("the reason must be at most " + MAX_LENGTH + " characters");
        }
        String compact = NOT_WORD.matcher(reason.toLowerCase(Locale.ROOT)).replaceAll("");
        if (!compact.isEmpty() && compact.chars().distinct().count() == 1) {
            throw refused("the reason cannot be one character repeated");
        }
        String[] tokens = comparableTokens(reason);
        if (tokens.length > 1 && Arrays.stream(tokens).allMatch(tokens[0]::equals)) {
            throw refused("the reason cannot be one word repeated");
        }
        long words = Arrays.stream(reason.split(" "))
                .filter(word -> HAS_LETTER.matcher(word).matches())
                .count();
        if (words < 2) {
            throw refused("the reason must be at least two words");
        }
        return reason;
    }

    static boolean isPlaceholder(String raw) {
        String[] tokens = comparableTokens(normalize(raw));
        return tokens.length > 0 && isOnlyPlaceholders(tokens);
    }

    private static PrivilegeApprovalException refused(String message) {
        return new PrivilegeApprovalException(Refusal.REASON_REQUIRED, message);
    }

    private static String[] comparableTokens(String reason) {
        String folded = NOT_WORD_OR_SPACE.matcher(reason.toLowerCase(Locale.ROOT)).replaceAll("");
        return Arrays.stream(WHITESPACE.split(folded))
                .filter(token -> !token.isEmpty())
                .toArray(String[]::new);
    }

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
