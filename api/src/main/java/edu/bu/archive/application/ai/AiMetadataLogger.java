package edu.bu.archive.application.ai;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
            int sequenceCount,
            String category,
            Long inputTokenCount,
            Long outputTokenCount,
            boolean cacheHit,
            String promptVersion,
            String promptHash
    ) {
        Long totalTokens = totalTokens(
                inputTokenCount,
                outputTokenCount
        );
        LOG.atInfo()
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
                .addKeyValue("sequenceCount", sequenceCount)
                .addKeyValue("category", category)
                .addKeyValue("inputTokens", inputTokenCount)
                .addKeyValue("outputTokens", outputTokenCount)
                .addKeyValue("totalTokens", totalTokens)
                .addKeyValue("cacheHit", cacheHit)
                .addKeyValue("promptVersion", promptVersion)
                .addKeyValue("promptHash", promptHash)
                .log("AI award summary request");
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
