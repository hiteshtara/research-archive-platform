package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardAmountHistoryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCentralAdministrationContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCommentsResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardHierarchyResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardPersonDetailResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSapTransmissionResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSponsorContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardTermsResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardUnitContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardUnitDetailsResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardVersionSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyActionResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyDocumentResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyHistoryEntryResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneySummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyTransactionResponse;
import edu.bu.archive.application.award.AwardArchiveService;
import edu.bu.archive.application.award.AwardAttachmentDownload;
import edu.bu.archive.application.award.AwardContactService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * Versioned Award API - stable, independent of JPA entities, archive
 * table layout, and any single frontend's presentation needs. This is
 * the intended long-term home for every new Award endpoint; the older
 * unversioned AwardArchiveController (/api/awards/...) exists only
 * because the current React UI already calls it directly - see
 * docs/architecture/AWARD_SEARCH_API_DESIGN.md's "API versioning"
 * section.
 *
 * awardId (the archive.award_version surrogate primary key) is exposed
 * in /{awardId}/summary and /{awardId}/versions because a specific
 * Award *version* - not just the award_number family - must be
 * addressable; it is safe to expose as a stable public identifier only
 * because this archive is read-only and never renumbers or reuses
 * surrogate keys. See the design doc's "Internal identifiers" section.
 */
@RestController
@RequestMapping("/api/v1/awards")
@Validated
@Tag(
        name = "Awards",
        description = "Read-only search, hierarchy, summary, and "
                + "version history for archived Kuali Awards."
)
public class AwardV1Controller {

    private final AwardArchiveService service;
    private final AwardContactService contactService;

    public AwardV1Controller(
            AwardArchiveService service,
            AwardContactService contactService
    ) {
        this.service = service;
        this.contactService = contactService;
    }

    @Operation(
            summary = "Search Awards",
            description = "Free-text search across Award number, PI/"
                    + "person name, title, sponsor, lead unit, and "
                    + "document number. Supports exact match, "
                    + "substring match (the default), and the "
                    + "application's own *text* wildcard syntax. "
                    + "Results are always ordered by award_number "
                    + "ascending (unique per is_primary_current row - "
                    + "a stable, deterministic sort with no explicit "
                    + "sort parameter needed yet)."
    )
    @ApiResponse(responseCode = "200", description = "A page of matching Awards, plus an exact workflow document-number match if the query matched one.")
    @ApiResponse(responseCode = "400", description = "page/size out of range.")
    @GetMapping("/search")
    public ResponseEntity<AwardSearchResponse> search(
            @Parameter(description = "Free-text query. Supports "
                    + "*wildcard* syntax. Omit or leave blank to list "
                    + "all current Awards, paginated.")
            @RequestParam(name = "q", required = false)
            String q,

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
                service.search(q, page, size)
        );
    }

    @Operation(
            summary = "Get an Award's hierarchy",
            description = "Returns the full recursive Award family "
                    + "tree rooted at archive.award_hierarchy's "
                    + "root_award_number, resolved from any award "
                    + "number in the family (root, mid-tree, or "
                    + "leaf). An Award with no recorded hierarchy "
                    + "relationship returns a single-node tree, not "
                    + "a 404 - only a nonexistent award_number 404s."
    )
    @ApiResponse(responseCode = "200", description = "The resolved hierarchy tree.")
    @ApiResponse(responseCode = "404", description = "No such award_number.")
    @GetMapping("/{awardNumber}/hierarchy")
    public ResponseEntity<AwardHierarchyResponse> hierarchy(
            @Parameter(description = "Any award_number in the family.")
            @PathVariable
            String awardNumber
    ) {
        return ResponseEntity.ok(
                service.findHierarchy(awardNumber)
        );
    }

    @Operation(
            summary = "Get a compact Award summary",
            description = "Keyed by the surrogate award_id (one "
                    + "specific version), not award_number. Excludes "
                    + "comments, Budget, Time and Money, SAP "
                    + "transmission history, and attachments - those "
                    + "are separate composable resources planned for "
                    + "a later phase, not folded into this endpoint."
    )
    @ApiResponse(responseCode = "200", description = "The Award summary.")
    @ApiResponse(responseCode = "404", description = "No such award_id.")
    @GetMapping("/{awardId}/summary")
    public ResponseEntity<AwardSummaryResponse> summary(
            @Parameter(description = "The archive.award_version "
                    + "surrogate primary key for one specific "
                    + "version.")
            @PathVariable
            long awardId
    ) {
        return ResponseEntity.ok(
                service.findSummary(awardId)
        );
    }

    @Operation(
            summary = "List an Award's versions",
            description = "Resolves awardId to its award_number "
                    + "family, then returns every version row for "
                    + "that exact award_number (not sibling "
                    + "award_numbers), ordered by sequence_number "
                    + "descending (newest first), with "
                    + "source_update_timestamp then award_id as "
                    + "deterministic tiebreakers."
    )
    @ApiResponse(responseCode = "200", description = "A page of the Award's versions.")
    @ApiResponse(responseCode = "404", description = "No such award_id.")
    @GetMapping("/{awardId}/versions")
    public ResponseEntity<PageResponse<AwardVersionSummaryResponse>> versions(
            @Parameter(description = "Any award_id belonging to the "
                    + "family whose versions should be listed.")
            @PathVariable
            long awardId,

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
        return ResponseEntity.ok(
                service.findVersions(awardId, page, size)
        );
    }

    @Operation(
            summary = "List an Award's People and Units",
            description = "Keyed by the surrogate award_id (one "
                    + "specific version). Nests each person's "
                    + "associated units and credit-split rows rather "
                    + "than returning raw child-table rows "
                    + "separately."
    )
    @ApiResponse(responseCode = "200", description = "The Award's people.")
    @ApiResponse(responseCode = "404", description = "No such award_id.")
    @GetMapping("/{awardId}/people")
    public ResponseEntity<List<AwardPersonDetailResponse>> people(
            @PathVariable
            long awardId
    ) {
        return ResponseEntity.ok(service.findPeople(awardId));
    }

    @Operation(
            summary = "Get an Award's Unit Details",
            description = "The Award's lead unit (archive.unit, the "
                    + "shared reference table - never a second, "
                    + "Award-owned copy of Unit data), including its "
                    + "parent unit name when archived."
    )
    @ApiResponse(responseCode = "200", description = "The Award's lead unit details.")
    @ApiResponse(responseCode = "404", description = "No such award_id, or no lead unit archived for it.")
    @GetMapping("/{awardId}/unit-details")
    public ResponseEntity<AwardUnitDetailsResponse> unitDetails(
            @PathVariable
            long awardId
    ) {
        return ResponseEntity.ok(contactService.findUnitDetails(awardId));
    }

    @Operation(
            summary = "List an Award's Unit Contacts",
            description = "Real, Award-specific archived data "
                    + "(archive.award_unit_contact) - not derived, "
                    + "unlike Central Administration Contacts."
    )
    @ApiResponse(responseCode = "200", description = "The Award's Unit Contacts.")
    @ApiResponse(responseCode = "404", description = "No such award_id.")
    @GetMapping("/{awardId}/unit-contacts")
    public ResponseEntity<List<AwardUnitContactResponse>> unitContacts(
            @PathVariable
            long awardId
    ) {
        return ResponseEntity.ok(contactService.findUnitContacts(awardId));
    }

    @Operation(
            summary = "List an Award's Sponsor Contacts",
            description = "Resolves archive.award_sponsor_contact "
                    + "against the shared archive.rolodex table for "
                    + "organization/phone/email."
    )
    @ApiResponse(responseCode = "200", description = "The Award's Sponsor Contacts.")
    @ApiResponse(responseCode = "404", description = "No such award_id.")
    @GetMapping("/{awardId}/sponsor-contacts")
    public ResponseEntity<List<AwardSponsorContactResponse>> sponsorContacts(
            @PathVariable
            long awardId
    ) {
        return ResponseEntity.ok(contactService.findSponsorContacts(awardId));
    }

    @Operation(
            summary = "List an Award's Central Administration Contacts",
            description = "DERIVED, never persisted as its own table - "
                    + "reproduces Kuali's Award.initCentralAdminContacts() "
                    + "exactly: the Award's lead unit's administrators "
                    + "(archive.unit_administrator joined to "
                    + "archive.unit_administrator_type), filtered to "
                    + "default_group_flag = 'C'."
    )
    @ApiResponse(responseCode = "200", description = "The Award's Central Administration Contacts.")
    @ApiResponse(responseCode = "404", description = "No such award_id.")
    @GetMapping("/{awardId}/central-administration-contacts")
    public ResponseEntity<List<AwardCentralAdministrationContactResponse>>
            centralAdministrationContacts(
                    @PathVariable
                    long awardId
            ) {
        return ResponseEntity.ok(
                contactService.findCentralAdministrationContacts(awardId)
        );
    }

    @Operation(
            summary = "List an Award's amount history",
            description = "Resolves awardId to its award_number "
                    + "family, then returns every award_amount_info "
                    + "row for that family, newest version first, "
                    + "each joined back to its own award_version row "
                    + "for effective-date context."
    )
    @ApiResponse(responseCode = "200", description = "A page of amount history.")
    @ApiResponse(responseCode = "404", description = "No such award_id.")
    @GetMapping("/{awardId}/amounts")
    public ResponseEntity<PageResponse<AwardAmountHistoryResponse>> amounts(
            @PathVariable
            long awardId,

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
        return ResponseEntity.ok(service.findAmounts(awardId, page, size));
    }

    @Operation(
            summary = "Get an Award's Time and Money summary",
            description = "Scoped to this exact award_id (the version "
                    + "being viewed), not the whole award_number "
                    + "family - the latest award_amount_info row for "
                    + "this award_id plus its most recent Time and "
                    + "Money action, if any."
    )
    @ApiResponse(responseCode = "200", description = "The Award's Time and Money summary.")
    @ApiResponse(responseCode = "404", description = "No such award_id.")
    @GetMapping("/{awardId}/time-and-money/summary")
    public ResponseEntity<TimeAndMoneySummaryResponse> timeAndMoneySummary(
            @PathVariable
            long awardId
    ) {
        return ResponseEntity.ok(service.findTimeAndMoneySummary(awardId));
    }

    @Operation(
            summary = "List an Award's Time and Money actions",
            description = "\"Action Summary\" - one row per (Time and "
                    + "Money document, affected award) pair, "
                    + "family-wide (every version of awardId's "
                    + "award_number)."
    )
    @ApiResponse(responseCode = "200", description = "A page of Time and Money actions.")
    @ApiResponse(responseCode = "404", description = "No such award_id.")
    @GetMapping("/{awardId}/time-and-money/actions")
    public ResponseEntity<PageResponse<TimeAndMoneyActionResponse>>
            timeAndMoneyActions(
                    @PathVariable
                    long awardId,

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
        return ResponseEntity.ok(
                service.findTimeAndMoneyActions(awardId, page, size)
        );
    }

    @Operation(
            summary = "List an Award's Time and Money history",
            description = "\"History Timeline\" - the "
                    + "award_amount_info append-only ledger, "
                    + "family-wide, newest version first, each row "
                    + "carrying its own sequenceNumber (the version it "
                    + "belongs to) alongside originatingAwardVersion "
                    + "(the version a Time and Money-created snapshot "
                    + "was produced against) - the two can differ."
    )
    @ApiResponse(responseCode = "200", description = "A page of Time and Money history.")
    @ApiResponse(responseCode = "404", description = "No such award_id.")
    @GetMapping("/{awardId}/time-and-money/history")
    public ResponseEntity<PageResponse<TimeAndMoneyHistoryEntryResponse>>
            timeAndMoneyHistory(
                    @PathVariable
                    long awardId,

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
        return ResponseEntity.ok(
                service.findTimeAndMoneyHistory(awardId, page, size)
        );
    }

    @Operation(
            summary = "Get one Time and Money transaction's details",
            description = "\"Transaction Details\" - looked up by "
                    + "pendingTransactionId, the numeric surrogate key "
                    + "shared by archive.pending_transaction/"
                    + "transaction_detail/award_amount_info's own "
                    + "transaction_id column (never confused with "
                    + "award_amount_transaction's differently-typed, "
                    + "differently-meaning TRANSACTION_ID). A single "
                    + "transaction can span more than one Award "
                    + "(source/destination), so details is a list."
    )
    @ApiResponse(responseCode = "200", description = "The transaction's details.")
    @ApiResponse(responseCode = "404", description = "No such award_id, or no such transaction.")
    @GetMapping("/{awardId}/time-and-money/transactions/{pendingTransactionId}")
    public ResponseEntity<TimeAndMoneyTransactionResponse>
            timeAndMoneyTransaction(
                    @PathVariable
                    long awardId,

                    @PathVariable
                    @Positive
                    long pendingTransactionId
            ) {
        return ResponseEntity.ok(
                service.findTimeAndMoneyTransaction(
                        awardId,
                        pendingTransactionId
                )
        );
    }

    @Operation(
            summary = "Get one Time and Money document's header",
            description = "\"Workflow Details\" for one Time and Money "
                    + "document - archive.time_and_money_document's own "
                    + "row (document status/creation date - there is "
                    + "no live KEW connection in this archive). This is "
                    + "a different KEW document from any Award's own "
                    + "workflowDocumentNumber - never cross-link the "
                    + "two."
    )
    @ApiResponse(responseCode = "200", description = "The Time and Money document's header.")
    @ApiResponse(responseCode = "404", description = "No such award_id, or no such document.")
    @GetMapping("/{awardId}/time-and-money/documents/{timeAndMoneyDocumentNumber}")
    public ResponseEntity<TimeAndMoneyDocumentResponse>
            timeAndMoneyDocument(
                    @PathVariable
                    long awardId,

                    @PathVariable
                    @NotBlank
                    String timeAndMoneyDocumentNumber
            ) {
        return ResponseEntity.ok(
                service.findTimeAndMoneyDocument(
                        awardId,
                        timeAndMoneyDocumentNumber
                )
        );
    }

    @Operation(
            summary = "Get an Award's Terms",
            description = "Keyed by the surrogate award_id. Returns "
                    + "sponsor terms and report terms (each report "
                    + "term nesting its own recipients) as two "
                    + "separate groups - never merged."
    )
    @ApiResponse(responseCode = "200", description = "The Award's terms.")
    @ApiResponse(responseCode = "404", description = "No such award_id.")
    @GetMapping("/{awardId}/terms")
    public ResponseEntity<AwardTermsResponse> terms(
            @PathVariable
            long awardId
    ) {
        return ResponseEntity.ok(service.findTerms(awardId));
    }

    @Operation(
            summary = "Get an Award's Comments and Notepad",
            description = "Keyed by the surrogate award_id. Comments "
                    + "are scoped to this specific version; notepad "
                    + "entries have no sequence_number and are scoped "
                    + "to the whole award_number family instead - see "
                    + "AwardCommentsResponse."
    )
    @ApiResponse(responseCode = "200", description = "The Award's comments and notepad entries.")
    @ApiResponse(responseCode = "404", description = "No such award_id.")
    @GetMapping("/{awardId}/comments")
    public ResponseEntity<AwardCommentsResponse> comments(
            @PathVariable
            long awardId
    ) {
        return ResponseEntity.ok(service.findComments(awardId));
    }

    @Operation(
            summary = "List an Award's SAP transmission history",
            description = "Keyed by the surrogate award_id - "
                    + "award_transmission.award_id is the root Award "
                    + "of the transmitted hierarchy at the moment of "
                    + "the attempt and can be reassigned by Oracle "
                    + "over time, so this reflects whatever is "
                    + "currently attributed to this award_id. "
                    + "sent_data/returned_data are raw SOAP XML, "
                    + "verbatim - a client must render them as text, "
                    + "never as HTML."
    )
    @ApiResponse(responseCode = "200", description = "A page of SAP transmission history.")
    @ApiResponse(responseCode = "404", description = "No such award_id.")
    @GetMapping("/{awardId}/sap-transmissions")
    public ResponseEntity<PageResponse<AwardSapTransmissionResponse>>
            sapTransmissions(
                    @PathVariable
                    long awardId,

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
                service.findSapTransmissions(awardId, page, size)
        );
    }

    @Operation(
            summary = "List an Award's attachment metadata",
            description = "Keyed by the surrogate award_id. Never "
                    + "exposes the underlying S3 bucket/key - "
                    + "downloadable indicates whether the download "
                    + "endpoint below will succeed. Paginated like "
                    + "every other list endpoint in this domain - "
                    + "totalElements is the authoritative count; a "
                    + "client must never treat this page's content "
                    + "length as the total."
    )
    @ApiResponse(responseCode = "200", description = "A page of the Award's attachment metadata.")
    @ApiResponse(responseCode = "404", description = "No such award_id.")
    @GetMapping("/{awardId}/attachments")
    public ResponseEntity<PageResponse<AwardAttachmentResponse>> attachments(
            @PathVariable
            long awardId,

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
        return ResponseEntity.ok(service.findAttachments(awardId, page, size));
    }

    @Operation(
            summary = "Download an Award attachment",
            description = "Streams the underlying object from private "
                    + "S3 storage (or, in local dev, a fixture on "
                    + "disk) - never redirects to a raw S3 URL and "
                    + "never exposes the bucket/key."
    )
    @ApiResponse(responseCode = "200", description = "The attachment content.")
    @ApiResponse(responseCode = "404", description = "No such award_id/attachment, or it is not downloadable.")
    @GetMapping("/{awardId}/attachments/{attachmentId}/download")
    public ResponseEntity<StreamingResponseBody> downloadAttachment(
            @PathVariable
            long awardId,

            @PathVariable
            long attachmentId
    ) {
        AwardAttachmentDownload download =
                service.downloadAttachment(awardId, attachmentId);

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
}
