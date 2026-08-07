package edu.bu.archive.adapter.out.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.bu.archive.application.port.out.EmbeddingProvider;
import edu.bu.archive.config.SemanticSearchProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.nio.charset.StandardCharsets;

/*
 * Embeds live search-query text via AWS Bedrock (Titan Text Embeddings
 * V2), the same model and request/response shape already validated by
 * the pgvector proof-of-concept (etl/build_search_embedding_poc.py's
 * embed_text). This provider only ever embeds the user's own query text
 * at request time - it never re-embeds archive records, which is done
 * asynchronously by etl/build_search_embedding.py instead.
 */
public class BedrockEmbeddingProvider implements EmbeddingProvider {

    private static final Logger LOG =
            LoggerFactory.getLogger(BedrockEmbeddingProvider.class);

    private final BedrockRuntimeClient bedrockRuntimeClient;
    private final ObjectMapper objectMapper;
    private final SemanticSearchProperties properties;

    public BedrockEmbeddingProvider(
            BedrockRuntimeClient bedrockRuntimeClient,
            ObjectMapper objectMapper,
            SemanticSearchProperties properties
    ) {
        this.bedrockRuntimeClient = bedrockRuntimeClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public float[] embed(String text) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("inputText", text);

            InvokeModelResponse response = bedrockRuntimeClient.invokeModel(
                    InvokeModelRequest.builder()
                            .modelId(properties.getEmbeddingModel())
                            .body(SdkBytes.fromUtf8String(
                                    objectMapper.writeValueAsString(requestBody)
                            ))
                            .build()
            );

            JsonNode payload = objectMapper.readTree(
                    response.body().asString(StandardCharsets.UTF_8)
            );
            ArrayNode embeddingNode = (ArrayNode) payload.get("embedding");
            float[] embedding = new float[embeddingNode.size()];
            for (int i = 0; i < embedding.length; i++) {
                embedding[i] = (float) embeddingNode.get(i).asDouble();
            }
            return embedding;
        } catch (Exception exception) {
            LOG.warn("Bedrock embedding call failed", exception);
            throw new EmbeddingProviderException(
                    "Failed to embed query text via Bedrock", exception
            );
        }
    }
}
