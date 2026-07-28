package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.ai.AwardAiSummaryResponse;
import edu.bu.archive.application.ai.AwardAiSummaryService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/awards")
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true")
public class AwardAiController {

    private final AwardAiSummaryService service;
    private final boolean securityEnabled;

    public AwardAiController(
            AwardAiSummaryService service,
            @Value("${app.security.enabled:true}")
            boolean securityEnabled
    ) {
        this.service = service;
        this.securityEnabled = securityEnabled;
    }

    @PostMapping("/{awardNumber}/summary")
    public ResponseEntity<AwardAiSummaryResponse> summarize(
            @PathVariable
            String awardNumber,
            @AuthenticationPrincipal
            Jwt jwt,
            @RequestBody(required = false)
            byte[] requestBody
    ) {
        if (requestBody != null && requestBody.length > 0) {
            throw new IllegalArgumentException(
                    "Request body is not allowed"
            );
        }

        String authenticatedUserId;

        if (jwt != null
                && jwt.getSubject() != null
                && !jwt.getSubject().isBlank()) {
            authenticatedUserId = jwt.getSubject();
        } else if (!securityEnabled) {
            authenticatedUserId = "local-dev";
        } else {
            throw new IllegalArgumentException(
                    "Authenticated user identifier is required"
            );
        }

        return ResponseEntity.ok(
                AwardAiSummaryResponse.from(
                        service.summarize(
                                awardNumber,
                                authenticatedUserId
                        )
                )
        );
    }
}
