package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationActivityResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationCustomDataResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationNotificationResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationPageResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationUnassociatedDetailResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationWorkspaceResponse;
import edu.bu.archive.application.negotiation.NegotiationArchiveService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    public ResponseEntity<NegotiationPageResponse> search(
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
}
