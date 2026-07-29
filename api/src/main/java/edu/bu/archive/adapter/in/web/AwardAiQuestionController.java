package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.ai.AwardAiQuestionRequest;
import edu.bu.archive.adapter.in.web.dto.ai.AwardAiQuestionResponse;
import edu.bu.archive.application.ai.AwardAiQuestionService;

import jakarta.validation.Valid;

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
@ConditionalOnProperty(
        name = {
                "app.ai.enabled",
                "app.ai.questions-enabled"
        },
        havingValue = "true"
)
public class AwardAiQuestionController {

    private final AwardAiQuestionService service;
    private final boolean securityEnabled;

    public AwardAiQuestionController(
            AwardAiQuestionService service,
            @Value("${app.security.enabled:true}")
            boolean securityEnabled
    ) {
        this.service = service;
        this.securityEnabled = securityEnabled;
    }

    @PostMapping("/{awardNumber}/questions")
    public ResponseEntity<AwardAiQuestionResponse> answer(
            @PathVariable
            String awardNumber,
            @Valid
            @RequestBody
            AwardAiQuestionRequest request,
            @AuthenticationPrincipal
            Jwt jwt
    ) {
        return ResponseEntity.ok(
                AwardAiQuestionResponse.from(
                        service.answer(
                                awardNumber,
                                request.question(),
                                authenticatedUserId(jwt)
                        )
                )
        );
    }

    private String authenticatedUserId(
            Jwt jwt
    ) {
        if (jwt != null
                && jwt.getSubject() != null
                && !jwt.getSubject().isBlank()) {
            return jwt.getSubject();
        }
        if (!securityEnabled) {
            return "local-dev";
        }
        throw new IllegalArgumentException(
                "Authenticated user identifier is required"
        );
    }
}
