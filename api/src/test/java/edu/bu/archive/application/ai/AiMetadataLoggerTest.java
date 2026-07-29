package edu.bu.archive.application.ai;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class AiMetadataLoggerTest {

    @Test
    void logsSafeOperationalMetadataAndTotalTokens() {
        Logger logger = (Logger) LoggerFactory.getLogger(
                AiMetadataLogger.class
        );
        ListAppender<ILoggingEvent> appender =
                new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            new AiMetadataLogger().log(
                    UUID.fromString(
                            "11111111-1111-1111-1111-111111111111"
                    ),
                    "user-subject",
                    "AWARD",
                    "A-100",
                    "openai",
                    "gpt-5",
                    250L,
                    640,
                    9,
                    "SUCCESS",
                    120L,
                    35L,
                    false,
                    "award-summary-v2",
                    "safe-prompt-hash"
            );

            ILoggingEvent event =
                    appender.list.getFirst();
            Map<String, Object> metadata =
                    event.getKeyValuePairs()
                            .stream()
                            .collect(Collectors.toMap(
                                    pair -> pair.key,
                                    pair -> pair.value
                            ));

            assertThat(metadata)
                    .containsEntry("durationMs", 250L)
                    .containsEntry("contextCharacters", 640)
                    .containsEntry("inputTokens", 120L)
                    .containsEntry("outputTokens", 35L)
                    .containsEntry("totalTokens", 155L)
                    .containsEntry("cacheHit", false)
                    .containsEntry("provider", "openai")
                    .containsEntry("model", "gpt-5")
                    .containsEntry(
                            "promptVersion",
                            "award-summary-v2"
                    )
                    .containsEntry(
                            "promptHash",
                            "safe-prompt-hash"
                    )
                    .containsEntry("sequenceCount", 9)
                    .containsEntry("category", "SUCCESS");
            assertThat(event.getFormattedMessage())
                    .doesNotContain("Use only")
                    .doesNotContain("Bearer")
                    .doesNotContain("OPENAI_API_KEY");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void logsQuestionIdentityAndSafeMetadataWithoutQuestionText() {
        Logger logger = (Logger) LoggerFactory.getLogger(
                AiMetadataLogger.class
        );
        ListAppender<ILoggingEvent> appender =
                new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            new AiMetadataLogger().logQuestion(
                    UUID.fromString(
                            "22222222-2222-2222-2222-222222222222"
                    ),
                    "jwt-subject",
                    "A-100",
                    "deterministic",
                    "none",
                    4L,
                    0,
                    2,
                    "deterministic_question_success",
                    null,
                    null,
                    "award-question-v1",
                    "question-prompt-hash",
                    "deterministic_fact",
                    27
            );

            ILoggingEvent event =
                    appender.list.getFirst();
            Map<String, Object> metadata =
                    event.getKeyValuePairs()
                            .stream()
                            .collect(Collectors.toMap(
                                    pair -> pair.key,
                                    pair -> pair.value
                            ));

            assertThat(metadata)
                    .containsEntry("operation", "award_question")
                    .containsEntry(
                            "authenticatedUserId",
                            "jwt-subject"
                    )
                    .containsEntry("questionCharacters", 27)
                    .containsEntry(
                            "promptVersion",
                            "award-question-v1"
                    )
                    .containsEntry(
                            "promptHash",
                            "question-prompt-hash"
                    );
            assertThat(event.getFormattedMessage())
                    .doesNotContain(
                            "What is",
                            "Bearer",
                            "OPENAI_API_KEY"
                    );
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
