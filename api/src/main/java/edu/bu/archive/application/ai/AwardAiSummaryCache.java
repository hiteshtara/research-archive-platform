package edu.bu.archive.application.ai;

import edu.bu.archive.config.AiProperties;
import edu.bu.archive.domain.model.ai.AwardAiNarrative;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true")
public class AwardAiSummaryCache {

    private final boolean enabled;
    private final int maxEntries;
    private final Map<Key, AwardAiNarrative> narratives;

    public AwardAiSummaryCache(
            AiProperties properties
    ) {
        enabled = properties.isCacheEnabled();
        maxEntries = properties.getCacheMaxEntries();
        if (enabled && maxEntries < 1) {
            throw new IllegalStateException(
                    "AI cache max entries must be positive"
            );
        }
        narratives = java.util.Collections.synchronizedMap(
                new LinkedHashMap<>(16, 0.75F, true) {
                    @Override
                    protected boolean removeEldestEntry(
                            Map.Entry<Key, AwardAiNarrative> eldest
                    ) {
                        return size() > maxEntries;
                    }
                }
        );
    }

    public Optional<AwardAiNarrative> get(
            Key key
    ) {
        return enabled
                ? Optional.ofNullable(narratives.get(key))
                : Optional.empty();
    }

    public void put(
            Key key,
            AwardAiNarrative narrative
    ) {
        if (enabled) {
            narratives.put(key, narrative);
        }
    }

    public record Key(
            Long awardId,
            Integer latestSequence,
            String provider,
            String model,
            String promptVersion,
            String promptHash
    ) {
    }
}
