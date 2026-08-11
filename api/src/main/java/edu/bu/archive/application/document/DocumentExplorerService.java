package edu.bu.archive.application.document;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.PaginationSupport;
import edu.bu.archive.adapter.in.web.dto.document.DocumentExplorerResponse;
import edu.bu.archive.adapter.in.web.dto.document.DocumentExplorerResultResponse;
import edu.bu.archive.adapter.in.web.dto.document.FacetCountResponse;
import edu.bu.archive.adapter.out.persistence.DocumentExplorerFilter;
import edu.bu.archive.adapter.out.persistence.DocumentExplorerRepository;
import edu.bu.archive.adapter.out.persistence.DocumentExplorerRow;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/*
 * Kuali Document Explorer - the four approved core modules (Award,
 * Proposal, Negotiation, Subaward). IRB is deliberately absent from
 * every allowlist and every count in this class - see
 * docs/architecture/KUALI_DOCUMENT_EXPLORER_DESIGN.md §16 decision 7.
 *
 * Unlike the simpler DocumentSearchService (which silently treats an
 * unrecognized module as "matches nothing"), this endpoint's module/
 * normalizedStatus/sort filters are validated against fixed allowlists
 * and REJECTED (IllegalArgumentException -> 400 via the app-wide
 * GlobalExceptionHandler) when invalid - the approved design calls for
 * "exact-match status filtering" and a richer, stricter contract than
 * the simpler search endpoint.
 */
@Service
public class DocumentExplorerService {

    static final Set<String> APPROVED_MODULES =
            Set.of("AWARD", "PROPOSAL", "NEGOTIATION", "SUBAWARD");

    static final Set<String> APPROVED_NORMALIZED_STATUSES =
            Set.of("ACTIVE", "PENDING", "ARCHIVED", "CANCELLED", "CLOSED", "UNKNOWN");

    static final Set<String> APPROVED_SORTS =
            Set.of("documentNumber", "title", "documentDate", "module");

    private final DocumentExplorerRepository repository;

    public DocumentExplorerService(DocumentExplorerRepository repository) {
        this.repository = repository;
    }

    public DocumentExplorerResponse search(
            String documentNumber,
            String businessRecordNumber,
            String query,
            String module,
            String normalizedStatus,
            String nativeStatus,
            String unitNumber,
            boolean includeAnyUnit,
            String personId,
            String personName,
            String personRole,
            boolean piOnly,
            String sponsorCode,
            String subrecipientOrganizationId,
            LocalDate dateFrom,
            LocalDate dateTo,
            String sort,
            int page,
            int size
    ) {
        DocumentExplorerFilter filter = new DocumentExplorerFilter(
                normalize(documentNumber),
                normalize(businessRecordNumber),
                normalize(query),
                validateModule(module),
                validateNormalizedStatus(normalizedStatus),
                normalize(nativeStatus),
                normalize(unitNumber),
                includeAnyUnit,
                normalize(personId),
                normalize(personName),
                normalize(personRole),
                piOnly,
                normalize(sponsorCode),
                normalize(subrecipientOrganizationId),
                dateFrom,
                dateTo,
                validateSort(sort)
        );

        int safePage = PaginationSupport.clampPage(page);
        int safeSize = PaginationSupport.clampSize(size);
        int offset = safePage * safeSize;

        long totalElements = repository.count(filter);
        PaginationSupport.PageMetadata pageMetadata =
                PaginationSupport.metadata(safePage, safeSize, totalElements);

        List<DocumentExplorerRow> rows = repository.search(filter, safeSize, offset);
        List<DocumentExplorerResultResponse> content = rows.stream()
                .map(this::toResponse)
                .toList();

        PageResponse<DocumentExplorerResultResponse> results = new PageResponse<>(
                content, safePage, safeSize, totalElements,
                pageMetadata.totalPages(), pageMetadata.first(), pageMetadata.last()
        );

        List<FacetCountResponse> moduleFacets = repository.moduleFacets(filter).stream()
                .map(row -> new FacetCountResponse(row.module(), row.n()))
                .toList();

        return new DocumentExplorerResponse(results, moduleFacets);
    }

    private DocumentExplorerResultResponse toResponse(DocumentExplorerRow row) {
        return new DocumentExplorerResultResponse(
                row.module(),
                row.documentNumber(),
                row.businessRecordNumber(),
                row.title(),
                row.normalizedStatus(),
                row.nativeStatusCode(),
                row.nativeStatusDescription(),
                row.versionOrSequence(),
                row.leadUnitNumber(),
                row.leadUnitName(),
                row.primaryPersonId(),
                row.primaryPersonName(),
                row.primaryPersonRole(),
                row.sponsorCode(),
                row.sponsorName(),
                row.subrecipientOrganizationId(),
                row.subrecipientOrganizationName(),
                row.documentDate(),
                targetRoute(row.module(), row.targetId()),
                row.unitCount(),
                row.personCount(),
                row.sponsorCount()
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
            default -> null;
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String validateModule(String module) {
        String normalized = normalize(module);
        if (normalized.isEmpty()) {
            return "";
        }
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (!APPROVED_MODULES.contains(upper)) {
            throw new IllegalArgumentException(
                    "Not an approved module: " + module
                            + ". Approved modules: " + APPROVED_MODULES
            );
        }
        return upper;
    }

    private String validateNormalizedStatus(String normalizedStatus) {
        String normalized = normalize(normalizedStatus);
        if (normalized.isEmpty()) {
            return "";
        }
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (!APPROVED_NORMALIZED_STATUSES.contains(upper)) {
            throw new IllegalArgumentException(
                    "Not an approved normalized status: " + normalizedStatus
                            + ". Approved values: " + APPROVED_NORMALIZED_STATUSES
            );
        }
        return upper;
    }

    private String validateSort(String sort) {
        String normalized = normalize(sort);
        if (normalized.isEmpty()) {
            return "documentNumber";
        }
        if (!APPROVED_SORTS.contains(normalized)) {
            throw new IllegalArgumentException(
                    "Not an approved sort field: " + sort
                            + ". Approved values: " + APPROVED_SORTS
            );
        }
        return normalized;
    }
}
