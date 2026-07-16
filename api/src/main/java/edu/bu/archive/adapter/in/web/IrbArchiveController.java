package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.IrbFamilyResponse;
import edu.bu.archive.adapter.in.web.dto.IrbHistoryResponse;
import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.out.persistence.IrbArchiveRepository;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/irb")
@Validated
public class IrbArchiveController {

    private final IrbArchiveRepository repository;

    public IrbArchiveController(IrbArchiveRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/families")
    public PageResponse<IrbFamilyResponse> families(
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0")
            @Min(0) int page,
            @RequestParam(defaultValue = "25")
            @Min(1) @Max(100) int size
    ) {
        return repository.findFamilies(query, page, size);
    }

    @GetMapping("/history")
    public PageResponse<IrbHistoryResponse> history(
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0")
            @Min(0) int page,
            @RequestParam(defaultValue = "25")
            @Min(1) @Max(100) int size
    ) {
        return repository.findHistory(query, page, size);
    }
}
