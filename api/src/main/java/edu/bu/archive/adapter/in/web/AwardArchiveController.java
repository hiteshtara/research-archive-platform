package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.award.AwardFamilyResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardFamilySummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSequenceDetailResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSequencePageResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardWorkspaceResponse;
import edu.bu.archive.adapter.out.persistence.AwardArchiveRepository;
import edu.bu.archive.application.award.AwardArchiveService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/awards")
public class AwardArchiveController {

    private final AwardArchiveService service;
    private final AwardArchiveRepository repository;

    public AwardArchiveController(
            AwardArchiveService service,
            AwardArchiveRepository repository
    ) {
        this.service = service;
        this.repository = repository;
    }

    @GetMapping("/families")
    public List<AwardFamilySummaryResponse> families(
            @RequestParam(required = false)
            String query,

            @RequestParam(defaultValue = "50")
            int limit
    ) {
        int safeLimit = Math.min(
                Math.max(limit, 1),
                200
        );

        return repository.findFamilies(
                query,
                safeLimit
        );
    }

    /*
     * Lightweight Award workspace.
     */
    @GetMapping("/{awardNumber}")
    public ResponseEntity<AwardWorkspaceResponse> workspace(
            @PathVariable
            String awardNumber
    ) {
        return ResponseEntity.ok(
                service.findWorkspace(
                        awardNumber
                )
        );
    }

    /*
     * Paginated sequence summaries.
     */
    @GetMapping("/{awardNumber}/history")
    public ResponseEntity<AwardSequencePageResponse> historyPage(
            @PathVariable
            String awardNumber,

            @RequestParam(defaultValue = "0")
            int page,

            @RequestParam(defaultValue = "25")
            int size
    ) {
        return ResponseEntity.ok(
                service.findSequencePage(
                        awardNumber,
                        page,
                        size
                )
        );
    }

    /*
     * Load only one selected sequence.
     */
    @GetMapping("/{awardNumber}/history/{sequenceNumber}")
    public ResponseEntity<AwardSequenceDetailResponse> sequence(
            @PathVariable
            String awardNumber,

            @PathVariable
            int sequenceNumber
    ) {
        return ResponseEntity.ok(
                service.findSequence(
                        awardNumber,
                        sequenceNumber
                )
        );
    }


    @GetMapping("/{awardNumber}/people")
    public ResponseEntity<
            List<
                    edu.bu.archive.adapter.in.web.dto.award
                            .AwardPersonResponse
                    >
            > people(
                    @PathVariable
                    String awardNumber
            ) {
        return ResponseEntity.ok(
                service.findCurrentPeople(
                        awardNumber
                )
        );
    }

    /*
     * Existing proof-of-concept endpoint.
     * Keep until the React UI switches to the paginated endpoints.
     */
    @GetMapping("/history/{awardNumber}")
    public ResponseEntity<AwardFamilyResponse> legacyHistory(
            @PathVariable
            String awardNumber
    ) {
        return ResponseEntity.ok(
                service.findFamily(
                        awardNumber
                )
        );
    }
}
