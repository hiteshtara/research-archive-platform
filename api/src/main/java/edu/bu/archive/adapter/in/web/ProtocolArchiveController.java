package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolActionResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolAmendRenewalResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolFundingResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolLocationResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolPersonResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolResearchAreaResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolSubmissionResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolVersionResponse;
import edu.bu.archive.application.protocol.ProtocolArchiveService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/protocols")
public class ProtocolArchiveController {

    private final ProtocolArchiveService service;

    public ProtocolArchiveController(ProtocolArchiveService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<ProtocolSummaryResponse> families(
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        return service.findFamilies(query, page, size);
    }

    @GetMapping("/{protocolNumber}/history")
    public ResponseEntity<List<ProtocolVersionResponse>> history(
            @PathVariable String protocolNumber
    ) {
        return ResponseEntity.ok(
                service.findHistory(protocolNumber)
        );
    }

    @GetMapping("/versions/{protocolId}")
    public ResponseEntity<ProtocolVersionResponse> version(
            @PathVariable long protocolId
    ) {
        return ResponseEntity.ok(service.findVersion(protocolId));
    }

    @GetMapping("/versions/{protocolId}/personnel")
    public ResponseEntity<List<ProtocolPersonResponse>> personnel(
            @PathVariable long protocolId
    ) {
        return ResponseEntity.ok(
                service.findPersonnel(protocolId)
        );
    }

    @GetMapping("/versions/{protocolId}/funding")
    public ResponseEntity<List<ProtocolFundingResponse>> funding(
            @PathVariable long protocolId
    ) {
        return ResponseEntity.ok(
                service.findFunding(protocolId)
        );
    }

    @GetMapping("/versions/{protocolId}/research-areas")
    public ResponseEntity<List<ProtocolResearchAreaResponse>> researchAreas(
            @PathVariable long protocolId
    ) {
        return ResponseEntity.ok(
                service.findResearchAreas(protocolId)
        );
    }

    @GetMapping("/versions/{protocolId}/locations")
    public ResponseEntity<List<ProtocolLocationResponse>> locations(
            @PathVariable long protocolId
    ) {
        return ResponseEntity.ok(service.findLocations(protocolId));
    }

    @GetMapping("/versions/{protocolId}/submissions")
    public ResponseEntity<List<ProtocolSubmissionResponse>> submissions(
            @PathVariable long protocolId
    ) {
        return ResponseEntity.ok(service.findSubmissions(protocolId));
    }

    @GetMapping("/versions/{protocolId}/actions")
    public ResponseEntity<List<ProtocolActionResponse>> actions(
            @PathVariable long protocolId
    ) {
        return ResponseEntity.ok(service.findActions(protocolId));
    }

    @GetMapping("/versions/{protocolId}/amend-renewals")
    public ResponseEntity<List<ProtocolAmendRenewalResponse>> amendRenewals(
            @PathVariable long protocolId
    ) {
        return ResponseEntity.ok(
                service.findAmendRenewals(protocolId)
        );
    }
}
