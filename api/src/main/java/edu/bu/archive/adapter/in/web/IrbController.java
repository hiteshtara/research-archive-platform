package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.IrbSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.application.port.in.IrbQueryUseCase;
import edu.bu.archive.domain.model.IrbSearchQuery;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/*
 * IRB is out of release scope and has no record authorization. The release
 * configuration sets app.irb.api.enabled=false so this controller is never
 * registered there; absent or true keeps today's behaviour. See
 * docs/security/IRB_RELEASE_EXCLUSION.md.
 */
@ConditionalOnProperty(
        prefix = "app.irb.api",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@RestController
@RequestMapping("/api/irb")
@Validated
public class IrbController {

    private final IrbQueryUseCase useCase;

    public IrbController(IrbQueryUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping
    public PageResponse<IrbSummaryResponse> search(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0")
            @Min(0) int page,
            @RequestParam(defaultValue = "25")
            @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "approvalDate")
            String sort,
            @RequestParam(defaultValue = "desc")
            String direction
    ) {
        IrbSearchQuery searchQuery = new IrbSearchQuery(
                query,
                status,
                type,
                page,
                size,
                sort,
                direction
        );

        return IrbWebMapper.toResponse(
                useCase.search(searchQuery)
        );
    }

    @GetMapping("/record/{recordId}")
    public IrbSummaryResponse findByRecordId(
            @PathVariable @Positive Long recordId
    ) {
        return IrbWebMapper.toResponse(
                useCase.findByRecordId(recordId)
        );
    }

    @GetMapping("/{studyId}")
    public IrbSummaryResponse findByStudyId(
            @PathVariable @NotBlank String studyId
    ) {
        return IrbWebMapper.toResponse(
                useCase.findByStudyId(studyId)
        );
    }
}
