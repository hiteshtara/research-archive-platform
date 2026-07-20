package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.award.AwardFamilyResponse;
import edu.bu.archive.application.award.AwardArchiveService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/awards")
public class AwardArchiveController {

    private final AwardArchiveService service;

    public AwardArchiveController(
            AwardArchiveService service
    ) {
        this.service = service;
    }

    @GetMapping("/history/{awardNumber}")
    public ResponseEntity<AwardFamilyResponse> history(
            @PathVariable String awardNumber
    ) {
        return ResponseEntity.ok(
                service.findFamily(awardNumber)
        );
    }
}
