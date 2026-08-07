package edu.bu.archive.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.search.semantic")
public class SemanticSearchProperties {

    private boolean enabled;
    private int topK = 5;
    private String embeddingModel = "amazon.titan-embed-text-v2:0";
    private int bedrockTimeoutMs = 2000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(
            boolean enabled
    ) {
        this.enabled = enabled;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(
            int topK
    ) {
        this.topK = topK;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(
            String embeddingModel
    ) {
        this.embeddingModel = embeddingModel;
    }

    public int getBedrockTimeoutMs() {
        return bedrockTimeoutMs;
    }

    public void setBedrockTimeoutMs(
            int bedrockTimeoutMs
    ) {
        this.bedrockTimeoutMs = bedrockTimeoutMs;
    }
}
