package edu.bu.archive.application.document;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.PaginationSupport;
import edu.bu.archive.adapter.in.web.dto.document.DocumentSearchResultResponse;
import edu.bu.archive.adapter.out.persistence.DocumentSearchRepository;
import edu.bu.archive.adapter.out.persistence.DocumentSearchRow;

import org.springframework.stereotype.Service;

import java.util.List;

/*
 * Kuali Document Search - the five approved core modules only (Award,
 * Proposal, Negotiation, Subaward, IRB), per
 * docs/architecture/KUALI_DOCUMENT_METRIC_INVESTIGATION.md. Never
 * Budget/Time-and-Money/Pending-Transaction/SAP-transmission (child
 * financial artifacts of an Award, not independent business documents)
 * and never attachments (a document links to its owning business
 * record; attachments are reached from that record, not searched here
 * directly).
 *
 * Routing is computed here, not in SQL or the UI, so the
 * module-to-route mapping lives in exactly one place:
 *   AWARD       -> /awards/{award_id}
 *   PROPOSAL    -> /proposals/{proposal_number}  (already on the same
 *                  row as document_number - no separate resolver call
 *                  needed at read time)
 *   NEGOTIATION -> /negotiations/{negotiation_id}
 *   SUBAWARD    -> /subawards/{subaward_id}
 *   IRB         -> /irb/history/{protocol_id}
 */
@Service
public class DocumentSearchService {

    private final DocumentSearchRepository repository;

    public DocumentSearchService(DocumentSearchRepository repository) {
        this.repository = repository;
    }

    public PageResponse<DocumentSearchResultResponse> search(
            String documentNumber,
            String module,
            String businessRecordNumber,
            String title,
            String status,
            int page,
            int size
    ) {
        String rawDocumentNumber = normalize(documentNumber);
        String rawBusinessRecordNumber = normalize(businessRecordNumber);
        String rawTitle = normalize(title);
        String rawStatus = normalize(status);
        String rawModule = normalizeModule(module);

        String documentNumberPattern =
                DocumentSearchPattern.toLikePattern(rawDocumentNumber);
        String businessRecordNumberPattern =
                DocumentSearchPattern.toLikePattern(rawBusinessRecordNumber);
        String titlePattern = DocumentSearchPattern.toLikePattern(rawTitle);
        String statusPattern = DocumentSearchPattern.toLikePattern(rawStatus);

        int safePage = PaginationSupport.clampPage(page);
        int safeSize = PaginationSupport.clampSize(size);
        int offset = safePage * safeSize;

        long totalElements = repository.count(
                rawDocumentNumber,
                documentNumberPattern,
                rawModule,
                rawBusinessRecordNumber,
                businessRecordNumberPattern,
                rawTitle,
                titlePattern,
                rawStatus,
                statusPattern
        );

        PaginationSupport.PageMetadata pageMetadata = PaginationSupport.metadata(
                safePage,
                safeSize,
                totalElements
        );

        List<DocumentSearchRow> rows = repository.search(
                rawDocumentNumber,
                documentNumberPattern,
                rawModule,
                rawBusinessRecordNumber,
                businessRecordNumberPattern,
                rawTitle,
                titlePattern,
                rawStatus,
                statusPattern,
                safeSize,
                offset
        );

        List<DocumentSearchResultResponse> content = rows.stream()
                .map(this::toResponse)
                .toList();

        return new PageResponse<>(
                content,
                safePage,
                safeSize,
                totalElements,
                pageMetadata.totalPages(),
                pageMetadata.first(),
                pageMetadata.last()
        );
    }

    private DocumentSearchResultResponse toResponse(DocumentSearchRow row) {
        return new DocumentSearchResultResponse(
                row.module(),
                row.documentNumber(),
                row.businessRecordNumber(),
                row.title(),
                row.status(),
                row.versionOrSequence(),
                row.relevantDate(),
                targetRoute(row.module(), row.targetId())
        );
    }

    private String targetRoute(String module, String targetId) {
        if (targetId == null) {
            return null;
        }

        return switch (module) {
            case "AWARD" -> "/awards/" + targetId;
            case "PROPOSAL" -> "/proposals/" + targetId;
            case "NEGOTIATION" -> "/negotiations/" + targetId;
            case "SUBAWARD" -> "/subawards/" + targetId;
            case "IRB" -> "/irb/history/" + targetId;
            default -> null;
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    // An unrecognized module value is never rejected with a 400 and
    // never silently widened to "match everything" - it is normalized
    // (trim + uppercase) and bound as an ordinary equality parameter
    // like any other value. Since it can never equal one of the five
    // literal module strings the fixed union actually produces, an
    // invalid module naturally yields zero results, the same
    // predictable behavior as filtering by a real module with no
    // matching rows - never a crash, never a bypassed filter. Only a
    // genuinely blank/absent module means "no filter" (see normalize()
    // - "" is reserved for that).
    private String normalizeModule(String module) {
        String trimmed = normalize(module);
        return trimmed.isEmpty() ? "" : trimmed.toUpperCase(java.util.Locale.ROOT);
    }
}
