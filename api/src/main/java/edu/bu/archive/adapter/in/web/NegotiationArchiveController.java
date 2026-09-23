package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationActivityResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationAssociatedRecordResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationCustomDataResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationNotificationResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationSummaryResponse;
import edu.bu.archive.application.negotiation.NegotiationSearchFilters;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationUnassociatedDetailResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationWorkspaceResponse;
import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.application.negotiation.NegotiationArchiveService;
import edu.bu.archive.application.negotiation.NegotiationAttachmentDownload;
import edu.bu.archive.application.security.AttachmentAuthorizationService;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/negotiations")
public class NegotiationArchiveController {

    private final NegotiationArchiveService service;
    private final AttachmentAuthorizationService attachmentAuthorizationService;

    public NegotiationArchiveController(
            NegotiationArchiveService service,
            AttachmentAuthorizationService attachmentAuthorizationService
    ) {
        this.service = service;
        this.attachmentAuthorizationService = attachmentAuthorizationService;
    }

    /*
     * Free text plus optional structured filters. Every parameter is
     * optional and an omitted one imposes no condition; supplied ones
     * combine with AND. Filtering is done in PostgreSQL, never in the
     * UI, so page/size and totalElements stay consistent with what is
     * actually shown.
     *
     * Anticipated Award Date, Sponsor Award ID, Prime Sponsor and
     * Principal Investigator (Non-BU) deliberately have no filter here:
     * their verified source population (7, 1, 36 and 25 rows
     * respectively) is too sparse to justify one. They remain
     * display-only where verified.
     */
    @GetMapping
    public ResponseEntity<PageResponse<NegotiationSummaryResponse>> search(
            @RequestParam(required = false)
            String query,

            @RequestParam(required = false)
            String status,

            @RequestParam(required = false)
            String negotiator,

            @RequestParam(required = false)
            String agreementType,

            @RequestParam(required = false)
            String principalInvestigator,

            @RequestParam(required = false)
            String sponsor,

            @RequestParam(required = false)
            String leadUnit,

            @RequestParam(required = false)
            String associationType,

            @RequestParam(required = false)
            String associationId,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate startDateFrom,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate startDateTo,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate endDateFrom,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate endDateTo,

            @RequestParam(defaultValue = "0")
            int page,

            @RequestParam(defaultValue = "25")
            int size
    ) {
        NegotiationSearchFilters filters = new NegotiationSearchFilters(
                query,
                status,
                negotiator,
                agreementType,
                principalInvestigator,
                sponsor,
                leadUnit,
                associationType,
                associationId,
                startDateFrom,
                startDateTo,
                endDateFrom,
                endDateTo
        );

        return ResponseEntity.ok(
                service.findPage(filters, page, size)
        );
    }

    @GetMapping("/{negotiationId}")
    public ResponseEntity<NegotiationWorkspaceResponse> workspace(
            @PathVariable
            long negotiationId
    ) {
        return ResponseEntity.ok(
                service.findWorkspace(negotiationId)
        );
    }

    @GetMapping("/{negotiationId}/activities")
    public ResponseEntity<List<NegotiationActivityResponse>> activities(
            @PathVariable
            long negotiationId
    ) {
        return ResponseEntity.ok(
                service.findActivities(negotiationId)
        );
    }

    @GetMapping("/{negotiationId}/custom-data")
    public ResponseEntity<List<NegotiationCustomDataResponse>> customData(
            @PathVariable
            long negotiationId
    ) {
        return ResponseEntity.ok(
                service.findCustomData(negotiationId)
        );
    }

    @GetMapping("/{negotiationId}/notifications")
    public ResponseEntity<List<NegotiationNotificationResponse>> notifications(
            @PathVariable
            long negotiationId
    ) {
        return ResponseEntity.ok(
                service.findNotifications(negotiationId)
        );
    }

    @GetMapping("/{negotiationId}/unassociated-details")
    public ResponseEntity<List<NegotiationUnassociatedDetailResponse>>
            unassociatedDetails(
                    @PathVariable
                    long negotiationId
            ) {
        return ResponseEntity.ok(
                service.findUnassociatedDetails(negotiationId)
        );
    }

    @GetMapping("/{negotiationId}/attachments")
    public ResponseEntity<List<NegotiationAttachmentResponse>> attachments(
            @PathVariable
            long negotiationId,

            Authentication authentication
    ) {
        attachmentAuthorizationService.requireAttachmentAccess(authentication);
        return ResponseEntity.ok(
                service.findAttachments(negotiationId)
        );
    }

    @GetMapping("/{negotiationId}/attachments/{attachmentId}/download")
    public ResponseEntity<StreamingResponseBody> downloadAttachment(
            @PathVariable
            long negotiationId,

            @PathVariable
            long attachmentId,

            Authentication authentication
    ) {
        attachmentAuthorizationService.requireAttachmentAccess(authentication);
        NegotiationAttachmentDownload download =
                service.downloadAttachment(negotiationId, attachmentId);

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

    @GetMapping("/{negotiationId}/associated-record")
    public ResponseEntity<NegotiationAssociatedRecordResponse>
            associatedRecord(
                    @PathVariable
                    long negotiationId
            ) {
        return ResponseEntity.ok(
                service.findAssociatedRecord(negotiationId)
        );
    }
}
