package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationActivityResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationAssociatedRecordResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationCustomDataResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationNotificationResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationUnassociatedDetailResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationWorkspaceResponse;
import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.application.negotiation.NegotiationArchiveService;
import edu.bu.archive.application.negotiation.NegotiationAttachmentDownload;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/negotiations")
public class NegotiationArchiveController {

    private final NegotiationArchiveService service;

    public NegotiationArchiveController(
            NegotiationArchiveService service
    ) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<PageResponse<NegotiationSummaryResponse>> search(
            @RequestParam(required = false)
            String query,

            @RequestParam(defaultValue = "0")
            int page,

            @RequestParam(defaultValue = "25")
            int size
    ) {
        return ResponseEntity.ok(
                service.findPage(query, page, size)
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
            long negotiationId
    ) {
        return ResponseEntity.ok(
                service.findAttachments(negotiationId)
        );
    }

    @GetMapping("/{negotiationId}/attachments/{attachmentId}/download")
    public ResponseEntity<StreamingResponseBody> downloadAttachment(
            @PathVariable
            long negotiationId,

            @PathVariable
            long attachmentId
    ) {
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
