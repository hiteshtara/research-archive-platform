package edu.bu.archive.application.authorization;

import org.springframework.beans.factory.ObjectProvider;

/**
 * What every per-module record-authorization port has in common.
 *
 * <p>Award and Proposal each have their own port because their record
 * operations differ - {@code requireAward(long)} is not
 * {@code requireProposal(long)}, and the SQL predicate is built for a
 * different module and alias. But three things are identical, and were
 * duplicated when the second module arrived: whether the caller has any
 * access at all, whether the caller is unrestricted, and the startup refusal
 * that stops a permissive fallback being used while enforcement is enabled.
 *
 * <p>This is a declaration-sharing supertype, not new behaviour. There
 * remains exactly one implementation of the rules themselves, in
 * {@link RecordAuthorizationService}.
 */
public interface RecordAccess {

    /**
     * Throws when the caller has no archive access at all, so an
     * unprovisioned user receives an explicit problem rather than an empty
     * list that reads as an empty archive.
     *
     * @throws AccessNotProvisionedException     no identity link, or no active grant
     * @throws IdentityAccessDeniedException     ambiguous, revoked or suspended
     * @throws ReauthenticationRequiredException the BU sign-in is too old
     */
    void requireProvisioned();

    /** True when enforcement is off, or the caller is Central. */
    boolean unrestricted();

    /**
     * Refuses to start when record authorization is ENABLED but the only
     * available implementation for a module is its permissive fallback.
     *
     * <p>Without it, a context where the Phase 1 beans are missing, excluded
     * or failed to initialise would serve every record to every
     * authenticated caller while configuration claimed enforcement was on -
     * an operator reading {@code enforcement-enabled=true} would have
     * positive confirmation of a control that was not running. Failing
     * startup is loud and cannot be mistaken for working.
     *
     * <p>Enforcement OFF with the permissive fallback is the supported
     * configuration today and is left alone.
     *
     * @param resolved   the port the service actually resolved
     * @param permitAll  that port's permissive fallback, compared by identity
     * @param portName   named in the message so an operator can act on it
     */
    static void requireEnforceableWiring(
            RecordAccess resolved,
            RecordAccess permitAll,
            ObjectProvider<AuthorizationProperties> properties,
            String portName
    ) {
        if (resolved != permitAll) {
            return;
        }
        AuthorizationProperties settings = properties == null ? null : properties.getIfAvailable();
        if (settings != null && settings.isEnforcementEnabled()) {
            throw new IllegalStateException(
                    "app.authorization.enforcement-enabled is true but no " + portName
                            + " implementation is available, so record authorization would "
                            + "silently permit every record. Refusing to start. Ensure the record "
                            + "authorization beans (AuthorizationConfiguration) are present.");
        }
    }
}
