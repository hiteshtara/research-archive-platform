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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    /*
     * modules (optional, repeatable or comma-separated): restrict the
     * search to these record types - AWARD, PROPOSAL, NEGOTIATION,
     * SUBAWARD. Omitted means every module. An unknown value is a 400,
     * never silently ignored.
     */
    @GetMapping
    public GlobalSearchResponse search(
            @RequestParam
            @NotBlank
            @Size(min = 2, max = 200)
            String query,

            @RequestParam(required = false)
            List<String> modules
    ) {
        // Spring only splits a comma-separated value when the parameter
        // appears once; split every value so both forms mean the same.
        Set<String> selected = new LinkedHashSet<>();
        if (modules != null) {
            for (String value : modules) {
                if (value == null) {
                    continue;
                }
                for (String part : value.split(",")) {
                    if (!part.isBlank()) {
                        selected.add(part.trim());
                    }
                }
            }
        }
        return service.search(query, selected);
    }
}
