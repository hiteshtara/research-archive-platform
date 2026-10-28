package edu.bu.archive.application.authorization;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * An identifier the archive issues itself.
 *
 * <p><strong>Why this exists.</strong> Grants are keyed on an
 * {@link InstitutionalIdentifier}, and the open question has been which BU
 * attribute that should be - a question only BU IAM can answer. This makes the
 * archive's own identity the answer instead: a value the archive mints, that
 * means nothing outside it, and that no other system can reassign.
 *
 * <p><strong>What it is not.</strong> Not an email address, not a login name,
 * not a Kuali {@code PRNCPL_ID}, not a Cognito subject. Each of those is owned
 * by someone else and can be changed or reassigned by them; a grant keyed on
 * one would follow the string rather than the person. The Kuali principal is
 * still recorded on the link, as {@code kuali_person_id}, for historical
 * research relationships - but it is a crosswalk, never the grant key.
 *
 * <p><strong>Format.</strong> {@code ARP-XXXXXXXX-XXXXXXXX}, Crockford base32
 * (no I, L, O or U, so a transcription slip is a rejected value rather than a
 * different person's identity). 80 bits of randomness, so collision is not a
 * practical concern and the value cannot be guessed or enumerated.
 *
 * <p>Minting is deliberately separate from accepting: an administrator mints
 * once, records it, and supplies it thereafter. Nothing derives it from a
 * person's attributes, so two people cannot be minted the same id by having
 * similar names, emails or roles.
 */
public final class ArchivePrincipalId {

    /** Crockford base32, less the ambiguous letters. */
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final Pattern FORMAT =
            Pattern.compile("^ARP-[0-9A-HJKMNP-TV-Z]{8}-[0-9A-HJKMNP-TV-Z]{8}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private ArchivePrincipalId() {
    }

    /** A new identifier. Random, never derived from anything about the person. */
    public static InstitutionalIdentifier mint() {
        StringBuilder value = new StringBuilder("ARP-");
        for (int i = 0; i < 16; i++) {
            if (i == 8) {
                value.append('-');
            }
            value.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return new InstitutionalIdentifier(value.toString());
    }

    /** True when {@code value} is a well-formed archive-issued identifier. */
    public static boolean isArchiveIssued(String value) {
        return value != null && FORMAT.matcher(value.trim().toUpperCase(Locale.ROOT)).matches();
    }

    /**
     * Normalise an operator-typed value, or throw.
     *
     * <p>Case is folded and surrounding space trimmed, because those are
     * transcription noise. Nothing else is repaired: a value that is not
     * well-formed is rejected rather than guessed at.
     */
    public static InstitutionalIdentifier parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("an archive principal id is required");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!FORMAT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "not a well-formed archive principal id (expected ARP-XXXXXXXX-XXXXXXXX "
                            + "in Crockford base32); refusing to guess what was meant");
        }
        return new InstitutionalIdentifier(normalized);
    }
}
