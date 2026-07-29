package edu.bu.archive.application.ai;

import edu.bu.archive.domain.model.ai.AiCitation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true")
public class AwardCitationValidator {

    List<AiCitation> validateRequired(
            List<AiCitation> citations,
            List<AiCitation> allowedCitations
    ) {
        if (citations == null || citations.isEmpty()) {
            throw new AiProviderException(
                    "AI provider returned an invalid response"
            );
        }
        return validate(citations, allowedCitations);
    }

    List<AiCitation> validate(
            List<AiCitation> citations,
            List<AiCitation> allowedCitations
    ) {
        if (citations == null) {
            throw new AiProviderException(
                    "AI provider returned an invalid response"
            );
        }

        Map<String, AiCitation> allowed = new HashMap<>();
        allowedCitations.forEach(citation ->
                allowed.put(citation.recordId(), citation)
        );

        List<AiCitation> validated = new ArrayList<>();
        for (AiCitation citation : citations) {
            if (citation == null) {
                throw unsupported();
            }
            String recordId = normalize(citation.recordId());
            AiCitation supplied = allowed.get(recordId);
            if (supplied == null
                    || !"award".equalsIgnoreCase(
                            normalize(citation.recordType())
                    )
                    || !Objects.equals(
                            supplied.awardNumber(),
                            normalize(citation.awardNumber())
                    )
                    || !Objects.equals(
                            supplied.sequenceNumber(),
                            citation.sequenceNumber()
                    )) {
                throw unsupported();
            }
            validated.add(supplied);
        }
        return List.copyOf(validated);
    }

    private String normalize(
            String value
    ) {
        return value == null ? null : value.trim();
    }

    private AiProviderException unsupported() {
        return new AiProviderException(
                "AI provider returned an unsupported citation"
        );
    }
}
