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
            long elapsedTimeMs,
            String status
    ) {
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
                .addKeyValue("elapsedTimeMs", elapsedTimeMs)
                .addKeyValue("status", status)
                .log("AI award summary request");
    }
}
