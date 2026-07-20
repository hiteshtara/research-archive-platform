package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.award.AwardFamilyResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardFamilySummaryResponse;
import edu.bu.archive.application.award.AwardArchiveService;
import edu.bu.archive.adapter.out.persistence.AwardArchiveRepository;

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

    /**
     * Search Award Families
     *
     * Examples
     *
     * GET /api/awards/families
     *
     * GET /api/awards/families?query=NIH
     *
     * GET /api/awards/families?query=100836
     */
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

    /**
     * Award Family History
     *
     * GET /api/awards/history/100836-00001
     */
    @GetMapping("/history/{awardNumber}")
    public ResponseEntity<AwardFamilyResponse> history(

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
