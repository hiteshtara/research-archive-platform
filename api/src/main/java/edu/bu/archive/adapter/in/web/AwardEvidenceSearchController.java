package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.ai.AwardEvidenceSearchRequest;
import edu.bu.archive.adapter.in.web.dto.ai.AwardEvidenceSearchResponse;
import edu.bu.archive.application.ai.AwardEvidenceSearchService;

import jakarta.validation.Valid;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * Shares AwardAiController/AwardAiQuestionController's "/api/ai/awards"
 * base path for URL-namespace consistency, but is deliberately gated on
 * app.search.semantic.enabled - NOT app.ai.enabled, which is off in dev
 * today with no dependency planned here. Evidence retrieval is
 * embedding-based similarity search, not LLM generation - it belongs
 * with the semantic-search flag (already true in dev), not the
 * AI-generation flag. See
 * docs/architecture/AWARD_EVIDENCE_RETRIEVAL_PHASE3_DESIGN.md section
 * 3.1. Authentication is enforced the same way every other /api/**
 * endpoint already is (SecurityConfiguration.java's
 * .requestMatchers("/api/**").authenticated()) - no per-controller
 * change needed.
 */
@RestController
@RequestMapping("/api/ai/awards")
@ConditionalOnProperty(
        name = "app.search.semantic.enabled",
        havingValue = "true"
)
public class AwardEvidenceSearchController {

    private final AwardEvidenceSearchService service;

    public AwardEvidenceSearchController(
            AwardEvidenceSearchService service
    ) {
        this.service = service;
    }

    @PostMapping("/{awardNumber}/evidence-search")
    public ResponseEntity<AwardEvidenceSearchResponse> evidenceSearch(
            @PathVariable
            String awardNumber,
            @Valid
            @RequestBody
            AwardEvidenceSearchRequest request
    ) {
        return ResponseEntity.ok(
                service.search(
                        awardNumber,
                        request.query(),
                        request.documentTypes(),
                        request.topK()
                )
        );
    }
}
