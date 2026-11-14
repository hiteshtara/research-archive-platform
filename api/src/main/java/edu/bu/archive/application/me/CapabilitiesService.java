package edu.bu.archive.application.me;

import edu.bu.archive.adapter.in.web.dto.me.CapabilitiesResponse;
import edu.bu.archive.application.authorization.AuthorizationProperties;
import edu.bu.archive.application.authorization.CurrentIdentityProvider;
import edu.bu.archive.application.authorization.IdentityResolution;
import edu.bu.archive.application.authorization.IdentityResolver;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;
import edu.bu.archive.application.authorization.admin.AdminRoleRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Resolves the signed-in caller's capabilities.
 *
 * <p><strong>Why this does not call the administrator API.</strong> The
 * administrator tree is gated on enforcement being enabled and returns 404
 * when it is off. A UI asking it "am I an administrator?" would receive 404
 * (feature off), 403 (not an administrator) or 200 (administrator) and
 * cannot tell the first two apart - which is exactly the question this
 * exists to answer. So membership is read **directly** from
 * {@link AdminRoleRepository}, which is a plain {@code @Repository} with no
 * enforcement gate, and the enforcement flag is read from configuration.
 * Both work with enforcement off, which is the state this must work in.
 *
 * <p><strong>Why a suspended administrator is not an administrator.</strong>
 * Not by a special case - structurally. {@link IdentityResolution} is a
 * sealed type and only its {@code Mapped} variant carries an
 * {@code institutionalIdentifier}. {@code Suspended} (like
 * {@code Revoked}, {@code Ambiguous} and {@code NotProvisioned}) carries
 * none, so there is no identifier with which to look a membership up. A
 * suspended identity holding an ACTIVE membership row therefore cannot
 * reach the membership read at all.
 *
 * <p><strong>Fail closed.</strong> Every fault - a missing identity, an
 * unresolvable one, a repository error - produces
 * {@link CapabilitiesResponse#denied()} and a server-side log line. The
 * method does not throw, because a thrown exception would become a 500 and
 * the UI would have to interpret it; a uniform denial cannot be
 * misinterpreted.
 */
@Service
public class CapabilitiesService {

    private static final Logger log = LoggerFactory.getLogger(CapabilitiesService.class);

    private final CurrentIdentityProvider identities;
    private final IdentityResolver identityResolver;
    private final AdminRoleRepository adminRoles;
    private final AuthorizationProperties authorization;

    public CapabilitiesService(
            CurrentIdentityProvider identities,
            IdentityResolver identityResolver,
            AdminRoleRepository adminRoles,
            AuthorizationProperties authorization
    ) {
        this.identities = identities;
        this.identityResolver = identityResolver;
        this.adminRoles = adminRoles;
        this.authorization = authorization;
    }

    public CapabilitiesResponse current() {
        Optional<ValidatedCognitoIdentity> cognitoIdentity;
        try {
            cognitoIdentity = identities.current();
        } catch (RuntimeException exception) {
            log.warn("capabilities: could not read the current identity; denying", exception);
            return CapabilitiesResponse.denied();
        }
        if (cognitoIdentity.isEmpty()) {
            // Authenticated at the filter chain but no usable identity here.
            return CapabilitiesResponse.denied();
        }

        IdentityResolution resolution;
        try {
            resolution = identityResolver.resolve(cognitoIdentity.get());
        } catch (RuntimeException exception) {
            log.warn("capabilities: identity resolution failed; denying", exception);
            return CapabilitiesResponse.denied();
        }

        // Only Mapped carries an institutional identifier. NotProvisioned,
        // Ambiguous, Revoked and Suspended all deny here, by construction
        // rather than by an enumerated list that a new variant could escape.
        if (!(resolution instanceof IdentityResolution.Mapped mapped)) {
            return CapabilitiesResponse.denied();
        }

        boolean administrator;
        try {
            administrator = adminRoles.findActive(mapped.institutionalIdentifier()).isPresent();
        } catch (RuntimeException exception) {
            log.warn("capabilities: ARCHIVE_ADMIN membership read failed; denying", exception);
            return CapabilitiesResponse.denied();
        }

        if (!administrator) {
            return CapabilitiesResponse.denied();
        }
        return CapabilitiesResponse.administrator(
                authorization.isEnforcementEnabled(),
                authorization.isAdministrationEnabled(),
                authorization.isAdministrationWritesEnabled());
    }
}
