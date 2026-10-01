package edu.bu.archive.application.authorization;

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
    private AuthorizationPolicy.VersionScope versionScope;
    private AuthorizationPolicy.DepartmentMatch departmentMatch;
    private Set<String> researchStaffRoles = new LinkedHashSet<>();
    private AuthorizationPolicy.ContactDerivation contactDerivation;
    private final Enrollment enrollment = new Enrollment();

    /** The configured policy, or empty when any strategy is missing. */
    public Optional<AuthorizationPolicy> policy() {
        if (versionScope == null || departmentMatch == null || researchStaffRoles.isEmpty()
                || contactDerivation == null) {
            return Optional.empty();
        }
        Set<String> roles = new LinkedHashSet<>();
        researchStaffRoles.forEach(role -> roles.add(role.trim().toUpperCase()));
        return Optional.of(new AuthorizationPolicy(versionScope, departmentMatch, roles, contactDerivation));
    }

    public boolean isEnforcementEnabled() {
        return enforcementEnabled;
    }

    public void setEnforcementEnabled(boolean enforcementEnabled) {
        this.enforcementEnabled = enforcementEnabled;
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

    public void setContactDerivation(AuthorizationPolicy.ContactDerivation contactDerivation) {
        this.contactDerivation = contactDerivation;
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

        /** Property keys that must be set when enrollment is enabled but are not. */
        public List<String> missingSettings() {
            List<String> missing = new ArrayList<>();
            if (blank(userPoolId)) missing.add("app.authorization.enrollment.user-pool-id");
            if (blank(region)) missing.add("app.authorization.enrollment.region");
            if (blank(samlProviderName)) missing.add("app.authorization.enrollment.saml-provider-name");
            if (blank(identifierAttribute)) missing.add("app.authorization.enrollment.identifier-attribute");
            if (blank(crosswalkAttributeName)) missing.add("app.authorization.enrollment.crosswalk-attribute-name");
            if (!blank(identifierAttribute) && !"identities.userId".equals(identifierAttribute.trim())
                    && !acceptMappedAttributeIdentifier) {
                missing.add("app.authorization.enrollment.accept-mapped-attribute-identifier=true (identifier-attribute '"
                        + identifierAttribute.trim() + "' is a mapped attribute whose freshness Cognito does not "
                        + "guarantee; use identities.userId (the NameID) or accept the risk explicitly)");
            }
            return missing;
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
