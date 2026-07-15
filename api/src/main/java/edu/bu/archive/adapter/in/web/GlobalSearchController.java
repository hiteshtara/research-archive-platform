package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.GlobalSearchItemResponse;
import edu.bu.archive.adapter.in.web.dto.GlobalSearchResponse;
import edu.bu.archive.application.port.in.IrbQueryUseCase;
import edu.bu.archive.domain.model.IrbProtocol;
import edu.bu.archive.domain.model.IrbSearchQuery;
import edu.bu.archive.domain.model.PageResult;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/global-search")
@Validated
public class GlobalSearchController {

    private final IrbQueryUseCase irbQueryUseCase;

    public GlobalSearchController(IrbQueryUseCase irbQueryUseCase) {
        this.irbQueryUseCase = irbQueryUseCase;
    }

    @GetMapping
    public GlobalSearchResponse search(
            @RequestParam
            @NotBlank
            @Size(min = 2, max = 200)
            String query
    ) {
        String normalizedQuery = query.trim();

        PageResult<IrbProtocol> irbResults = irbQueryUseCase.search(
                new IrbSearchQuery(
                        normalizedQuery,
                        null,
                        null,
                        0,
                        20,
                        "approvalDate",
                        "desc"
                )
        );

        List<GlobalSearchItemResponse> results = irbResults.content()
                .stream()
                .map(protocol -> new GlobalSearchItemResponse(
                        protocol.recordId(),
                        "IRB",
                        protocol.studyId() != null
                                ? protocol.studyId()
                                : protocol.protocolBase(),
                        protocol.protocolNumber(),
                        protocol.title(),
                        protocol.protocolStatus(),
                        protocol.piFullName(),
                        protocol.protocolType()
                ))
                .toList();

        return new GlobalSearchResponse(
                normalizedQuery,
                irbResults.totalElements(),
                results
        );
    }
}
