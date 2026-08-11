package edu.bu.archive.adapter.in.web.dto.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/*
 * documentTypes is optional - null/empty means "search every approved
 * evidence type" (see AwardEvidenceSearchService.APPROVED_DOCUMENT_TYPES).
 * A non-empty list is validated against that same allowlist server-side
 * - an unapproved value (including AWARD_SUMMARY/AWARD_ATTACHMENT) is
 * rejected with 400, never silently dropped or silently widened.
 *
 * topK is optional - null means the service's own default; any supplied
 * value is clamped to the hard maximum server-side, never passed through
 * raw to the repository query.
 */
public record AwardEvidenceSearchRequest(
        @NotBlank(message = "Query is required")
        @Size(
                max = 500,
                message = "Query must not exceed 500 characters"
        )
        String query,
        List<String> documentTypes,
        Integer topK
) {
    public AwardEvidenceSearchRequest {
        documentTypes = documentTypes == null
                ? List.of()
                : List.copyOf(documentTypes);
    }
}
