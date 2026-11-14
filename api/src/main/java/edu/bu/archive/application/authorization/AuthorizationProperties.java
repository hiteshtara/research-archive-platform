package edu.bu.archive.application.authorization;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.authorization.*}. Enforcement is OFF by default, which keeps
 * today's behaviour ("record authorization not enforced"). The policy
 * strategies have NO defaults: an enforced deployment without them denies
 * every request rather than guessing an unapproved policy.
 */
@ConfigurationProperties(prefix = "app.authorization")
public class AuthorizationProperties {

    private boolean enforcementEnabled;

    /**
     * Whether the administrative API (/api/v1/admin) answers at all.
     *
     * <p>Deliberately SEPARATE from {@link #enforcementEnabled}. Administrator
     * identity resolution and ARCHIVE_ADMIN membership never depended on
     * record enforcement - the old 404 was a policy choice ("do not offer
     * tools that cannot work"), not a technical dependency. Keeping them one
     * flag made "manage administrators while records are unenforced"
     * unexpressible.
     *
     * <p>Turning this on exposes the surface. It grants nothing: every
     * endpoint still resolves its own actor and calls requireAdministrator,
     * so a non-administrator gets 403 rather than data.
     *
     * <p>Defaults to false, so merging this change alters no deployment.
     */
    private boolean administrationEnabled;

    /**
     * Whether the EIGHT administrative WRITE endpoints are permitted.
     *
     * <p>Read-only administration is the first rollout step, and a read-only
     * phase enforced only by not clicking anything is not a control - this is
     * checked server-side, before the actor is resolved.
     *
     * <p>Separate from {@link #administrationEnabled} because the write paths
     * carry open safeguard gaps (self-granting, grant expansion via
     * includeDescendants, and single-administrator promotion). While record
     * enforcement is off a bad grant has no visible effect, so it would sit
     * undetected until enforcement is switched on.
     *
     * <p>Defaults to false. Has no effect unless administrationEnabled is
     * also true.
     */
    private boolean administrationWritesEnabled;

    private AuthorizationPolicy.VersionScope versionScope;
    private AuthorizationPolicy.DepartmentMatch departmentMatch;
    private Set<String> researchStaffRoles = new LinkedHashSet<>();
    private AuthorizationPolicy.ContactDerivation contactDerivation;

    /**
     * SEC-005: parent-to-child hops a PARENT_AWARD grant may follow.
     * Conservative default of one level; raising it is a policy change.
     */
    private int parentAwardDepth = AuthorizationPolicy.DEFAULT_PARENT_AWARD_DEPTH;
    /**
     * The longest time since the caller's last BU sign-in ({@code auth_time})
     * that an enforced request accepts. A Cognito refresh keeps the original
     * {@code auth_time}, so without this a refresh token (30 days) would keep a
     * session alive with no new BU login and no per-session re-check. Required
     * when enforcement is on; NO default.
     */
    private Duration maxSignInAge;
    private final Enrollment enrollment = new Enrollment();

    /**
     * Everything that is wrong with the enforcement configuration, as
     * property names an operator can act on.
     *
     * <p>Returns ALL of them, not the first. Someone turning enforcement on
     * is setting five values at once; failing on one at a time means five
     * restarts to discover five problems, which is how people start guessing.
     *
     * <p>Empty when enforcement is off: nothing here is required until it is.
     */
    public List<String> enforcementConfigurationProblems() {
        List<String> problems = new ArrayList<>();
        if (!enforcementEnabled) {
            return problems;
        }
        if (versionScope == null) {
            problems.add("app.authorization.version-scope is not set (one of: "
                    + names(AuthorizationPolicy.VersionScope.values()) + ")");
        }
        if (departmentMatch == null) {
            problems.add("app.authorization.department-match is not set (one of: "
                    + names(AuthorizationPolicy.DepartmentMatch.values()) + ")");
        }
        if (contactDerivation == null) {
            problems.add("app.authorization.contact-derivation is not set (one of: "
                    + names(AuthorizationPolicy.ContactDerivation.values()) + ")");
        }
        if (researchStaffRoles.isEmpty()) {
            problems.add("app.authorization.research-staff-roles is empty (the Kuali contact role "
                    + "codes that count as Research Staff, e.g. PI)");
        } else if (researchStaffRoles.stream().anyMatch(role -> role == null || role.isBlank())) {
            problems.add("app.authorization.research-staff-roles contains a blank role code");
        }
        /*
         * Not part of policy(), but required by RecordAuthorizationService on
         * every enforced request. Left unset it denies at request time rather
         * than at startup - the same late failure this validator exists to
         * move forward.
         */
        if (maxSignInAge == null) {
            problems.add("app.authorization.max-sign-in-age is not set (e.g. 12h; a Cognito refresh "
                    + "keeps the original auth_time, so without it a refresh token keeps a session "
                    + "alive with no new BU login)");
        } else if (maxSignInAge.isZero() || maxSignInAge.isNegative()) {
            problems.add("app.authorization.max-sign-in-age must be positive, but is " + maxSignInAge);
        }
        if (parentAwardDepth < 0) {
            problems.add("app.authorization.parent-award-depth must not be negative, but is " + parentAwardDepth);
        } else if (parentAwardDepth > AuthorizationPolicy.MAX_PARENT_AWARD_DEPTH) {
            problems.add("app.authorization.parent-award-depth is " + parentAwardDepth
                    + ", above the maximum supported depth of "
                    + AuthorizationPolicy.MAX_PARENT_AWARD_DEPTH
                    + " - the traversal would clamp to the maximum, so the configured value "
                    + "would not mean what it says");
        }
        return problems;
    }

    private static String names(Enum<?>[] values) {
        List<String> all = new ArrayList<>();
        for (Enum<?> value : values) {
            all.add(value.name());
        }
        return String.join(", ", all);
    }

    /** The configured policy, or empty when any strategy is missing. */
    public Optional<AuthorizationPolicy> policy() {
        if (versionScope == null || departmentMatch == null || researchStaffRoles.isEmpty()
                || contactDerivation == null) {
            return Optional.empty();
        }
        Set<String> roles = new LinkedHashSet<>();
        researchStaffRoles.forEach(role -> roles.add(role.trim().toUpperCase()));
        return Optional.of(new AuthorizationPolicy(
                versionScope, departmentMatch, roles, parentAwardDepth, contactDerivation));
    }

    public boolean isEnforcementEnabled() {
        return enforcementEnabled;
    }

    public void setEnforcementEnabled(boolean enforcementEnabled) {
        this.enforcementEnabled = enforcementEnabled;
    }

    public boolean isAdministrationEnabled() {
        return administrationEnabled;
    }

    public void setAdministrationEnabled(boolean administrationEnabled) {
        this.administrationEnabled = administrationEnabled;
    }

    /**
     * Writes are permitted only when the surface is open as well, so a
     * writes-only configuration cannot accidentally expose anything.
     */
    public boolean isAdministrationWritesEnabled() {
        return administrationEnabled && administrationWritesEnabled;
    }

    public void setAdministrationWritesEnabled(boolean administrationWritesEnabled) {
        this.administrationWritesEnabled = administrationWritesEnabled;
    }

    public AuthorizationPolicy.VersionScope getVersionScope() {
        return versionScope;
    }

    public void setVersionScope(AuthorizationPolicy.VersionScope versionScope) {
        this.versionScope = versionScope;
    }

    public AuthorizationPolicy.DepartmentMatch getDepartmentMatch() {
        return departmentMatch;
    }

    public void setDepartmentMatch(AuthorizationPolicy.DepartmentMatch departmentMatch) {
        this.departmentMatch = departmentMatch;
    }

    public Set<String> getResearchStaffRoles() {
        return researchStaffRoles;
    }

    public void setResearchStaffRoles(Set<String> researchStaffRoles) {
        this.researchStaffRoles = researchStaffRoles == null ? new LinkedHashSet<>() : researchStaffRoles;
    }

    public AuthorizationPolicy.ContactDerivation getContactDerivation() {
        return contactDerivation;
    }

    public int getParentAwardDepth() {
        return parentAwardDepth;
    }

    public void setParentAwardDepth(int parentAwardDepth) {
        this.parentAwardDepth = parentAwardDepth;
    }

    public void setContactDerivation(AuthorizationPolicy.ContactDerivation contactDerivation) {
        this.contactDerivation = contactDerivation;
    }

    public Duration getMaxSignInAge() {
        return maxSignInAge;
    }

    public void setMaxSignInAge(Duration maxSignInAge) {
        this.maxSignInAge = maxSignInAge;
    }

    /** True when a usable maximum sign-in age is configured. */
    public boolean hasMaxSignInAge() {
        return maxSignInAge != null && !maxSignInAge.isNegative() && !maxSignInAge.isZero();
    }

    public Enrollment getEnrollment() {
        return enrollment;
    }

    /**
     * {@code app.authorization.enrollment.*}: server-side enrollment (design
     * 12.2 Option A). OFF by default. When enabled, every setting except
     * {@code endpoint-override} is required and has NO default; the API
     * refuses to start without them. No credentials are configured here: the
     * AWS default credentials provider chain supplies them.
     */
    public static class Enrollment {

        private boolean enabled;
        private String userPoolId;
        private String region;
        private String endpointOverride;
        private String samlProviderName;
        private String identifierAttribute;
        private String crosswalkAttributeName;
        private long refusalRetrySeconds = 60;
        /**
         * Identity freshness. The SAML NameID ("identities.userId") is the profile key Cognito
         * matched on THIS sign-in, so it is always fresh. A mapped attribute is only as fresh as
         * Cognito's attribute handling: AWS does not document that a mapped attribute missing from
         * a later assertion is cleared, so a retained, outdated value cannot be told apart from a
         * fresh one. Using one therefore requires this explicit acceptance (default false).
         */
        private boolean acceptMappedAttributeIdentifier;
        /**
         * The user pool's UsernameConfiguration.CaseSensitive. Required, no default: a wrong value
         * either refuses every federated user (case-insensitive pool treated as sensitive) or
         * compares more loosely than the pool does.
         */
        private Boolean usernameCaseSensitive;

        /** Property keys that must be set when enrollment is enabled but are not. */
        public List<String> missingSettings() {
            List<String> missing = new ArrayList<>();
            if (blank(userPoolId)) missing.add("app.authorization.enrollment.user-pool-id");
            if (blank(region)) missing.add("app.authorization.enrollment.region");
            if (blank(samlProviderName)) missing.add("app.authorization.enrollment.saml-provider-name");
            if (blank(identifierAttribute)) missing.add("app.authorization.enrollment.identifier-attribute");
            if (blank(crosswalkAttributeName)) missing.add("app.authorization.enrollment.crosswalk-attribute-name");
            if (usernameCaseSensitive == null) {
                missing.add("app.authorization.enrollment.username-case-sensitive (true|false: the user pool's "
                        + "UsernameConfiguration.CaseSensitive)");
            }
            if (!blank(identifierAttribute) && !"identities.userId".equals(identifierAttribute.trim())
                    && !acceptMappedAttributeIdentifier) {
                missing.add("app.authorization.enrollment.accept-mapped-attribute-identifier=true (identifier-attribute '"
                        + identifierAttribute.trim() + "' is a mapped attribute whose freshness Cognito does not "
                        + "guarantee; use identities.userId (the NameID) or accept the risk explicitly)");
            }
            return missing;
        }

        public Boolean getUsernameCaseSensitive() {
            return usernameCaseSensitive;
        }

        public void setUsernameCaseSensitive(Boolean usernameCaseSensitive) {
            this.usernameCaseSensitive = usernameCaseSensitive;
        }

        public boolean isAcceptMappedAttributeIdentifier() {
            return acceptMappedAttributeIdentifier;
        }

        public void setAcceptMappedAttributeIdentifier(boolean acceptMappedAttributeIdentifier) {
            this.acceptMappedAttributeIdentifier = acceptMappedAttributeIdentifier;
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUserPoolId() {
            return userPoolId;
        }

        public void setUserPoolId(String userPoolId) {
            this.userPoolId = userPoolId;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getEndpointOverride() {
            return endpointOverride;
        }

        public void setEndpointOverride(String endpointOverride) {
            this.endpointOverride = endpointOverride;
        }

        public String getSamlProviderName() {
            return samlProviderName;
        }

        public void setSamlProviderName(String samlProviderName) {
            this.samlProviderName = samlProviderName;
        }

        public String getIdentifierAttribute() {
            return identifierAttribute;
        }

        public void setIdentifierAttribute(String identifierAttribute) {
            this.identifierAttribute = identifierAttribute;
        }

        public String getCrosswalkAttributeName() {
            return crosswalkAttributeName;
        }

        public void setCrosswalkAttributeName(String crosswalkAttributeName) {
            this.crosswalkAttributeName = crosswalkAttributeName;
        }

        public long getRefusalRetrySeconds() {
            return refusalRetrySeconds;
        }

        public void setRefusalRetrySeconds(long refusalRetrySeconds) {
            this.refusalRetrySeconds = refusalRetrySeconds;
        }
    }
}
