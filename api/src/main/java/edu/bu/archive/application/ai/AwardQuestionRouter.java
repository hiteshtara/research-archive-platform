package edu.bu.archive.application.ai;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "app.ai.questions-enabled",
        havingValue = "true"
)
public class AwardQuestionRouter {

    private static final Pattern CAUSAL_LANGUAGE =
            Pattern.compile("\\b(why|reason|decision|cause)\\b");
    private static final Pattern TWO_SEQUENCES = Pattern.compile(
            "\\b(?:sequence|seq)\\s*(\\d+)\\b.*"
                    + "\\b(?:sequence|seq)\\s*(\\d+)\\b"
    );

    AwardQuestionRoute route(
            String question
    ) {
        String normalized = question
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");

        if (CAUSAL_LANGUAGE.matcher(normalized).find()) {
            return AwardQuestionRoute.intent(
                    AwardQuestionIntent.INSUFFICIENT
            );
        }

        Matcher sequences = TWO_SEQUENCES.matcher(normalized);
        if (sequences.find()
                && (normalized.contains("compare")
                || normalized.contains("change")
                || normalized.contains("difference"))) {
            return new AwardQuestionRoute(
                    AwardQuestionIntent.SEQUENCE_COMPARISON,
                    Integer.valueOf(sequences.group(1)),
                    Integer.valueOf(sequences.group(2))
            );
        }

        if (normalized.contains("last two sequence")) {
            return AwardQuestionRoute.intent(
                    AwardQuestionIntent.SEQUENCE_COMPARISON
            );
        }
        if (normalized.contains("administrative change")) {
            return AwardQuestionRoute.intent(
                    AwardQuestionIntent.LIKELY_ADMINISTRATIVE_CHANGES
            );
        }
        if (normalized.contains("summar")
                && normalized.contains("histor")) {
            return AwardQuestionRoute.intent(
                    AwardQuestionIntent.HISTORY_SUMMARY
            );
        }
        if (normalized.contains("principal investigator")
                || normalized.matches(".*\\bpi\\b.*")) {
            return AwardQuestionRoute.intent(
                    AwardQuestionIntent.CURRENT_PI
            );
        }
        if (normalized.contains("anticipated")
                && normalized.contains("amount")) {
            return AwardQuestionRoute.intent(
                    AwardQuestionIntent.CURRENT_ANTICIPATED_AMOUNT
            );
        }
        if (normalized.contains("obligated")
                && normalized.contains("amount")) {
            return AwardQuestionRoute.intent(
                    AwardQuestionIntent.CURRENT_OBLIGATED_AMOUNT
            );
        }
        if (normalized.contains("lead unit")) {
            return AwardQuestionRoute.intent(
                    AwardQuestionIntent.CURRENT_LEAD_UNIT
            );
        }
        if (normalized.contains("sponsor")) {
            return AwardQuestionRoute.intent(
                    AwardQuestionIntent.CURRENT_SPONSOR
            );
        }
        if (normalized.contains("status")) {
            return AwardQuestionRoute.intent(
                    AwardQuestionIntent.CURRENT_STATUS
            );
        }
        if (normalized.contains("title")) {
            return AwardQuestionRoute.intent(
                    AwardQuestionIntent.CURRENT_TITLE
            );
        }
        if (normalized.contains("date")
                || normalized.contains("begin")
                || normalized.contains("closeout")) {
            return AwardQuestionRoute.intent(
                    AwardQuestionIntent.CURRENT_DATES
            );
        }
        if (normalized.contains("current sequence")
                || normalized.matches(".*\\bsequence number\\b.*")) {
            return AwardQuestionRoute.intent(
                    AwardQuestionIntent.CURRENT_SEQUENCE
            );
        }
        return AwardQuestionRoute.intent(
                AwardQuestionIntent.INSUFFICIENT
        );
    }
}
