package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalAttachmentsResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalCommentsResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalCustomDataResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalFundedAwardResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalPersonResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalUnitsResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalVersionSummaryResponse;
import edu.bu.archive.application.proposal.ProposalArchiveV1Service;
import edu.bu.archive.application.proposal.ProposalAttachmentDownload;
import edu.bu.archive.application.security.AttachmentAuthorizationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.List;

/*
 * Versioned Institutional Proposal API - keyed by the exact surrogate
 * proposalId (one specific version), mirroring AwardV1Controller's own
 * convention. proposalId, proposalNumber, sequenceNumber, and
 * workflowDocumentNumber are four distinct identifiers, never inferred
 * one from another. Distinct from the older, family-number-scoped
 * ProposalArchiveController (/api/proposals/{proposalNumber}), which
 * this controller does not modify or replace.
 */
@RestController
@RequestMapping("/api/v1/proposals")
@Validated
@Tag(
        name = "Proposals",
        description = "Read-only summary, version history, people, "
                + "units, attachments, comments, and funded-award "
                + "linkage for archived Institutional Proposals."
)
public class ProposalV1Controller {

    private final ProposalArchiveV1Service service;
    private final AttachmentAuthorizationService attachmentAuthorizationService;

    public ProposalV1Controller(
            ProposalArchiveV1Service service,
            AttachmentAuthorizationService attachmentAuthorizationService
    ) {
        this.service = service;
        this.attachmentAuthorizationService = attachmentAuthorizationService;
    }

    @Operation(
            summary = "Get a Proposal's summary",
            description = "Keyed by the surrogate proposalId (one "
                    + "specific version), not proposalNumber."
    )
    @ApiResponse(responseCode = "200", description = "The Proposal summary.")
    @ApiResponse(responseCode = "404", description = "No such proposal_id.")
    @GetMapping("/{proposalId}")
    public ResponseEntity<ProposalSummaryResponse> summary(
            @Parameter(description = "The archive.proposal_version "
                    + "surrogate primary key for one specific version.")
            @PathVariable
            long proposalId
    ) {
        return ResponseEntity.ok(service.findSummary(proposalId));
    }

    @Operation(
            summary = "List a Proposal's versions",
            description = "Resolves proposalId to its proposalNumber "
                    + "family, then returns every version row for that "
                    + "exact family, ordered by sequenceNumber "
                    + "descending (newest first)."
    )
    @ApiResponse(responseCode = "200", description = "A page of the Proposal's versions.")
    @ApiResponse(responseCode = "404", description = "No such proposal_id.")
    @GetMapping("/{proposalId}/versions")
    public ResponseEntity<PageResponse<ProposalVersionSummaryResponse>> versions(
            @PathVariable
            long proposalId,

            @Parameter(description = "Zero-based page index.")
            @RequestParam(defaultValue = "0")
            @Min(0)
            int page,

            @Parameter(description = "Page size, 1-100.")
            @RequestParam(defaultValue = "50")
            @Min(1)
            @Max(100)
            int size
    ) {
        return ResponseEntity.ok(service.findVersions(proposalId, page, size));
    }

    @Operation(
            summary = "List a Proposal's people",
            description = "archive.proposal_person for this exact "
                    + "proposalId - PI/MPI/COI/KP via contactRoleCode. "
                    + "Associated units live at the separate /units "
                    + "resource, never nested here."
    )
    @ApiResponse(responseCode = "200", description = "The Proposal's people.")
    @ApiResponse(responseCode = "404", description = "No such proposal_id.")
    @GetMapping("/{proposalId}/people")
    public ResponseEntity<List<ProposalPersonResponse>> people(
            @PathVariable
            long proposalId
    ) {
        return ResponseEntity.ok(service.findPeople(proposalId));
    }

    @Operation(
            summary = "Get a Proposal's Associated Units and Unit Contacts",
            description = "Two distinct lists, never merged: "
                    + "associatedUnits (archive.proposal_person_unit, "
                    + "a person's own unit(s)) and unitContacts "
                    + "(archive.proposal_unit_contact, a genuinely "
                    + "separate sibling table - live-verified as a "
                    + "different real person than the PI in the "
                    + "reference fixture)."
    )
    @ApiResponse(responseCode = "200", description = "The Proposal's units.")
    @ApiResponse(responseCode = "404", description = "No such proposal_id.")
    @GetMapping("/{proposalId}/units")
    public ResponseEntity<ProposalUnitsResponse> units(
            @PathVariable
            long proposalId
    ) {
        return ResponseEntity.ok(service.findUnits(proposalId));
    }

    @Operation(
            summary = "List a Proposal's attachment metadata, grouped by type",
            description = "Reuses the already-completed Proposal "
                    + "attachment pipeline (archive.proposal_attachment). "
                    + "Grouped by Oracle's real PROPOSAL_ATTACHMENT_TYPE "
                    + "taxonomy - never by attachment title. "
                    + "downloadable indicates whether the download "
                    + "endpoint below will succeed."
    )
    @ApiResponse(responseCode = "200", description = "The Proposal's attachments, grouped by type.")
    @ApiResponse(responseCode = "404", description = "No such proposal_id.")
    @GetMapping("/{proposalId}/attachments")
    public ResponseEntity<ProposalAttachmentsResponse> attachments(
            @PathVariable
            long proposalId,

            Authentication authentication
    ) {
        attachmentAuthorizationService.requireAttachmentAccess(authentication);
        return ResponseEntity.ok(service.findAttachments(proposalId));
    }

    @Operation(
            summary = "Download a Proposal attachment",
            description = "Streams the underlying object from private "
                    + "S3 storage (or, in local dev, a fixture on disk) "
                    + "- never redirects to a raw S3 URL and never "
                    + "exposes the bucket/key."
    )
    @ApiResponse(responseCode = "200", description = "The attachment content.")
    @ApiResponse(responseCode = "404", description = "No such proposal_id/attachment, or it is not downloadable.")
    @GetMapping("/{proposalId}/attachments/{attachmentId}/download")
    public ResponseEntity<StreamingResponseBody> downloadAttachment(
            @PathVariable
            long proposalId,

            @PathVariable
            long attachmentId,

            Authentication authentication
    ) {
        attachmentAuthorizationService.requireAttachmentAccess(authentication);
        ProposalAttachmentDownload download =
                service.downloadAttachment(proposalId, attachmentId);

        StreamingResponseBody body = output -> {
            try (var input = download.stream()) {
                input.transferTo(output);
            }
        };

        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(download.mimeType());
        } catch (Exception ignored) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.fileName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(download.contentLength())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        disposition.toString()
                )
                .body(body);
    }

    @Operation(
            summary = "Get a Proposal's Comments",
            description = "Keyed by the surrogate proposalId, resolved "
                    + "to its proposalNumber family. Reuses the shared "
                    + "archive.comment_type table - shows only "
                    + "\"Proposal Comments\" and \"Proposal IP Review "
                    + "Comments\" (codes 12/13), family-wide, with "
                    + "history behavior matching Award's own Comments "
                    + "screen (consecutive identical text collapsed to "
                    + "its earliest occurrence)."
    )
    @ApiResponse(responseCode = "200", description = "The Proposal's comments.")
    @ApiResponse(responseCode = "404", description = "No such proposal_id.")
    @GetMapping("/{proposalId}/comments")
    public ResponseEntity<ProposalCommentsResponse> comments(
            @PathVariable
            long proposalId
    ) {
        return ResponseEntity.ok(service.findComments(proposalId));
    }

    @Operation(
            summary = "List a Proposal's Funded Awards",
            description = "Family-wide (every version of this "
                    + "proposalNumber), resolved through "
                    + "archive.proposal_award to each linked Award's "
                    + "current version. Never exposes an internal "
                    + "awardId - a client resolves it separately, only "
                    + "at click-time, via GET "
                    + "/api/v1/awards/by-number/{awardNumber}."
    )
    @ApiResponse(responseCode = "200", description = "The Proposal's funded Awards.")
    @ApiResponse(responseCode = "404", description = "No such proposal_id.")
    @GetMapping("/{proposalId}/funded-awards")
    public ResponseEntity<List<ProposalFundedAwardResponse>> fundedAwards(
            @PathVariable
            long proposalId
    ) {
        return ResponseEntity.ok(service.findFundedAwards(proposalId));
    }

    @Operation(
            summary = "List a Proposal's Custom Data",
            description = "archive.proposal_custom_data for this exact "
                    + "proposalId - version-scoped, never combined with "
                    + "a sibling version's rows. label/name/dataType "
                    + "resolve via the shared archive.custom_attribute "
                    + "lookup and are null when Oracle has since added "
                    + "an attribute this archive hasn't loaded yet."
    )
    @ApiResponse(responseCode = "200", description = "The Proposal's custom data.")
    @ApiResponse(responseCode = "404", description = "No such proposal_id.")
    @GetMapping("/{proposalId}/custom-data")
    public ResponseEntity<List<ProposalCustomDataResponse>> customData(
            @PathVariable
            long proposalId
    ) {
        return ResponseEntity.ok(service.findCustomData(proposalId));
    }
}
