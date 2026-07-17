package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.InvestigatorProfileResponse;
import edu.bu.archive.adapter.out.persistence.InvestigatorRepository;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/investigators")
@Validated
public class InvestigatorController {

    private final InvestigatorRepository repository;

    public InvestigatorController(InvestigatorRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public InvestigatorProfileResponse findByEmail(
            @RequestParam
            @NotBlank
            @Email
            @Size(max = 320)
            String email
    ) {
        return repository.findByEmail(email);
    }
}
