package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.GlobalSearchResponse;
import edu.bu.archive.adapter.out.persistence.GlobalSearchRepository;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/global-search")
@Validated
public class GlobalSearchController {

    private final GlobalSearchRepository repository;

    public GlobalSearchController(GlobalSearchRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public GlobalSearchResponse search(
            @RequestParam
            @NotBlank
            @Size(min = 2, max = 200)
            String query
    ) {
        return repository.search(query);
    }
}
