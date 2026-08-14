package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.attachment.AttachmentSearchResultResponse;
import edu.bu.archive.application.award.AwardArchiveService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/*
 * Archived File Finder (Phase 1: Award only, exact-identifier only).
 * Deliberately a separate top-level path from both /api/v1/awards (this
 * is cross-Award, not scoped to one award_id) and /api/documents /
 * /api/v1/documents (Kuali Documents/Document Explorer - those search
 * business RECORDS by their own workflow document number; this finds
 * archived FILES - see DocumentSearchRepository's own header comment
 * for the deliberate "attachments are child artifacts, not independent
 * Kuali documents" boundary this endpoint intentionally sits on the
 * other side of).
 *
 * Reuses AwardArchiveService directly rather than a new service class -
 * Phase 1 has no cross-domain logic to justify one yet; a later Phase 2
 * (Proposal) adding a second domain's results to this same endpoint is
 * the natural trigger to introduce a shared facade, not before.
 *
 * Same global Cognito auth every /api/** route uses
 * (SecurityConfiguration) - no extra wiring, no feature flag. This
 * inherits the existing flat "any authenticated archive-staff user may
 * call every /api/** endpoint" model exactly as-is; it does not
 * introduce, narrow, or widen authorization. Designing per-researcher/
 * per-PI access is explicitly out of scope for Phase 1 (see the design
 * investigation this endpoint implements).
 *
 * Download itself is NOT implemented here - the response never
 * contains a download URL of any kind (no presigned URL exists
 * anywhere in this codebase - see AwardV1Controller.downloadAttachment,
 * a real authenticated server-side S3 proxy stream). A client
 * downloads a result by calling that existing endpoint directly with
 * the result's own awardId/attachmentId - no new download mechanism.
 */
@RestController
@RequestMapping("/api/v1/attachments")
@Validated
@Tag(
        name = "Archived File Finder",
        description = "Read-only, exact-identifier search across "
                + "archived Award attachment files. Phase 1: Award "
                + "only. Distinct from Kuali Documents/Document "
                + "Explorer, which search business records, not files."
)
public class AttachmentSearchController {

    private final AwardArchiveService service;

    public AttachmentSearchController(AwardArchiveService service) {
        this.service = service;
    }

    @Operation(
            summary = "Search archived Award attachment files",
            description = "At least one of awardNumber, documentNumber, "
                    + "awardId, attachmentId, or fileId is required - "
                    + "an all-blank request is rejected with 400, never "
                    + "treated as \"match everything\". Every supplied "
                    + "filter is an exact match, combined with AND; "
                    + "there is no free-text/general-query parameter in "
                    + "Phase 1. One result row per authoritative "
                    + "award_attachment relationship - a physical file "
                    + "legitimately shared across multiple Award "
                    + "versions produces one row per version, never "
                    + "collapsed."
    )
    @ApiResponse(responseCode = "200", description = "A page of matching archived files.")
    @ApiResponse(responseCode = "400", description = "No identifier supplied, an identifier is not a valid whole number, or page/size out of range.")
    @GetMapping("/search")
    public ResponseEntity<PageResponse<AttachmentSearchResultResponse>> search(
            @Parameter(description = "Exact award_number match.")
            @RequestParam(required = false)
            String awardNumber,

            @Parameter(description = "Exact Award workflow document_number match.")
            @RequestParam(required = false)
            String documentNumber,

            @Parameter(description = "Exact award_id match (a specific "
                    + "Award version's surrogate key).")
            @RequestParam(required = false)
            String awardId,

            @Parameter(description = "Exact award_attachment_id match "
                    + "(one specific attachment reference row).")
            @RequestParam(required = false)
            String attachmentId,

            @Parameter(description = "Exact file_id match (the "
                    + "physical file - may be shared by more than one "
                    + "attachment reference).")
            @RequestParam(required = false)
            String fileId,

            @Parameter(description = "\"all\" (default), \"current\", or \"historical\".")
            @RequestParam(defaultValue = "all")
            String versionFilter,

            @Parameter(description = "Zero-based page index.")
            @RequestParam(defaultValue = "0")
            @Min(0)
            int page,

            @Parameter(description = "Page size, 1-100.")
            @RequestParam(defaultValue = "25")
            @Min(1)
            @Max(100)
            int size
    ) {
        return ResponseEntity.ok(
                service.searchAttachments(
                        awardNumber, documentNumber, awardId, attachmentId,
                        fileId, versionFilter, page, size
                )
        );
    }
}
