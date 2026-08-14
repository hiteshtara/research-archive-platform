package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.attachment.AttachmentSearchResultResponse;
import edu.bu.archive.application.service.AttachmentSearchService;

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
 * Archived File Finder. Phase 1 was Award-only; Phase 2 adds Proposal
 * support and a recordType=ALL cross-domain search behind the same
 * route and the same query-parameter shape - deliberately a separate
 * top-level path from both /api/v1/awards and /api/documents/
 * /api/v1/documents (Kuali Documents/Document Explorer - those search
 * business RECORDS by their own workflow document number; this finds
 * archived FILES - see DocumentSearchRepository's own header comment
 * for the deliberate "attachments are child artifacts, not independent
 * Kuali documents" boundary this endpoint intentionally sits on the
 * other side of).
 *
 * recordType defaults to AWARD when omitted - every Phase 1 URL never
 * set it, so every existing bookmarked search keeps returning Award-only
 * results exactly as before. awardNumber/awardId are still accepted as
 * temporary aliases for the new canonical recordNumber/recordId names -
 * see AttachmentSearchService's own header comment for the precedence
 * rule (canonical wins when both are supplied).
 *
 * All dispatch/validation logic now lives in AttachmentSearchService,
 * a genuinely cross-domain service - this controller only parses HTTP
 * parameters and delegates. Same global Cognito auth every /api/**
 * route uses (SecurityConfiguration) - no extra wiring, no feature
 * flag, no narrower or wider authorization than any other endpoint.
 *
 * Download itself is NOT implemented here - the response never
 * contains a download URL of any kind. A client downloads a result by
 * calling the existing per-domain authenticated proxy-stream endpoint
 * directly (AwardV1Controller.downloadAttachment for recordType=AWARD,
 * ProposalV1Controller.downloadAttachment for recordType=PROPOSAL)
 * using the result's own parentId/attachmentId - no new download
 * mechanism, no presigned URL, no unified download endpoint.
 */
@RestController
@RequestMapping("/api/v1/attachments")
@Validated
@Tag(
        name = "Archived File Finder",
        description = "Read-only, exact-identifier search across "
                + "archived Award and Proposal attachment files. "
                + "Distinct from Kuali Documents/Document Explorer, "
                + "which search business records, not files."
)
public class AttachmentSearchController {

    private final AttachmentSearchService service;

    public AttachmentSearchController(AttachmentSearchService service) {
        this.service = service;
    }

    @Operation(
            summary = "Search archived Award and/or Proposal attachment files",
            description = "recordType selects the domain: AWARD (default "
                    + "when omitted, for backward compatibility with "
                    + "Phase 1), PROPOSAL, or ALL (a single database-level "
                    + "union across both, restricted to recordNumber/"
                    + "documentNumber/versionFilter - recordId/"
                    + "attachmentId/fileId are ambiguous across domains "
                    + "and rejected with ALL). At least one identifier is "
                    + "required - an all-blank request is rejected with "
                    + "400, never treated as \"match everything\". Every "
                    + "supplied filter is an exact match, combined with "
                    + "AND. One result row per authoritative attachment "
                    + "relationship."
    )
    @ApiResponse(responseCode = "200", description = "A page of matching archived files.")
    @ApiResponse(responseCode = "400", description = "No identifier supplied, an identifier is not a valid whole number, an identifier is ambiguous for the given recordType, or page/size out of range.")
    @GetMapping("/search")
    public ResponseEntity<PageResponse<AttachmentSearchResultResponse>> search(
            @Parameter(description = "ALL, AWARD, or PROPOSAL. Defaults to AWARD when omitted.")
            @RequestParam(required = false)
            String recordType,

            @Parameter(description = "Exact Award number or Proposal number, per recordType. Canonical name; awardNumber is a temporary alias.")
            @RequestParam(required = false)
            String recordNumber,

            @Parameter(description = "Deprecated alias for recordNumber - kept for backward compatibility with Phase 1 URLs.")
            @RequestParam(required = false)
            String awardNumber,

            @Parameter(description = "Exact workflow document number match (Award's own KEW number, or Proposal's own document_number).")
            @RequestParam(required = false)
            String documentNumber,

            @Parameter(description = "Exact Award ID or Proposal ID, per recordType. Canonical name; awardId is a temporary alias.")
            @RequestParam(required = false)
            String recordId,

            @Parameter(description = "Deprecated alias for recordId - kept for backward compatibility with Phase 1 URLs.")
            @RequestParam(required = false)
            String awardId,

            @Parameter(description = "Exact attachment relationship ID match. Requires an explicit (or defaulted) recordType of AWARD or PROPOSAL - rejected with recordType=ALL.")
            @RequestParam(required = false)
            String attachmentId,

            @Parameter(description = "Exact file_id match. Award-specific - rejected for recordType=PROPOSAL or ALL.")
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
                        recordType, recordNumber, awardNumber, documentNumber,
                        recordId, awardId, attachmentId, fileId, versionFilter,
                        page, size
                )
        );
    }
}
