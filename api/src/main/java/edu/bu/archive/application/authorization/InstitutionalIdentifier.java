package edu.bu.archive.application.authorization;

/**
 * A verified, stable institutional person identifier. Which BU identifier
 * this is (and from which SAML attribute it arrives) is NOT decided: it is
 * awaiting BU IAM. The value is private: {@link #toString()} masks it so it
 * cannot leak into logs or error bodies by accident.
 */
public record InstitutionalIdentifier(String value) {

    public InstitutionalIdentifier {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("institutional identifier is required");
        }
    }

    @Override
    public String toString() {
        return "InstitutionalIdentifier[****]";
    }
}
