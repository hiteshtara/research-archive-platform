package edu.bu.archive.application.ai;

import edu.bu.archive.adapter.in.web.dto.ai.AwardEvidenceResultResponse;
import edu.bu.archive.adapter.in.web.dto.ai.AwardEvidenceSearchResponse;
import edu.bu.archive.adapter.out.persistence.AwardEvidenceRetrievalRepository;
import edu.bu.archive.adapter.out.persistence.AwardEvidenceRow;
import edu.bu.archive.application.award.AwardArchiveService;
import edu.bu.archive.application.port.out.EmbeddingProvider;
import edu.bu.archive.config.SemanticSearchProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/*
 * Gated the same way as AwardContextBuilder/AwardCitationValidator are
 * gated on app.ai.enabled - here gated on app.search.semantic.enabled
 * instead, deliberately decoupled from the AI Summary/Questions feature
 * flag (which is off in dev today). See
 * docs/architecture/AWARD_EVIDENCE_RETRIEVAL_PHASE3_DESIGN.md section
 * 3.1/5. Since this bean only exists when the flag is on, EmbeddingProvider
 * (also only registered when the flag is on, per
 * SemanticSearchConfiguration) can be injected directly via the
 * constructor - no ObjectProvider indirection needed, unlike
 * GlobalSearchService, which stays always-present for its 5 other
 * domains and therefore needs ObjectProvider for its one optional
 * semantic branch.
 *
 * Reuses AwardEvidenceRetrievalRepository (new, plain @Repository, no
 * conditional gating - mirrors SemanticSearchRepository's own
 * ungated-repository convention) and SensitiveFieldRedactor
 * (deliberately unconditional as of this change - see that class's own
 * comment) for the centralized redaction implementation, per Decision 1.
 */
@Service
@ConditionalOnProperty(
        name = "app.search.semantic.enabled",
        havingValue = "true"
)
public class AwardEvidenceSearchService {

    // Mirrors etl/build_evidence_embedding.py's own
    // APPROVED_DOCUMENT_TYPES tuple exactly - AWARD_SUMMARY (owned by
    // build_search_embedding.py) and AWARD_ATTACHMENT (attachment
    // content is explicitly out of scope for evidence RAG) are
    // deliberately absent, not merely unlisted.
    static final List<String> APPROVED_DOCUMENT_TYPES = List.of(
            "AWARD_VERSION", "AWARD_PERSON", "AWARD_AMOUNT", "AWARD_TERM",
            "AWARD_COMMENT", "RELATED_PROPOSAL", "RELATED_NEGOTIATION",
            "RELATED_SUBAWARD"
    );

    static final int DEFAULT_TOP_K = 8;
    static final int MAX_TOP_K = 20;
    static final int MAX_EXCERPT_LENGTH = 300;

    private static final Map<String, String> TARGET_SECTION_BY_TYPE =
            Map.ofEntries(
                    Map.entry("AWARD_VERSION", "versions"),
                    Map.entry("AWARD_PERSON", "people"),
                    Map.entry("AWARD_AMOUNT", "amounts"),
                    Map.entry("AWARD_TERM", "terms"),
                    Map.entry("AWARD_COMMENT", "comments"),
                    Map.entry("RELATED_PROPOSAL", "fundingProposals"),
                    Map.entry("RELATED_NEGOTIATION", "negotiations"),
                    Map.entry("RELATED_SUBAWARD", "fundingSubawards")
            );

    private static final Map<String, String> TITLE_BY_TYPE = Map.ofEntries(
            Map.entry("AWARD_VERSION", "Award Version"),
            Map.entry("AWARD_PERSON", "Investigator or Person"),
            Map.entry("AWARD_AMOUNT", "Funding Amount"),
            Map.entry("AWARD_TERM", "Term"),
            Map.entry("AWARD_COMMENT", "Award Comment"),
            Map.entry("RELATED_PROPOSAL", "Related Proposal"),
            Map.entry("RELATED_NEGOTIATION", "Related Negotiation"),
            Map.entry("RELATED_SUBAWARD", "Related Subaward")
    );

    private final AwardArchiveService awardArchiveService;
    private final AwardEvidenceRetrievalRepository repository;
    private final EmbeddingProvider embeddingProvider;
    private final SensitiveFieldRedactor redactor;
    private final SemanticSearchProperties properties;

    public AwardEvidenceSearchService(
            AwardArchiveService awardArchiveService,
            AwardEvidenceRetrievalRepository repository,
            EmbeddingProvider embeddingProvider,
            SensitiveFieldRedactor redactor,
            SemanticSearchProperties properties
    ) {
        this.awardArchiveService = awardArchiveService;
        this.repository = repository;
        this.embeddingProvider = embeddingProvider;
        this.redactor = redactor;
        this.properties = properties;
    }

    public AwardEvidenceSearchResponse search(
            String awardNumber,
            String query,
            List<String> requestedDocumentTypes,
            Integer requestedTopK
    ) {
        UUID correlationId = UUID.randomUUID();

        try {
            List<String> documentTypes =
                    resolveDocumentTypes(requestedDocumentTypes);
            int topK = resolveTopK(requestedTopK);

            // Resolves and normalizes the Award, throwing
            // NoSuchElementException on a missing Award - the same
            // convention every other Award sub-resource endpoint uses
            // (AwardAiController/AwardAiQuestionController included).
            String normalizedAwardNumber =
                    awardArchiveService.findFamily(awardNumber)
                            .awardNumber();

            // Never logs the raw query text or the embedding vector -
            // mirrors BedrockEmbeddingProvider's own logging
            // convention.
            float[] queryEmbedding = embeddingProvider.embed(query);

            List<AwardEvidenceRow> rows = repository.findNearestEvidence(
                    normalizedAwardNumber,
                    documentTypes,
                    queryEmbedding,
                    properties.getEvidenceMaxDistance(),
                    topK
            );

            List<AwardEvidenceResultResponse> results = rows.stream()
                    .map(this::toResult)
                    .toList();

            return new AwardEvidenceSearchResponse(
                    query,
                    normalizedAwardNumber,
                    results,
                    results.isEmpty(),
                    correlationId.toString()
            );
        } catch (RuntimeException exception) {
            throw new AwardEvidenceSearchException(
                    correlationId, exception
            );
        }
    }

    private List<String> resolveDocumentTypes(
            List<String> requested
    ) {
        if (requested == null || requested.isEmpty()) {
            return APPROVED_DOCUMENT_TYPES;
        }

        // Deduplicates while preserving the caller's own ordering
        // intent (not that ordering is meaningful for an IN-list, but
        // it keeps the validation error message's offending-value order
        // predictable in tests).
        Set<String> requestedSet = new LinkedHashSet<>(requested);
        Set<String> approved = new LinkedHashSet<>(APPROVED_DOCUMENT_TYPES);

        for (String type : requestedSet) {
            if (!approved.contains(type)) {
                throw new IllegalArgumentException(
                        "Not an approved evidence type: " + type
                );
            }
        }

        return List.copyOf(requestedSet);
    }

    private int resolveTopK(Integer requested) {
        if (requested == null) {
            return DEFAULT_TOP_K;
        }
        // Clamped, never rejected - mirrors GlobalSearchService's own
        // hard Top-5 cap "regardless of what's requested" convention
        // for its semantic branch.
        return Math.max(1, Math.min(requested, MAX_TOP_K));
    }

    private AwardEvidenceResultResponse toResult(AwardEvidenceRow row) {
        String redactedExcerpt = redactor.redact(row.sourceText());
        String truncatedExcerpt = truncate(
                redactedExcerpt, MAX_EXCERPT_LENGTH
        );
        double score = Math.max(0.0, 1.0 - row.distance());

        return new AwardEvidenceResultResponse(
                row.documentType(),
                row.awardNumber(),
                TITLE_BY_TYPE.getOrDefault(row.documentType(), row.documentType()),
                truncatedExcerpt,
                row.sourceTable(),
                Long.toString(row.sourcePrimaryKey()),
                score,
                TARGET_SECTION_BY_TYPE.getOrDefault(row.documentType(), "summary")
        );
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength).stripTrailing() + "…";
    }
}
