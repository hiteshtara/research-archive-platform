package edu.bu.archive.application.ai;

import edu.bu.archive.config.AiProperties;
import edu.bu.archive.domain.model.ai.AiCitation;
import edu.bu.archive.domain.model.ai.AwardAiNarrative;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AwardAiSummaryCacheTest {

    @Test
    void cacheHitsOnlyForTheExactVersionedKey() {
        AiProperties properties = new AiProperties();
        properties.setCacheEnabled(true);
        properties.setCacheMaxEntries(10);
        AwardAiSummaryCache cache =
                new AwardAiSummaryCache(properties);
        AwardAiSummaryCache.Key key = new AwardAiSummaryCache.Key(
                101L,
                2,
                "openai",
                "gpt-5",
                "award-summary-v2",
                "prompt-hash-1"
        );
        AwardAiNarrative narrative = narrative();

        cache.put(key, narrative);

        assertThat(cache.get(key)).contains(narrative);
        assertThat(cache.get(new AwardAiSummaryCache.Key(
                102L, 2, "openai", "gpt-5",
                "award-summary-v2", "prompt-hash-1"
        ))).isEmpty();
        assertThat(cache.get(new AwardAiSummaryCache.Key(
                101L, 3, "openai", "gpt-5",
                "award-summary-v2", "prompt-hash-1"
        ))).isEmpty();
        assertThat(cache.get(new AwardAiSummaryCache.Key(
                101L, 2, "openai", "gpt-5.1",
                "award-summary-v2", "prompt-hash-1"
        ))).isEmpty();
        assertThat(cache.get(new AwardAiSummaryCache.Key(
                101L, 2, "openai", "gpt-5",
                "award-summary-v3", "prompt-hash-1"
        ))).isEmpty();
        assertThat(cache.get(new AwardAiSummaryCache.Key(
                101L, 2, "openai", "gpt-5",
                "award-summary-v2", "prompt-hash-2"
        ))).isEmpty();
    }

    @Test
    void disabledCacheNeverReturnsStoredResponses() {
        AwardAiSummaryCache cache =
                new AwardAiSummaryCache(new AiProperties());
        AwardAiSummaryCache.Key key = new AwardAiSummaryCache.Key(
                101L, 2, "openai", "gpt-5",
                "award-summary-v2", "prompt-hash"
        );

        cache.put(key, narrative());

        assertThat(cache.get(key)).isEmpty();
    }

    @Test
    void cacheValueCanContainOnlyNarrativeAndCitations() {
        assertThat(AwardAiNarrative.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly(
                        "overview",
                        "notableChanges",
                        "archiveAssessment",
                        "citations"
                );
    }

    private AwardAiNarrative narrative() {
        return new AwardAiNarrative(
                "Overview",
                List.of(),
                "Assessment",
                List.of(new AiCitation(
                        "award", "101", "A-100", 2
                ))
        );
    }
}
