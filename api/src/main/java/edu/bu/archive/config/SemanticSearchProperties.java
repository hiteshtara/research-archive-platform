package edu.bu.archive.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.search.semantic")
public class SemanticSearchProperties {

    private boolean enabled;
    private int topK = 5;
    private String embeddingModel = "amazon.titan-embed-text-v2:0";
    private int bedrockTimeoutMs = 2000;

    // Connection-attempt (TCP connect) timeout for the shared Bedrock
    // client - distinct from bedrockTimeoutMs, which bounds the whole
    // API call (including retries). Same 2000ms default as
    // bedrockTimeoutMs: generous enough for normal VPC-endpoint
    // latency, bounded enough that an authenticated UI request (Award
    // Evidence Search) can never hang indefinitely on a stalled
    // connection attempt.
    private int connectionTimeoutMs = 2000;

    // Award Evidence Search's minimum-similarity cutoff (maximum
    // cosine distance to accept), applied only to evidence-row
    // retrieval - Global Search's own semantic branch intentionally
    // applies no threshold at all (the PoC's threshold experiment found
    // no single global cutoff works across the whole archive). Evidence
    // search's candidate space is narrower (one Award's own rows), so a
    // threshold may be more viable here - but 2.0 (cosine distance's
    // practical maximum, i.e. "accept everything") is a deliberately
    // PERMISSIVE, NOT-YET-CALIBRATED default, not a guessed real cutoff
    // - per docs/architecture/AWARD_EVIDENCE_RETRIEVAL_PHASE3_DESIGN.md
    // section 5's explicit instruction not to invent a threshold number
    // by inspection. Calibrate this empirically against real indexed
    // data (mirroring the PoC's own threshold-experiment methodology)
    // before relying on it to meaningfully filter results.
    private double evidenceMaxDistance = 2.0;

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

    public int getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public void setConnectionTimeoutMs(
            int connectionTimeoutMs
    ) {
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    public double getEvidenceMaxDistance() {
        return evidenceMaxDistance;
    }

    public void setEvidenceMaxDistance(
            double evidenceMaxDistance
    ) {
        this.evidenceMaxDistance = evidenceMaxDistance;
    }
}
