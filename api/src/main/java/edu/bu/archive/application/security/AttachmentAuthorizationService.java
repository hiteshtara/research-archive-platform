package edu.bu.archive.application.security;

import edu.bu.archive.application.authorization.audit.ArchiveActivityRecorder;
import edu.bu.archive.exception.AttachmentAccessDeniedException;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/*
 * Single authorization point for every attachment-related endpoint in
 * this API: Award/Proposal/Subaward/Negotiation attachment listing and
 * download, and the cross-domain Archived File Finder search
 * (AttachmentSearchController). See docs/architecture/
 * NEGOTIATION_ATTACHMENT_ACCESS_DESIGN.md for the product decision this
 * implements.
 *
 * Cognito authentication (401 for an anonymous request) is already
 * enforced globally by SecurityConfiguration for every /api/** route,
 * before Spring MVC dispatch even begins - this service adds the
 * second, narrower gate on top of that: authenticated but not a member
 * of the approved group -> 403, scoped only to attachment endpoints
 * (every other endpoint keeps its existing plain-authenticated rule
 * unchanged).
 *
 * The ArchiveAttachmentViewer -> ROLE_ArchiveAttachmentViewer mapping
 * itself lives in SecurityConfiguration.jwtAuthenticationConverter()
 * (the existing, already-shipped cognito:groups -> ROLE_<group>
 * conversion - a missing/malformed cognito:groups claim already maps to
 * no group authorities there, so this service denies by default in
 * that case too, with no special-casing needed here). This service only
 * makes the authorization *decision* from the authorities Spring
 * Security already produced; it never re-parses the raw JWT.
 *
 * Every attachment controller method must call requireAttachmentAccess
 * as its first line, before any repository/service call runs, so no
 * attachment metadata (filenames, descriptions, IDs, counts,
 * availability) is ever computed for an unauthorized caller - and
 * download endpoints must call it too, independently, on every request
 * (never cached/short-circuited from an earlier list call).
 */
@Service
public class AttachmentAuthorizationService {

    public static final String ATTACHMENT_VIEWER_AUTHORITY =
            "ROLE_ArchiveAttachmentViewer";

    private final ArchiveActivityRecorder activity;

    /** Collection off: the default, and what every existing test gets. */
    public AttachmentAuthorizationService() {
        this.activity = ArchiveActivityRecorder.disabled(Clock.systemUTC());
    }

    /** Direct injection, for tests that want to observe what was recorded. */
    public AttachmentAuthorizationService(ArchiveActivityRecorder activity) {
        this.activity = activity;
    }

    /**
     * The Spring constructor.
     *
     * <p>{@link org.springframework.beans.factory.ObjectProvider}, not a plain
     * dependency, because this is a {@code @Service} and therefore lives in
     * every {@code @WebMvcTest} slice - which has no recorder bean. A hard
     * dependency here fails twelve controller slices at context load with an
     * error about auditing, which tells nobody anything about the controller
     * they were testing. Absent recorder means auditing off, which is also
     * the production default.
     */
    @Autowired
    public AttachmentAuthorizationService(
            org.springframework.beans.factory.ObjectProvider<ArchiveActivityRecorder> activity) {
        this.activity = activity.getIfAvailable(
                () -> ArchiveActivityRecorder.disabled(Clock.systemUTC()));
    }

    /**
     * The gate for a DOWNLOAD, as opposed to a listing.
     *
     * <p>SEC-008: the audit row is written HERE, before the caller's
     * attachment is resolved and long before a byte is streamed. Attachment
     * downloads are FAIL_CLOSED when collection is enabled, so a failed audit
     * write throws out of this method and the file is never released. That
     * ordering is the requirement - "verify the required audit write succeeds
     * before releasing the file" - and it is why this is a separate method
     * rather than a flag on the listing gate: a listing must not be recorded
     * as a download, and a download must not be served on a listing's weaker
     * guarantee.
     *
     * <p>Authorization is checked FIRST. A caller who is refused never
     * produces an ATTACHMENT_DOWNLOADED row, because nothing was downloaded;
     * the refusal is recorded by the record-level path that denied it.
     */
    public void requireAttachmentDownload(Authentication authentication,
                                          String resourceType, String resourceKey) {
        requireAttachmentAccess(authentication);
        activity.attachmentDownloaded(resourceType, resourceKey, null);
    }

    /**
     * The gate for generating an EXPORT (a report PDF). Same ordering and the
     * same FAIL_CLOSED tier as a download: a report is a file leaving the
     * system.
     */
    public void requireExport(Authentication authentication,
                              String resourceType, String resourceKey) {
        requireAttachmentAccess(authentication);
        activity.exportGenerated(resourceType, resourceKey, null);
    }

    /** An export with no attachment gate of its own (the plain report PDF). */
    public void recordExport(String resourceType, String resourceKey) {
        activity.exportGenerated(resourceType, resourceKey, null);
    }

    public void requireAttachmentAccess(Authentication authentication) {
        boolean authorized = authentication != null
                && authentication.getAuthorities().stream()
                        .anyMatch(authority ->
                                ATTACHMENT_VIEWER_AUTHORITY.equals(
                                        authority.getAuthority()
                                )
                        );

        if (!authorized) {
            throw new AttachmentAccessDeniedException(
                    "Attachment access requires membership in the "
                            + "ArchiveAttachmentViewer group"
            );
        }
    }
}
