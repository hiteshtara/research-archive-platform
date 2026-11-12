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
    private final edu.bu.archive.application.authorization.AwardRecordAccess recordAccess;

    public DocumentExplorerService(DocumentExplorerRepository repository,
            org.springframework.beans.factory.ObjectProvider<
                    edu.bu.archive.application.authorization.AwardRecordAccess> recordAccessProvider,
            org.springframework.beans.factory.ObjectProvider<
                    edu.bu.archive.application.authorization.AuthorizationProperties>
                    authorizationProperties
    ) {
        edu.bu.archive.application.authorization.AwardRecordAccess resolvedAccess =
                recordAccessProvider == null ? null : recordAccessProvider.getIfAvailable();
        this.recordAccess = resolvedAccess != null
                ? resolvedAccess
                : edu.bu.archive.application.authorization.AwardRecordAccess.PERMIT_ALL;
        edu.bu.archive.application.authorization.AwardRecordAccess
                .requireEnforceableWiring(this.recordAccess, authorizationProperties);
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
        // Cross-module index: Award rows are UNIONed with four modules that
        // have no approved record rule, so this is refused for a scoped
        // caller rather than returning Award rows outside their scope.
        recordAccess.requireUnrestrictedForCrossModuleSearch("Document explorer");

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

        boolean unfiltered = isUnfiltered(filter);
        boolean lightFiltered = !unfiltered && canUseLightFilteredPath(filter);

        /*
         * DEF-5. The per-module facet counts are read FIRST and the
         * response's totalElements is derived by summing them, instead
         * of issuing a separate COUNT(*) over the same union under the
         * same WHERE. COUNT(*) over a predicate is exactly the sum of
         * that predicate's per-group counts, so the number is
         * unchanged - but a filtered request now evaluates the union
         * twice (facets + page) rather than three times
         * (count + page + facets). A module that matches nothing
         * contributes no GROUP BY row, which is a contribution of
         * zero, so the sum is still the full total.
         */
        List<DocumentExplorerRepository.ModuleFacetRow> facetRows = unfiltered
                ? repository.moduleFacetsDefault()
                : lightFiltered
                        ? repository.moduleFacetsFilteredLight(filter)
                        : repository.moduleFacets(filter);

        long totalElements = facetRows.stream()
                .mapToLong(DocumentExplorerRepository.ModuleFacetRow::n)
                .sum();
        PaginationSupport.PageMetadata pageMetadata =
                PaginationSupport.metadata(safePage, safeSize, totalElements);

        List<DocumentExplorerRow> rows = unfiltered
                ? repository.searchDefaultPage(safeSize, offset, filter.sort())
                : lightFiltered
                        ? repository.searchFilteredLight(filter, safeSize, offset)
                        : repository.search(filter, safeSize, offset);
        List<DocumentExplorerResultResponse> content = rows.stream()
                .map(this::toResponse)
                .toList();

        PageResponse<DocumentExplorerResultResponse> results = new PageResponse<>(
                content, safePage, safeSize, totalElements,
                pageMetadata.totalPages(), pageMetadata.first(), pageMetadata.last()
        );

        List<FacetCountResponse> moduleFacets = facetRows.stream()
                .map(row -> new FacetCountResponse(row.module(), row.n()))
                .toList();

        return new DocumentExplorerResponse(results, moduleFacets);
    }

    /*
     * The default-page fast path (DocumentExplorerRepository's
     * countDefault()/moduleFacetsDefault()/searchDefaultPage()) is only
     * correct when NOTHING is being filtered - it skips the
     * person/unit-aware joins entirely, so any filter that depends on
     * them (or any other filter at all) must fall back to the original
     * count()/search()/moduleFacets(). sort/page/size are not filters -
     * every approved sort value is available directly on the light
     * union without the expensive joins, so the fast path applies
     * regardless of which one is requested.
     */
    private boolean isUnfiltered(DocumentExplorerFilter filter) {
        return filter.documentNumber().isEmpty()
                && filter.businessRecordNumber().isEmpty()
                && filter.query().isEmpty()
                && filter.module().isEmpty()
                && filter.normalizedStatus().isEmpty()
                && filter.nativeStatus().isEmpty()
                && filter.unitNumber().isEmpty()
                && !filter.includeAnyUnit()
                && filter.personId().isEmpty()
                && filter.personName().isEmpty()
                && filter.personRole().isEmpty()
                && !filter.piOnly()
                && filter.sponsorCode().isEmpty()
                && filter.subrecipientOrganizationId().isEmpty()
                && filter.dateFrom() == null
                && filter.dateTo() == null;
    }

    /*
     * DEF-5. Whether a FILTERED request can use the light union
     * (DocumentExplorerRepository's moduleFacetsFilteredLight()/
     * searchFilteredLight()) instead of the heavy EXPLORER_CTE.
     *
     * The heavy CTE's expense is Award's person/unit resolution:
     * award_pi (a DISTINCT ON over archive.award_person, 325K rows),
     * award_unit_counts and award_person_counts (GROUP BY over
     * award_person_unit, 334K rows, and award_person again), plus
     * award_any_units - all computed for every one of the 267,386
     * archive.award_version rows before any filter or LIMIT applies.
     *
     * Those columns are needed up front only when a filter actually
     * MATCHES on them. For display they are needed for at most `size`
     * rows, which the two-stage light path already resolves by
     * award_id through the existing indexes. So the light path is
     * correct for any filter that does not read a person column or
     * widen a unit match across award_person_unit:
     *
     *   personId / personName / personRole -> primary_person_*
     *   piOnly                             -> primary_person_role
     *   unitNumber WITH includeAnyUnit     -> award_any_units
     *
     * Everything else (query, documentNumber, businessRecordNumber,
     * module, normalizedStatus, nativeStatus, sponsorCode,
     * subrecipientOrganizationId, dateFrom, dateTo, and unitNumber on
     * its own) matches only columns the light union carries natively
     * for all four modules.
     *
     * A unitNumber WITHOUT includeAnyUnit stays eligible: it reduces to
     * lead_unit_number equality, which the light union does carry.
     */
    private boolean canUseLightFilteredPath(DocumentExplorerFilter filter) {
        return filter.personId().isEmpty()
                && filter.personName().isEmpty()
                && filter.personRole().isEmpty()
                && !filter.piOnly()
                && !(filter.includeAnyUnit() && !filter.unitNumber().isEmpty());
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
            // The version dashboard, addressed by proposal_id, so a
            // document row opens the version it names. "/proposals/{number}"
            // is the FAMILY route and redirects to the current version,
            // which is what made every version open as version 4.
            case "PROPOSAL" -> "/proposals/dashboard/" + targetId;
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
