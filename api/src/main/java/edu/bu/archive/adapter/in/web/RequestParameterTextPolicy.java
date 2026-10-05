package edu.bu.archive.adapter.in.web;

/*
 * Which characters a request parameter may contain - a pure string
 * inspection, applied at the web boundary by
 * RequestParameterValidationInterceptor before any controller or
 * repository sees the value.
 *
 * Why this exists (QA TC-017, defect QA-D2): a NUL byte reaching
 * PostgreSQL as a bound parameter is rejected by the server's UTF-8
 * decoder - "ERROR: invalid byte sequence for encoding "UTF8": 0x00" -
 * which Spring surfaces as a DataAccessException. Nothing maps that to
 * a client error, so a crafted query string such as "smith%00cohort"
 * returned HTTP 500 from every search endpoint. The value never had any
 * business reaching the database: the fix belongs here, at the
 * boundary, not in a blanket DataAccessException handler (a real
 * integrity failure genuinely is a 500).
 *
 * Rejected: every ISO control character (C0 0x00-0x1F, DEL 0x7F, and
 * C1 0x80-0x9F) except the three whitespace controls below. Only 0x00
 * actually breaks PostgreSQL; the rest are non-printable, cannot be
 * typed into a search box, carry no search meaning, and corrupt logs
 * and CSV exports, so they are refused for the same reason rather than
 * silently stripped - stripping would quietly search for something the
 * user did not ask for.
 *
 * Allowed: tab, line feed and carriage return. These arrive from an
 * ordinary paste, are accepted by PostgreSQL, and are already handled
 * as whitespace by each domain's own trimming.
 */
final class RequestParameterTextPolicy {

    private static final char TAB = '\t';
    private static final char LINE_FEED = '\n';
    private static final char CARRIAGE_RETURN = '\r';

    private RequestParameterTextPolicy() {
    }

    static boolean isDisallowed(char character) {
        if (character == TAB
                || character == LINE_FEED
                || character == CARRIAGE_RETURN) {
            return false;
        }
        return Character.isISOControl(character);
    }

    /*
     * The U+XXXX label of the first disallowed character, or null when
     * the value is acceptable. Returns the label rather than the
     * character itself so callers can name the problem in an error
     * message without echoing unprintable input back to the client.
     */
    static String firstDisallowed(String value) {
        if (value == null) {
            return null;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (isDisallowed(character)) {
                return label(character);
            }
        }
        return null;
    }

    private static String label(char character) {
        return String.format("U+%04X", (int) character);
    }
}
