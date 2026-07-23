package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.subaward.SubawardAmountResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardCloseoutResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardContactResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardCustomDataResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardFundingResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardNotepadResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardNotificationResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardPageResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardReportResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardTemplateInfoResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardWorkspaceResponse;
import edu.bu.archive.application.subaward.SubawardArchiveService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/subawards")
public class SubawardArchiveController {

    private final SubawardArchiveService service;

    public SubawardArchiveController(
            SubawardArchiveService service
    ) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<SubawardPageResponse> search(
            @RequestParam(required = false)
            String query,

            @RequestParam(defaultValue = "0")
            int page,

            @RequestParam(defaultValue = "25")
            int size
    ) {
        return ResponseEntity.ok(service.findPage(query, page, size));
    }

    @GetMapping("/{subawardId}")
    public ResponseEntity<SubawardWorkspaceResponse> workspace(
            @PathVariable
            long subawardId
    ) {
        return ResponseEntity.ok(service.findWorkspace(subawardId));
    }

    @GetMapping("/{subawardId}/amounts")
    public ResponseEntity<List<SubawardAmountResponse>> amounts(
            @PathVariable
            long subawardId
    ) {
        return ResponseEntity.ok(service.findAmounts(subawardId));
    }

    @GetMapping("/{subawardId}/contacts")
    public ResponseEntity<List<SubawardContactResponse>> contacts(
            @PathVariable
            long subawardId
    ) {
        return ResponseEntity.ok(service.findContacts(subawardId));
    }

    @GetMapping("/{subawardId}/custom-data")
    public ResponseEntity<List<SubawardCustomDataResponse>> customData(
            @PathVariable
            long subawardId
    ) {
        return ResponseEntity.ok(service.findCustomData(subawardId));
    }

    @GetMapping("/{subawardId}/funding")
    public ResponseEntity<List<SubawardFundingResponse>> funding(
            @PathVariable
            long subawardId
    ) {
        return ResponseEntity.ok(service.findFunding(subawardId));
    }

    @GetMapping("/{subawardId}/attachments")
    public ResponseEntity<List<SubawardAttachmentResponse>> attachments(
            @PathVariable
            long subawardId
    ) {
        return ResponseEntity.ok(service.findAttachments(subawardId));
    }

    @GetMapping("/{subawardId}/template-info")
    public ResponseEntity<SubawardTemplateInfoResponse> templateInfo(
            @PathVariable
            long subawardId
    ) {
        return ResponseEntity.ok(service.findTemplateInfo(subawardId));
    }

    @GetMapping("/{subawardId}/closeout")
    public ResponseEntity<List<SubawardCloseoutResponse>> closeout(
            @PathVariable
            long subawardId
    ) {
        return ResponseEntity.ok(service.findCloseout(subawardId));
    }

    @GetMapping("/{subawardId}/reports")
    public ResponseEntity<List<SubawardReportResponse>> reports(
            @PathVariable
            long subawardId
    ) {
        return ResponseEntity.ok(service.findReports(subawardId));
    }

    @GetMapping("/{subawardId}/notepad")
    public ResponseEntity<List<SubawardNotepadResponse>> notepad(
            @PathVariable
            long subawardId
    ) {
        return ResponseEntity.ok(service.findNotepad(subawardId));
    }

    @GetMapping("/{subawardId}/notifications")
    public ResponseEntity<List<SubawardNotificationResponse>> notifications(
            @PathVariable
            long subawardId
    ) {
        return ResponseEntity.ok(service.findNotifications(subawardId));
    }
}
