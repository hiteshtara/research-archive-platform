package edu.bu.archive.adapter.in.web;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import edu.bu.archive.adapter.in.web.dto.admin.AdminAuditEntryResponse;
import edu.bu.archive.adapter.in.web.dto.admin.AdminGrantResponse;
import edu.bu.archive.adapter.in.web.dto.admin.AdminIdentityResponse;
import edu.bu.archive.adapter.in.web.dto.admin.AdminIdentitySearchResponse;
import edu.bu.archive.adapter.in.web.dto.admin.AdminLinkResponse;
import edu.bu.archive.adapter.in.web.dto.admin.AdminRequests;
import edu.bu.archive.adapter.in.web.dto.admin.AdminRoleMemberResponse;
import edu.bu.archive.application.authorization.AuthorizationProperties;
import edu.bu.archive.application.authorization.CurrentIdentityProvider;
import edu.bu.archive.application.authorization.GrantType;
import edu.bu.archive.application.authorization.IdentityResolution;
import edu.bu.archive.application.authorization.IdentityResolver;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.KualiPersonId;
import edu.bu.archive.application.authorization.admin.AdminAuditAction;
import edu.bu.archive.application.authorization.admin.ArchiveAdminService;
import edu.bu.archive.application.authorization.admin.AuditQuery;
import edu.bu.archive.application.authorization.admin.GrantSpecification;
import edu.bu.archive.application.authorization.admin.NotAnArchiveAdministratorException;

/**
 * The administrative API: find and verify identities, manage grants,
 * suspension, revocation, audit history and ARCHIVE_ADMIN membership.
 *
 * <p><strong>The controller enforces nothing.</strong> Every method resolves
 * the acting administrator and hands it to {@link ArchiveAdminService}, which
 * checks the role and writes the audit row in the same transaction. Putting
 * the check there rather than here means a new endpoint added later cannot
 * forget it.
 *
 * <p><strong>The whole tree 404s while enforcement is off.</strong> With
 * {@code app.authorization.enforcement-enabled=false} - the default, and the
 * current state everywhere - no grant is read by anything, so an
 * administrative API that appeared to work would be misleading: it would
 * accept changes that have no effect. It therefore does not exist until
 * enforcement is on.
 *
 * <p>Bootstrap of the first administrator is deliberately absent. It cannot be
 * performed by an existing administrator, so exposing it over HTTP would be a
 * route around the entire model; it stays an operator-run procedure
 * (approved decision D-ID-2).
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminAuthorizationController {

    private final ArchiveAdminService admin;
    private final CurrentIdentityProvider identities;
    private final IdentityResolver resolver;
    private final AuthorizationProperties properties;
    private final Clock clock;

    @Autowired
    public AdminAuthorizationController(ArchiveAdminService admin, CurrentIdentityProvider identities,
                                        IdentityResolver resolver, AuthorizationProperties properties) {
        this(admin, identities, resolver, properties, Clock.systemUTC());
    }

    /** Test seam: a fixed Clock makes grant-expiry assertions deterministic. */
    AdminAuthorizationController(ArchiveAdminService admin, CurrentIdentityProvider identities,
                                 IdentityResolver resolver, AuthorizationProperties properties,
                                 Clock clock) {
        this.admin = admin;
        this.identities = identities;
        this.resolver = resolver;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Identities
    // ------------------------------------------------------------------

    @GetMapping("/identities")
    public List<AdminIdentitySearchResponse> searchIdentities(
            @RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "25") int limit) {

        return admin.searchIdentities(actor(), query, limit).stream()
                .map(AdminIdentitySearchResponse::from)
                .toList();
    }

    @GetMapping("/identities/{identifier}")
    public AdminIdentityResponse describeIdentity(@PathVariable("identifier") String identifier) {
        return AdminIdentityResponse.from(
                admin.describeIdentity(actor(), subject(identifier)), now());
    }

    @PostMapping("/identities/{identifier}/links")
    public ResponseEntity<AdminLinkResponse> link(
            @PathVariable("identifier") String identifier,
            @RequestBody AdminRequests.CreateLink request) {

        AdminLinkResponse created = AdminLinkResponse.from(admin.linkIdentity(
                actor(),
                request.cognitoIssuer(),
                request.cognitoSubject(),
                subject(identifier),
                request.kualiPersonId() == null || request.kualiPersonId().isBlank()
                        ? null : new KualiPersonId(request.kualiPersonId()),
                request.loginName(),
                request.reason()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/identities/{identifier}/links/{linkId}")
    public ResponseEntity<Void> unlink(
            @PathVariable("identifier") String identifier,
            @PathVariable("linkId") long linkId,
            @RequestParam(value = "reason", required = false) String reason) {

        admin.unlinkIdentity(actor(), linkId, reason);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/identities/{identifier}/suspension")
    public ResponseEntity<Void> setSuspension(
            @PathVariable("identifier") String identifier,
            @RequestBody AdminRequests.Suspension request) {

        admin.setSuspended(actor(), subject(identifier), request.suspended(), request.reason());
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // Grants
    // ------------------------------------------------------------------

    @PostMapping("/identities/{identifier}/grants")
    public ResponseEntity<AdminGrantResponse> grant(
            @PathVariable("identifier") String identifier,
            @RequestBody AdminRequests.CreateGrant request) {

        GrantSpecification specification = new GrantSpecification(
                grantType(request.type()),
                request.unitNumber(),
                request.includeDescendants(),
                request.ioValue(),
                request.expiresAt(),
                request.reason());

        AdminGrantResponse created = AdminGrantResponse.from(
                admin.grantAccess(actor(), subject(identifier), specification), now());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/identities/{identifier}/grants/{grantId}")
    public AdminGrantResponse modifyGrant(
            @PathVariable("identifier") String identifier,
            @PathVariable("grantId") long grantId,
            @RequestBody AdminRequests.ModifyGrant request) {

        return AdminGrantResponse.from(
                admin.modifyAccess(actor(), grantId, request.expiresAt(),
                        request.includeDescendants(), request.reason()),
                now());
    }

    @DeleteMapping("/identities/{identifier}/grants/{grantId}")
    public ResponseEntity<Void> revokeGrant(
            @PathVariable("identifier") String identifier,
            @PathVariable("grantId") long grantId,
            @RequestParam(value = "reason", required = false) String reason) {

        admin.revokeAccess(actor(), grantId, reason);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // Audit and administrators
    // ------------------------------------------------------------------

    @GetMapping("/audit")
    public List<AdminAuditEntryResponse> audit(
            @RequestParam(value = "identity", required = false) String identity,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "from", required = false) Instant from,
            @RequestParam(value = "to", required = false) Instant to,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {

        AuditQuery query = new AuditQuery(
                blankToNull(identity), auditAction(action), from, to, limit);
        return admin.auditHistory(actor(), query).stream()
                .map(AdminAuditEntryResponse::from)
                .toList();
    }

    @GetMapping("/administrators")
    public List<AdminRoleMemberResponse> administrators() {
        return admin.listAdministrators(actor()).stream()
                .map(AdminRoleMemberResponse::from)
                .toList();
    }

    @PostMapping("/administrators")
    public ResponseEntity<AdminRoleMemberResponse> assignAdministrator(
            @RequestBody AdminRequests.AssignAdministrator request) {

        AdminRoleMemberResponse assigned = AdminRoleMemberResponse.from(admin.assignAdministrator(
                actor(), subject(request.institutionalIdentifier()), request.reason()));
        return ResponseEntity.status(HttpStatus.CREATED).body(assigned);
    }

    @DeleteMapping("/administrators/{identifier}")
    public ResponseEntity<Void> revokeAdministrator(
            @PathVariable("identifier") String identifier,
            @RequestParam(value = "reason", required = false) String reason) {

        admin.revokeAdministrator(actor(), subject(identifier), reason);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------

    /**
     * The acting administrator, from the validated token only.
     *
     * <p>Anything short of a fully resolved identity is treated the same as
     * not being an administrator. That is deliberate: a revoked, suspended or
     * ambiguous identity must not be able to administer access, and
     * distinguishing those cases here would tell a caller why they failed.
     */
    private InstitutionalIdentifier actor() {
        requireEnforcementEnabled();
        return identities.current()
                .map(resolver::resolve)
                .filter(IdentityResolution.Mapped.class::isInstance)
                .map(IdentityResolution.Mapped.class::cast)
                .map(IdentityResolution.Mapped::institutionalIdentifier)
                .orElseThrow(() -> new NotAnArchiveAdministratorException(
                        "no resolved institutional identity for this request"));
    }

    private void requireEnforcementEnabled() {
        if (!properties.isEnforcementEnabled()) {
            // 404, not 403: while enforcement is off the surface genuinely
            // does not exist, and changes made through it would have no effect.
            throw new NoSuchElementException("administrative API is not available");
        }
    }

    private Instant now() {
        return clock.instant();
    }

    private static InstitutionalIdentifier subject(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("institutional identifier is required");
        }
        return new InstitutionalIdentifier(identifier.trim());
    }

    private static GrantType grantType(String value) {
        try {
            return GrantType.valueOf(value == null ? "" : value.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(
                    "unknown grant type '" + value + "'; expected one of CENTRAL, UNIT, IO, "
                            + "CONTACT_DERIVATION (IO is shown as 'SAP Account')");
        }
    }

    private static AdminAuditAction auditAction(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return AdminAuditAction.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("unknown audit action '" + value + "'");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
