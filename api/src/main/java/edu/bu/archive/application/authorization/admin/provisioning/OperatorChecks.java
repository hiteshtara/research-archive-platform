package edu.bu.archive.application.authorization.admin.provisioning;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import edu.bu.archive.application.authorization.InstitutionalIdentifier;

/**
 * The input checks every operator provisioning utility shares.
 *
 * <p>Extracted when a second utility needed them. Two copies of "is this a
 * well-formed archive identifier" or "did the operator state the account this
 * is actually running in" is two copies that can drift, and the one that
 * drifts is the one nobody is reading when it matters. {@link
 * RuntimeVerification} was already shared with the bootstrap utility for the
 * same reason; this is the rest of that boundary.
 *
 * <p>Every method appends to the caller's {@code checks} and {@code problems}
 * lists rather than throwing. A refusal has to be able to report ALL the
 * problems at once - an operator fixing one flag per run, against a production
 * account, is how a one-line mistake becomes an afternoon.
 */
public final class OperatorChecks {

    /**
     * Crockford base-32, which excludes I, L, O and U - so an archive
     * identifier cannot contain a character a person would misread as a digit
     * when copying it off a ticket.
     */
    private static final Pattern ARP =
            Pattern.compile("^ARP-[0-9A-HJKMNP-TV-Z]{8}-[0-9A-HJKMNP-TV-Z]{8}$");

    /** A Cognito subject is a UUID. Anything else is the wrong field. */
    private static final Pattern COGNITO_SUBJECT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private OperatorChecks() {
    }

    public static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static String lower(String value) {
        String trimmed = trim(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    /**
     * Partially hides an identifier in console and audit output.
     *
     * <p>These utilities print to a terminal whose scrollback is routinely
     * pasted into tickets and pull requests. An archive identifier is not a
     * secret, but a full roster of who was granted what does not need to
     * travel that way.
     */
    public static String mask(String value) {
        return value.length() <= 6
                ? "****"
                : value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }

    /**
     * A well-formed archive identifier, or null with the reason recorded.
     *
     * <p>An email address is rejected with its own message because it is the
     * mistake that actually gets made: the operator knows the person, and the
     * person is an email address everywhere else at the university. Accepting
     * one would create a grant keyed to a string that no token will ever
     * resolve to, which fails later as "this user has no access" rather than
     * as a typo.
     */
    public static InstitutionalIdentifier identifier(
            String raw, String what, List<String> checks, List<String> problems) {

        String value = trim(raw);
        if (value == null) {
            problems.add(what + " identifier is required and must be supplied explicitly");
            return null;
        }
        if (value.contains("@")) {
            problems.add(what + " looks like an email address. Supply the archive identifier "
                    + "(ARP-XXXXXXXX-XXXXXXXX); an email is not an identity here.");
            return null;
        }
        if (!ARP.matcher(value).matches()) {
            problems.add(what + " is not a well-formed archive identifier "
                    + "(expected ARP-XXXXXXXX-XXXXXXXX)");
            return null;
        }
        checks.add("OK     " + what + " is a well-formed archive identifier " + mask(value));
        return new InstitutionalIdentifier(value);
    }

    /**
     * Operator, reason and approval reference - what the audit row needs to
     * answer "who did this and who said they could".
     *
     * <p>The OK line is added only when NOTHING has failed, including earlier
     * checks, because it is a statement about the audit row that will be
     * written - and if anything else refused, no audit row is.
     */
    public static void provenance(
            String operator, String reason, String approvalReference,
            List<String> checks, List<String> problems) {

        if (trim(operator) == null) {
            problems.add("--operator is required: the audit row records the person, not the account");
        }
        if (trim(reason) == null) {
            problems.add("--reason is required");
        }
        if (trim(approvalReference) == null) {
            problems.add("--approval-reference is required, so the audit row points at the approval");
        }
        if (problems.isEmpty()) {
            checks.add("OK     operator, reason and approval reference recorded for the audit row");
        }
    }

    /**
     * A Cognito subject is the immutable {@code sub} UUID.
     *
     * <p>The mistake this exists to catch is supplying the wrong field. A
     * username, an email address and a SAML NameID are all what a person
     * thinks of as "the login", and all three are mutable - BU can rename a
     * user, and federation can re-issue a NameID. A link keyed to any of them
     * would silently stop resolving, or worse, start resolving to whoever
     * inherited the name. Only {@code sub} is guaranteed stable for the life
     * of the user pool entry.
     */
    public static String cognitoSubject(String raw, List<String> checks, List<String> problems) {
        String subject = trim(raw);
        if (subject == null) {
            problems.add("Cognito subject is required and must be supplied explicitly");
            return null;
        }
        if (!COGNITO_SUBJECT.matcher(subject).matches()) {
            problems.add("Cognito subject must be the immutable 'sub' UUID. "
                    + "A username, an email address or a NameID is not the subject.");
            return null;
        }
        checks.add("OK     Cognito subject is a well-formed immutable sub " + mask(subject));
        return subject;
    }

    /**
     * That the issuer is the one this user pool actually mints tokens for.
     *
     * <p>Derived from the pool id rather than accepted as an independent
     * value, so a typo in either shows up here instead of producing a link
     * that no real token can ever match.
     */
    public static void cognitoIssuer(
            String raw, String expectedIssuer, List<String> checks, List<String> problems) {

        if (!expectedIssuer.equals(trim(raw))) {
            problems.add("Cognito issuer does not match this user pool. Expected "
                    + expectedIssuer);
        } else {
            checks.add("OK     Cognito issuer matches the user pool");
        }
    }

    /**
     * That this is running where the operator believes it is.
     *
     * <p>The operator states an account and an environment; the runtime
     * reports what is actually true, and {@link RuntimeVerification} compares
     * them. The first version of this compared the flag against itself - the
     * CLI passed the same value as both expected and actual - so it always
     * passed and verified nothing. A flag is an intention, not evidence.
     *
     * <p>Production is refused outright by every utility that uses this.
     * Production access changes need their own approved procedure, and a
     * utility whose safe default is a dry run is still not that procedure.
     */
    public static void environment(
            RuntimeIdentity runtime, String statedAccount, String statedEnvironment,
            String expectedEnvironment, boolean willWrite,
            List<String> checks, List<String> problems) {

        if (statedAccount == null) {
            problems.add("--aws-account-id is required");
        }
        if (statedEnvironment == null) {
            problems.add("--environment is required");
        }

        RuntimeVerification.verify(
                runtime, statedAccount, statedEnvironment, willWrite, checks, problems);

        if (statedEnvironment != null && !statedEnvironment.equals(expectedEnvironment)) {
            problems.add("environment does not match this build's expectation. Expected "
                    + expectedEnvironment + ", got " + statedEnvironment);
        }
        /*
         * Only the two values the flag is ever typed as. RuntimeVerification
         * independently sweeps the REAL cluster name for the full set of
         * production spellings ("prd" included) and is the check that cannot
         * be fooled by a flag; this one is belt-and-braces on what the
         * operator stated. Any other value is refused anyway by the
         * expected-environment comparison above, since that is a build
         * constant.
         */
        if ("prod".equals(statedEnvironment) || "production".equals(statedEnvironment)) {
            problems.add("This utility refuses to run against production. "
                    + "Production access changes need their own approved procedure.");
        }
    }
}
