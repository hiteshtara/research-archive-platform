package edu.bu.archive.application.authorization;

import java.util.LinkedHashSet;
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

    /** The configured policy, or empty when any strategy is missing. */
    public Optional<AuthorizationPolicy> policy() {
        if (versionScope == null || departmentMatch == null || researchStaffRoles.isEmpty()) {
            return Optional.empty();
        }
        Set<String> roles = new LinkedHashSet<>();
        researchStaffRoles.forEach(role -> roles.add(role.trim().toUpperCase()));
        return Optional.of(new AuthorizationPolicy(versionScope, departmentMatch, roles));
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
}
