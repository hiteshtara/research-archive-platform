package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.GlobalSearchResponse;
import edu.bu.archive.application.service.GlobalSearchService;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/*
 * One endpoint, fanned out inside the API - see GlobalSearchService for
 * the per-domain orchestration. The frontend never issues a
 * per-domain request.
 */
@RestController
@RequestMapping("/api/global-search")
@Validated
public class GlobalSearchController {

    private final GlobalSearchService service;

    public GlobalSearchController(GlobalSearchService service) {
        this.service = service;
    }

    @GetMapping
    public GlobalSearchResponse search(
            @RequestParam
            @NotBlank
            @Size(min = 2, max = 200)
            String query
    ) {
        return service.search(query);
    }
}
