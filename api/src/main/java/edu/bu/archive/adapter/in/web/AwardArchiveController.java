package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.award.AwardHistoryResponse;
import edu.bu.archive.adapter.out.persistence.AwardArchiveRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/awards")
public class AwardArchiveController {

    private final AwardArchiveRepository repository;

    public AwardArchiveController(
            AwardArchiveRepository repository
    ) {
        this.repository = repository;
    }

    @GetMapping("/history/{awardNumber}")
    public List<AwardHistoryResponse> history(
            @PathVariable String awardNumber
    ) {

        return repository.history(
                awardNumber
        );

    }

}
