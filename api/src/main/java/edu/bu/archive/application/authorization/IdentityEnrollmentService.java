package edu.bu.archive.application.authorization;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side enrollment, Option A (authorization design 12.2 and 13):
 *
 * <pre>
 * validated access token (iss, sub, username)
 *   -> AdminGetUser(username); profile sub must equal the token sub
 *   -> profile federated through the configured SAML provider
 *   -> the ONE configured verified attribute
 *   -> exactly one ACTIVE crosswalk row -> an existing, ACTIVE KIM principal
 *   -> authz.identity_link (AUTO_VERIFIED, kuali_person_id = PRNCPL_ID, login_name NULL)
 * </pre>
 *
 * Fails closed: any refusal or failure links nothing, so the request stays
 * "access not provisioned". Every decision is audited (authz.access_audit)
 * with identifiers only. It never creates grants, and it never maps from
 * email or a login name.
 *
 * <p>An existing ACTIVE AUTO_VERIFIED link is re-checked on every request
 * (one indexed query): when its crosswalk row is no longer ACTIVE or its
 * principal is no longer active, the link is revoked and the request
 * denied. ADMIN_VERIFIED links were not created from the crosswalk and are
 * not re-checked against it.
 */
public class IdentityEnrollmentService implements IdentityEnrollment {

    public static final String ACTOR = "api-enrollment";
    /** identifier-attribute value that selects the NameID recorded in the profile's identities. */
    public static final String FEDERATED_USER_ID = "identities.userId";
    private static final int MAX_DEFERRED = 10_000;

    private final Settings settings;
    private final CognitoProfileReader profiles;
    private final EnrollmentStore store;
    private final Clock clock;
    private final Map<ValidatedCognitoIdentity, Instant> deferred = new ConcurrentHashMap<>();
    /** Links already re-verified for a sign-in session: key = link id + "|" + auth_time. */
    private final Map<String, Boolean> verifiedSessions = new ConcurrentHashMap<>();

    /**
     * @param samlProviderName       the Cognito identity provider name the profile must be federated through
     * @param identifierAttribute    the user-pool attribute carrying the verified value (e.g. custom:...),
     *                               or {@value #FEDERATED_USER_ID} to use the federated identity's
     *                               userId (the SAML NameID), which users cannot edit
     * @param crosswalkAttributeName authz.principal_crosswalk.attribute_name for that value
     * @param refusalRetry           how long a refusal is remembered before the profile is re-read (0 = never)
     */
    public record Settings(String samlProviderName, String identifierAttribute, String crosswalkAttributeName,
                           Duration refusalRetry) {
        public Settings {
            requireText(samlProviderName, "samlProviderName");
            requireText(identifierAttribute, "identifierAttribute");
            requireText(crosswalkAttributeName, "crosswalkAttributeName");
            refusalRetry = refusalRetry == null || refusalRetry.isNegative() ? Duration.ZERO : refusalRetry;
        }

        private static void requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " is required");
            }
        }
    }

    public IdentityEnrollmentService(Settings settings, CognitoProfileReader profiles, EnrollmentStore store,
                                     Clock clock) {
        this.settings = Objects.requireNonNull(settings);
        this.profiles = Objects.requireNonNull(profiles);
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public EnrollmentOutcome prepare(ValidatedCognitoIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        List<EnrollmentStore.StoredLink> links = store.links(identity);
        List<EnrollmentStore.StoredLink> active = links.stream().filter(EnrollmentStore.StoredLink::active).toList();
        if (!active.isEmpty()) {
            return revalidate(identity, active);
        }

        Instant now = clock.instant();
        Instant until = deferred.get(identity);
        if (until != null && now.isBefore(until)) {
            return EnrollmentOutcome.RETRY_DEFERRED;
        }
        deferred.remove(identity);

        EnrollmentOutcome outcome;
        try {
            outcome = enroll(identity, links);
        } catch (RuntimeException failure) {
            outcome = EnrollmentOutcome.FAILED;
            auditQuietly(identity, outcome, null, Map.of("error", failure.getClass().getSimpleName()));
        }
        if (outcome != EnrollmentOutcome.LINKED && outcome != EnrollmentOutcome.ALREADY_LINKED
                && !settings.refusalRetry().isZero()) {
            if (deferred.size() >= MAX_DEFERRED) {
                deferred.clear();
            }
            deferred.put(identity, now.plus(settings.refusalRetry()));
        }
        return outcome;
    }

    private EnrollmentOutcome revalidate(ValidatedCognitoIdentity identity,
                                         List<EnrollmentStore.StoredLink> active) {
        EnrollmentOutcome result = EnrollmentOutcome.LINK_STILL_VALID;
        for (EnrollmentStore.StoredLink link : active) {
            if (link.autoVerified()) {
                boolean valid = link.principalId() != null && store.mappingStillValid(
                        settings.crosswalkAttributeName(), link.institutionalIdentifier(), link.principalId());
                if (!valid) {
                    revokeLink(identity, link, EnrollmentOutcome.REVOKED_MAPPING_NO_LONGER_VALID, "crosswalk");
                    result = EnrollmentOutcome.REVOKED_MAPPING_NO_LONGER_VALID;
                    continue;
                }
            }
            EnrollmentOutcome session = verifySession(identity, link);
            if (session != EnrollmentOutcome.LINK_STILL_VALID) {
                result = session;
            }
        }
        return result;
    }

    /**
     * Once per link and sign-in session (token auth_time), re-read the Cognito profile - which
     * Cognito overwrites from the SAML assertion at every federated sign-in - and require that it
     * still supports the link. This is what stops a reassigned NameID (same Cognito username and
     * sub) from inheriting the previous person's link when the IdP now sends a different
     * identifier. It cannot detect a reassignment in which BU also reuses the identifier value;
     * that guarantee has to come from BU IAM.
     */
    private EnrollmentOutcome verifySession(ValidatedCognitoIdentity identity, EnrollmentStore.StoredLink link) {
        Optional<Instant> signIn = identity.signInTime();
        Optional<String> username = identity.tokenUsername();
        if (signIn.isEmpty() || username.isEmpty() || identity.canEditOwnAttributes()) {
            auditQuietly(identity, EnrollmentOutcome.FAILED_SESSION_VERIFICATION, link.institutionalIdentifier(),
                    Map.of("check", signIn.isEmpty() ? "no_auth_time"
                            : username.isEmpty() ? "no_username" : "self_editable_token"));
            return EnrollmentOutcome.FAILED_SESSION_VERIFICATION;
        }
        String key = link.id() + "|" + signIn.get().getEpochSecond();
        if (verifiedSessions.containsKey(key)) {
            return EnrollmentOutcome.LINK_STILL_VALID;
        }
        Optional<CognitoProfile> read;
        try {
            read = profiles.read(username.get());
        } catch (RuntimeException failure) {
            auditQuietly(identity, EnrollmentOutcome.FAILED_SESSION_VERIFICATION, link.institutionalIdentifier(),
                    Map.of("check", "profile_read", "error", failure.getClass().getSimpleName()));
            return EnrollmentOutcome.FAILED_SESSION_VERIFICATION;
        }
        String problem = null;
        if (read.isEmpty()) {
            problem = "profile_missing";
        } else {
            CognitoProfile profile = read.get();
            if (!identity.subject().equals(profile.sub())) {
                problem = "sub_mismatch";
            } else if (!profile.enabled()) {
                problem = "profile_disabled";
            } else if (profile.identities().stream()
                    .noneMatch(i -> settings.samlProviderName().equals(i.providerName()))) {
                problem = "not_federated";
            } else if (!identifierOf(profile).map(v -> v.equals(link.institutionalIdentifier())).orElse(false)) {
                problem = identifierOf(profile).isEmpty() ? "identifier_missing" : "identifier_changed";
            }
        }
        if (problem != null) {
            revokeLink(identity, link, EnrollmentOutcome.REVOKED_IDENTITY_EVIDENCE_CHANGED, problem);
            return EnrollmentOutcome.REVOKED_IDENTITY_EVIDENCE_CHANGED;
        }
        if (verifiedSessions.size() >= MAX_DEFERRED) {
            verifiedSessions.clear();
        }
        verifiedSessions.put(key, Boolean.TRUE);
        return EnrollmentOutcome.LINK_STILL_VALID;
    }

    /** The verified identifier from the profile: a mapped attribute, or the provider's NameID. */
    private Optional<String> identifierOf(CognitoProfile profile) {
        if (FEDERATED_USER_ID.equals(settings.identifierAttribute())) {
            return profile.identities().stream()
                    .filter(i -> settings.samlProviderName().equals(i.providerName()))
                    .map(CognitoProfile.FederatedIdentity::userId)
                    .filter(v -> v != null && !v.isBlank())
                    .findFirst();
        }
        return profile.attribute(settings.identifierAttribute());
    }

    private void revokeLink(ValidatedCognitoIdentity identity, EnrollmentStore.StoredLink link,
                            EnrollmentOutcome outcome, String check) {
        Map<String, Object> detail = detail(identity, outcome);
        detail.put("identity_link_id", link.id());
        detail.put("check", check);
        if (link.principalId() != null) {
            detail.put("kuali_person_id", link.principalId());
        }
        store.revoke(link.id(), ACTOR, new EnrollmentStore.Audit(ACTOR, outcome.auditAction(),
                link.institutionalIdentifier(), detail));
    }

    private EnrollmentOutcome enroll(ValidatedCognitoIdentity identity, List<EnrollmentStore.StoredLink> links) {
        // A revoked profile is never re-linked automatically; an administrator decides.
        if (!links.isEmpty()) {
            return refuse(identity, EnrollmentOutcome.REFUSED_PREVIOUSLY_REVOKED, null, Map.of());
        }
        Optional<String> username = identity.tokenUsername();
        if (username.isEmpty()) {
            return refuse(identity, EnrollmentOutcome.REFUSED_NO_USERNAME, null, Map.of());
        }
        if (identity.canEditOwnAttributes()) {
            return refuse(identity, EnrollmentOutcome.REFUSED_SELF_EDITABLE_TOKEN, null, Map.of());
        }

        Optional<CognitoProfile> read;
        try {
            read = profiles.read(username.get());
        } catch (RuntimeException failure) {
            EnrollmentOutcome outcome = EnrollmentOutcome.FAILED_PROFILE_READ;
            auditQuietly(identity, outcome, null, Map.of("error", failure.getClass().getSimpleName()));
            return outcome;
        }
        if (read.isEmpty()) {
            return refuse(identity, EnrollmentOutcome.REFUSED_PROFILE_NOT_FOUND, null, Map.of());
        }
        CognitoProfile profile = read.get();
        if (!identity.subject().equals(profile.sub())) {
            return refuse(identity, EnrollmentOutcome.REFUSED_SUBJECT_MISMATCH, null, Map.of());
        }
        if (!profile.enabled()) {
            return refuse(identity, EnrollmentOutcome.REFUSED_PROFILE_DISABLED, null, Map.of());
        }
        boolean federated = profile.identities().stream()
                .anyMatch(i -> settings.samlProviderName().equals(i.providerName()));
        if (!federated) {
            return refuse(identity, EnrollmentOutcome.REFUSED_NOT_FEDERATED, null, Map.of());
        }
        Optional<String> value = identifierOf(profile);
        if (value.isEmpty()) {
            return refuse(identity, EnrollmentOutcome.REFUSED_MISSING_IDENTIFIER, null, Map.of());
        }

        List<String> principals = store.activeCrosswalkPrincipals(settings.crosswalkAttributeName(), value.get());
        if (principals.isEmpty()) {
            // The value is not in any authz table, so it is not recorded.
            return refuse(identity, EnrollmentOutcome.REFUSED_UNKNOWN_PERSON, null, Map.of());
        }
        String identifier = value.get();
        if (principals.size() > 1) {
            return refuse(identity, EnrollmentOutcome.REFUSED_AMBIGUOUS_MAPPING, identifier,
                    Map.of("candidates", principals.size()));
        }
        String principal = principals.get(0);
        Optional<Boolean> principalActive = store.principalActive(principal);
        if (principalActive.isEmpty()) {
            return refuse(identity, EnrollmentOutcome.REFUSED_NOT_A_KIM_PRINCIPAL, identifier, Map.of());
        }
        if (!principalActive.get()) {
            return refuse(identity, EnrollmentOutcome.REFUSED_INACTIVE_PRINCIPAL, identifier,
                    Map.of("kuali_person_id", principal));
        }

        Map<String, Object> linkedDetail = detail(identity, EnrollmentOutcome.LINKED);
        linkedDetail.put("kuali_person_id", principal);
        EnrollmentStore.LinkResult result = store.link(identity, identifier, principal, ACTOR,
                new EnrollmentStore.Audit(ACTOR, EnrollmentOutcome.LINKED.auditAction(), identifier, linkedDetail));
        return switch (result) {
            case LINKED -> {
                // The profile was just read for this sign-in session: no second read on the next request.
                identity.signInTime().ifPresent(signIn -> store.links(identity).stream()
                        .filter(EnrollmentStore.StoredLink::active)
                        .forEach(l -> verifiedSessions.put(l.id() + "|" + signIn.getEpochSecond(), Boolean.TRUE)));
                yield EnrollmentOutcome.LINKED;
            }
            case ALREADY_LINKED -> {
                auditQuietly(identity, EnrollmentOutcome.ALREADY_LINKED, identifier, Map.of());
                yield EnrollmentOutcome.ALREADY_LINKED;
            }
            case IDENTIFIER_LINKED_TO_ANOTHER_PROFILE -> refuse(identity,
                    EnrollmentOutcome.REFUSED_IDENTIFIER_LINKED_TO_ANOTHER_PROFILE, identifier,
                    Map.of("kuali_person_id", principal));
        };
    }

    private EnrollmentOutcome refuse(ValidatedCognitoIdentity identity, EnrollmentOutcome outcome,
                                     String identifier, Map<String, Object> extra) {
        Map<String, Object> detail = detail(identity, outcome);
        detail.putAll(extra);
        store.audit(new EnrollmentStore.Audit(ACTOR, outcome.auditAction(), identifier, detail));
        return outcome;
    }

    /** Audits a failure without letting an audit failure mask it (the request still fails closed). */
    private void auditQuietly(ValidatedCognitoIdentity identity, EnrollmentOutcome outcome, String identifier,
                              Map<String, Object> extra) {
        try {
            Map<String, Object> detail = detail(identity, outcome);
            detail.putAll(extra);
            store.audit(new EnrollmentStore.Audit(ACTOR, outcome.auditAction(), identifier, detail));
        } catch (RuntimeException ignored) {
            // Nothing was linked; the request is refused regardless.
        }
    }

    private Map<String, Object> detail(ValidatedCognitoIdentity identity, EnrollmentOutcome outcome) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("outcome", outcome.name());
        detail.put("cognito_issuer", identity.issuer());
        detail.put("cognito_subject", identity.subject());
        detail.put("attribute_name", settings.crosswalkAttributeName());
        return detail;
    }
}
