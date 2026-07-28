package edu.bu.archive.application.ai;

import edu.bu.archive.application.port.out.AiProvider;
import edu.bu.archive.config.AiProperties;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true")
public class AiModelRouter {

    private final AiProvider provider;

    public AiModelRouter(
            List<AiProvider> providers,
            AiProperties properties
    ) {
        provider = providers.stream()
                .filter(candidate ->
                        candidate.providerName()
                                .equalsIgnoreCase(
                                        properties.getProvider()
                                )
                )
                .findFirst()
                .orElseThrow(() ->
                        new AiProviderException(
                                "Configured AI provider is unavailable"
                        )
                );
    }

    public AiProvider provider() {
        return provider;
    }
}
