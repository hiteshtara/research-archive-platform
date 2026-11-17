package edu.bu.archive.application.security;

import edu.bu.archive.application.authorization.RecordFactsRepository;
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
            org.springframework.beans.factory.ObjectProvider<ArchiveActivityRecorder> activity,
            org.springframework.beans.factory.ObjectProvider<RecordFactsRepository> facts) {
        this.activity = activity.getIfAvailable(
                () -> ArchiveActivityRecorder.disabled(Clock.systemUTC()));
        this.facts = facts;
    }

    /**
     * Resolves a download row's business key, lazily and only while
     * collection is on; absent in MVC slices.
     */
    private org.springframework.beans.factory.ObjectProvider<RecordFactsRepository> facts;

    /**
     * Records that ONE attachment of ONE record is being released
     * (US-SEC-004.2), called by the four download endpoints AFTER the archive
     * service has resolved the download and BEFORE the response body exists.
     *
     * <p>Why after the service, not before it. By the time this runs the
     * service has: decided the record (recording that view or refusal),
     * confirmed the attachment id belongs to THAT record, confirmed the
     * archived object exists and opened it. So a PERMITTED release row is
     * written only for a release that is actually about to happen - never for
     * a refused record, a foreign attachment id, a missing attachment or a
     * missing object, each of which throws out of the service first and
     * leaves no download row. (Writing it first, as before 2026-11-17, left a
     * false "download" row for every one of those.)
     *
     * <p>FAIL_CLOSED is unchanged: the response body that streams the bytes
     * has not been created yet. If the audit write fails this closes the
     * opened object and rethrows {@code AuditUnavailableException}, so not a
     * byte reaches the caller.
     *
     * <p>The row carries the record's BUSINESS key as {@code resource_key} -
     * the same key a view of that record carries - plus {@code recordId} and
     * {@code attachmentId} in {@code detail}. Ids only: never a file name,
     * description or content.
     *
     * @param resourceType AWARD, PROPOSAL, SUBAWARD or NEGOTIATION
     * @param recordId     the archive row id in the download path
     * @param attachmentId the attachment id in the download path
     * @param opened       the already-opened object, closed if the audit refuses
     */
    public void recordAttachmentDownload(String resourceType, long recordId, long attachmentId,
                                         java.io.Closeable opened) {
        if (!activity.enabled()) {
            return;
        }
        try {
            activity.attachmentDownloaded(resourceType, businessKey(resourceType, recordId),
                    null, recordId, attachmentId);
        } catch (RuntimeException refused) {
            try {
                if (opened != null) {
                    opened.close();
                }
            } catch (java.io.IOException | RuntimeException closeFailure) {
                refused.addSuppressed(closeFailure);
            }
            throw refused;
        }
    }

    private String businessKey(String resourceType, long recordId) {
        RecordFactsRepository repository = facts == null ? null : facts.getIfAvailable();
        if (repository == null) {
            return null;
        }
        try {
            return switch (resourceType) {
                case "AWARD" -> repository.awardNumberForId(recordId).orElse(null);
                case "PROPOSAL" -> repository.proposalNumberForId(recordId).orElse(null);
                case "SUBAWARD" -> repository.subawardCodeForId(recordId).orElse(null);
                case "NEGOTIATION" -> String.valueOf(recordId);
                default -> null;
            };
        } catch (RuntimeException lookupFailed) {
            // The key is a label; detail.recordId still identifies the record.
            return null;
        }
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
