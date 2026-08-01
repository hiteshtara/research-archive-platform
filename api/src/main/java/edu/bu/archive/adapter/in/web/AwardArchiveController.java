package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.award.AwardFamilyResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardFamilySummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardHierarchyResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResultResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSequenceDetailResponse;
import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSequenceSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardVersionSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardWorkspaceResponse;
import edu.bu.archive.adapter.out.persistence.AwardArchiveRepository;
import edu.bu.archive.application.award.AwardArchiveService;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/awards")
@Validated
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
    public ResponseEntity<PageResponse<AwardSequenceSummaryResponse>> historyPage(
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


    @GetMapping("/{awardNumber}/amounts")
    public ResponseEntity<
            List<
                    edu.bu.archive.adapter.in.web.dto.award
                            .AwardAmountResponse
                    >
            > amounts(
                    @PathVariable
                    String awardNumber
            ) {
        return ResponseEntity.ok(
                service.findCurrentAmounts(
                        awardNumber
                )
        );
    }

    @GetMapping("/{awardNumber}/proposals")
    public ResponseEntity<
            List<
                    edu.bu.archive.adapter.in.web.dto.award
                            .AwardProposalResponse
                    >
            > proposals(
                    @PathVariable
                    String awardNumber
            ) {
        return ResponseEntity.ok(
                service.findCurrentProposals(
                        awardNumber
                )
        );
    }

    @GetMapping("/{awardNumber}/funding")
    public ResponseEntity<
            edu.bu.archive.adapter.in.web.dto.award
                    .AwardFundingResponse
            > funding(
                    @PathVariable
                    String awardNumber
            ) {
        return ResponseEntity.ok(
                service.findCurrentFunding(
                        awardNumber
                )
        );
    }

    /*
     * Award search - supports exact/partial/wildcard (*text*) Award
     * number, PI/person name, title, sponsor code/name, lead unit
     * number/name, document number. See AwardSearchPattern for how q
     * is normalized into a safe, parameterized ILIKE pattern.
     */
    @GetMapping("/search")
    public ResponseEntity<PageResponse<AwardSearchResultResponse>> search(
            @RequestParam(name = "q", required = false)
            String q,

            @RequestParam(defaultValue = "0")
            @Min(0)
            int page,

            @RequestParam(defaultValue = "25")
            @Min(1)
            @Max(100)
            int size
    ) {
        return ResponseEntity.ok(
                service.search(q, page, size)
        );
    }

    /*
     * Full recursive Award hierarchy for the family containing
     * awardNumber - root, all descendants, and the selected Award path.
     */
    @GetMapping("/{awardNumber}/hierarchy")
    public ResponseEntity<AwardHierarchyResponse> hierarchy(
            @PathVariable
            String awardNumber
    ) {
        return ResponseEntity.ok(
                service.findHierarchy(awardNumber)
        );
    }

    /*
     * Compact Award summary only - no comments, Budget, Time and Money,
     * SAP transmission history, or attachments (later section
     * endpoints). Keyed by the surrogate award_id, not award_number.
     */
    @GetMapping("/{awardId}/summary")
    public ResponseEntity<AwardSummaryResponse> summary(
            @PathVariable
            long awardId
    ) {
        return ResponseEntity.ok(
                service.findSummary(awardId)
        );
    }

    /*
     * All Award versions for the family containing awardId, newest
     * version first.
     */
    @GetMapping("/{awardId}/versions")
    public ResponseEntity<List<AwardVersionSummaryResponse>> versions(
            @PathVariable
            long awardId
    ) {
        return ResponseEntity.ok(
                service.findVersions(awardId)
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
