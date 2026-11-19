package edu.bu.archive.application.authorization.viewaccessas;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import edu.bu.archive.application.authorization.AccessGrant;
import edu.bu.archive.application.authorization.GrantType;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.KualiPersonId;

/**
 * A grant shape to evaluate against, named for a tester.
 *
 * <p><strong>Not a person and not a session.</strong> A profile is the set of
 * grants a hypothetical holder would have. It carries no credential, no token
 * and no Cognito identity that any real sign-in could produce, which is why
 * this is simulation rather than impersonation: there is nothing here to
 * authenticate as.
 *
 * <p>Grants are built as {@link AccessGrant} values and handed to an in-memory
 * repository. Nothing is written to {@code authz.access_grant}, so a profile
 * cannot outlive the JVM, let alone authorise a real request.
 */
public record SyntheticProfile(
        String name,
        String description,
        List<AccessGrant> grants,
        Optional<KualiPersonId> kualiPersonId
) {

    /** The institutional identity every profile is keyed on. Synthetic; never enrolled. */
    public static final InstitutionalIdentifier GRANTEE =
            new InstitutionalIdentifier("ARP-SIMULATED-00000000");

    public SyntheticProfile {
        grants = List.copyOf(grants);
    }

    public static Builder named(String name, String description) {
        return new Builder(name, description);
    }

    /** Builds the grant rows a profile implies. Ids are positional and local. */
    public static final class Builder {

        private final String name;
        private final String description;
        private final List<AccessGrant> grants = new ArrayList<>();
        private Optional<KualiPersonId> person = Optional.empty();

        private Builder(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public Builder central() {
            return add(GrantType.CENTRAL, null, false, null, null, null, null);
        }

        public Builder unit(String unitNumber, boolean includeDescendants) {
            return add(GrantType.UNIT, unitNumber, includeDescendants, null, null, null, null);
        }

        public Builder io(String ioValue) {
            return add(GrantType.IO, null, false, ioValue, null, null, null);
        }

        /**
         * An explicit CONTACT_DERIVATION grant plus the Kuali principal it
         * applies to. Both are required: the resolver only carries a contact
         * person into the scope when the identity actually has one.
         */
        public Builder researchStaff(String kualiPersonId) {
            this.person = Optional.of(new KualiPersonId(kualiPersonId));
            return add(GrantType.CONTACT_DERIVATION, null, false, null, null, null, null);
        }

        /** A UNIT grant that has been revoked: active-at is false forever after. */
        public Builder revokedUnit(String unitNumber, Instant revokedAt) {
            return add(GrantType.UNIT, unitNumber, false, null, null, null, revokedAt);
        }

        /** A UNIT grant whose expiry has passed. Never revoked - still occupies its V087 scope. */
        public Builder expiredUnit(String unitNumber, Instant expiresAt) {
            return add(GrantType.UNIT, unitNumber, false, null, Instant.EPOCH, expiresAt, null);
        }

        /** A UNIT grant that has not started yet. */
        public Builder futureUnit(String unitNumber, Instant validFrom) {
            return add(GrantType.UNIT, unitNumber, false, null, validFrom, null, null);
        }

        private Builder add(GrantType type, String unit, boolean descendants, String io,
                            Instant validFrom, Instant expiresAt, Instant revokedAt) {
            grants.add(new AccessGrant(grants.size() + 1L, GRANTEE, type, unit, descendants, io,
                    validFrom, expiresAt, revokedAt));
            return this;
        }

        public SyntheticProfile build() {
            return new SyntheticProfile(name, description, grants, person);
        }
    }
}
