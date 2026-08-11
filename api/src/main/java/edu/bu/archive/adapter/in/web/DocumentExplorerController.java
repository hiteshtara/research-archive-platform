package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.document.DocumentExplorerResponse;
import edu.bu.archive.application.document.DocumentExplorerService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/*
 * Kuali Document Explorer - deterministic SQL search across the four
 * approved core modules (Award, Proposal, Negotiation, Subaward). No
 * AI/Bedrock/Titan/pgvector/embedding/RAG involvement anywhere in this
 * feature. Same global Cognito auth every /api/** route uses, no
 * feature flag - an always-on capability, mirroring
 * DocumentSearchController exactly.
 */
@RestController
@RequestMapping("/api/v1/documents")
@Validated
@Tag(
        name = "Document Explorer",
        description = "Read-only, faceted search across archived Kuali "
                + "workflow documents (Award, Proposal, Negotiation, "
                + "Subaward). Entirely deterministic SQL - no AI model "
                + "involvement."
)
public class DocumentExplorerController {

    private final DocumentExplorerService service;

    public DocumentExplorerController(DocumentExplorerService service) {
        this.service = service;
    }

    @Operation(
            summary = "Search Kuali documents with facets",
            description = "Filters are all optional and ANDed together. "
                    + "module/normalizedStatus/sort are validated "
                    + "against fixed allowlists and rejected (400) if "
                    + "invalid. Native status uses exact matching, "
                    + "never a substring scan."
    )
    @ApiResponse(responseCode = "200", description = "A page of matching documents plus module facet counts.")
    @ApiResponse(responseCode = "400", description = "Invalid module/normalizedStatus/sort, or page/size out of range.")
    @GetMapping
    public ResponseEntity<DocumentExplorerResponse> search(
            @RequestParam(required = false) String documentNumber,
            @RequestParam(required = false) String businessRecordNumber,
            @Parameter(description = "Free-text substring across document number, title, and business-record number.")
            @RequestParam(required = false) String query,
            @Parameter(description = "AWARD, PROPOSAL, NEGOTIATION, or SUBAWARD.")
            @RequestParam(required = false) String module,
            @Parameter(description = "ACTIVE, PENDING, ARCHIVED, CANCELLED, CLOSED, or UNKNOWN.")
            @RequestParam(required = false) String normalizedStatus,
            @Parameter(description = "Exact native status text, e.g. \"Approved Award\".")
            @RequestParam(required = false) String nativeStatus,
            @RequestParam(required = false) String unitNumber,
            @Parameter(description = "false (default) = lead unit only; true = also match any associated unit.")
            @RequestParam(defaultValue = "false") boolean includeAnyUnit,
            @RequestParam(required = false) String personId,
            @RequestParam(required = false) String personName,
            @RequestParam(required = false) String personRole,
            @RequestParam(defaultValue = "false") boolean piOnly,
            @RequestParam(required = false) String sponsorCode,
            @RequestParam(required = false) String subrecipientOrganizationId,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @RequestParam(required = false) LocalDate dateFrom,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @RequestParam(required = false) LocalDate dateTo,
            @Parameter(description = "documentNumber (default), title, documentDate, or module.")
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size
    ) {
        return ResponseEntity.ok(
                service.search(
                        documentNumber, businessRecordNumber, query, module,
                        normalizedStatus, nativeStatus, unitNumber, includeAnyUnit,
                        personId, personName, personRole, piOnly, sponsorCode,
                        subrecipientOrganizationId, dateFrom, dateTo, sort, page, size
                )
        );
    }
}
