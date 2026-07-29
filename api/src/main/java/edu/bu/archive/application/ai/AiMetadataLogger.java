package edu.bu.archive.application.ai;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true")
public class AiMetadataLogger {

    private static final Logger LOG =
            LoggerFactory.getLogger(AiMetadataLogger.class);

    public void log(
            UUID correlationId,
            String authenticatedUserId,
            String archiveDomain,
            String awardNumber,
            String provider,
            String model,
            long durationMs,
            int contextCharacters,
            int sequenceCount,
            String category,
            Long inputTokenCount,
            Long outputTokenCount,
            boolean cacheHit,
            String promptVersion,
            String promptHash
    ) {
        log(
                "award_summary",
                correlationId,
                authenticatedUserId,
                archiveDomain,
                awardNumber,
                provider,
                model,
                durationMs,
                contextCharacters,
                sequenceCount,
                category,
                inputTokenCount,
                outputTokenCount,
                cacheHit,
                promptVersion,
                promptHash,
                null,
                null
        );
    }

    void logQuestion(
            UUID correlationId,
            String authenticatedUserId,
            String awardNumber,
            String provider,
            String model,
            long durationMs,
            int contextCharacters,
            int sequenceCount,
            String category,
            Long inputTokenCount,
            Long outputTokenCount,
            String promptVersion,
            String promptHash,
            String answerType,
            int questionCharacters
    ) {
        log(
                "award_question",
                correlationId,
                authenticatedUserId,
                "AWARD",
                awardNumber,
                provider,
                model,
                durationMs,
                contextCharacters,
                sequenceCount,
                category,
                inputTokenCount,
                outputTokenCount,
                false,
                promptVersion,
                promptHash,
                answerType,
                questionCharacters
        );
    }

    private void log(
            String operation,
            UUID correlationId,
            String authenticatedUserId,
            String archiveDomain,
            String awardNumber,
            String provider,
            String model,
            long durationMs,
            int contextCharacters,
            int sequenceCount,
            String category,
            Long inputTokenCount,
            Long outputTokenCount,
            boolean cacheHit,
            String promptVersion,
            String promptHash,
            String answerType,
            Integer questionCharacters
    ) {
        Long totalTokens = totalTokens(
                inputTokenCount,
                outputTokenCount
        );
        LoggingEventBuilder event = LOG.atInfo()
                .addKeyValue("operation", operation)
                .addKeyValue("correlationId", correlationId)
                .addKeyValue(
                        "authenticatedUserId",
                        authenticatedUserId
                )
                .addKeyValue("archiveDomain", archiveDomain)
                .addKeyValue("awardNumber", awardNumber)
                .addKeyValue("provider", provider)
                .addKeyValue("model", model)
                .addKeyValue("durationMs", durationMs)
                .addKeyValue(
                        "contextCharacters",
                        contextCharacters
                )
                .addKeyValue("sequenceCount", sequenceCount)
                .addKeyValue("category", category)
                .addKeyValue("cacheHit", cacheHit)
                .addKeyValue("promptVersion", promptVersion)
                .addKeyValue("promptHash", promptHash);
        if (inputTokenCount != null) {
            event.addKeyValue("inputTokens", inputTokenCount);
        }
        if (outputTokenCount != null) {
            event.addKeyValue("outputTokens", outputTokenCount);
        }
        if (totalTokens != null) {
            event.addKeyValue("totalTokens", totalTokens);
        }
        if (answerType != null) {
            event.addKeyValue("answerType", answerType);
        }
        if (questionCharacters != null) {
            event.addKeyValue(
                    "questionCharacters",
                    questionCharacters
            );
        }
        event.log(
                "award_question".equals(operation)
                        ? "AI award question request"
                        : "AI award summary request"
        );
    }

    private Long totalTokens(
            Long inputTokens,
            Long outputTokens
    ) {
        if (inputTokens == null && outputTokens == null) {
            return null;
        }
        return (inputTokens == null ? 0 : inputTokens)
                + (outputTokens == null ? 0 : outputTokens);
    }
}
