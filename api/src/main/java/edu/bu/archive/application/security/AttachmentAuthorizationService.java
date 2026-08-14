package edu.bu.archive.application.security;

import edu.bu.archive.exception.AttachmentAccessDeniedException;

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
