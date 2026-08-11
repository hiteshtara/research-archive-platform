package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.document.DocumentSearchResultResponse;
import edu.bu.archive.application.document.DocumentSearchService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/*
 * Kuali Document Search - read-only search across the five approved
 * core business-record modules (Award, Proposal, Negotiation, Subaward,
 * IRB), per docs/architecture/KUALI_DOCUMENT_METRIC_INVESTIGATION.md.
 * Authenticated via the same global Cognito filter chain every other
 * /api/** route uses (SecurityConfiguration) - no extra wiring needed
 * and no feature flag; this is an always-on capability, not a dev-only
 * tool like ExplorerController.
 */
@RestController
@RequestMapping("/api/documents")
@Validated
@Tag(
        name = "Documents",
        description = "Read-only search across archived Kuali workflow "
                + "documents (Award, Proposal, Negotiation, Subaward, "
                + "IRB)."
)
public class DocumentSearchController {

    private final DocumentSearchService service;

    public DocumentSearchController(DocumentSearchService service) {
        this.service = service;
    }

    @Operation(
            summary = "Search Kuali documents",
            description = "Filters (all optional, ANDed together): "
                    + "document number, module, business-record number, "
                    + "title, status. Each text filter matches a "
                    + "substring by default (partial search) and "
                    + "resolves to exactly the matching business record "
                    + "when the full document number is supplied."
    )
    @ApiResponse(responseCode = "200", description = "A page of matching Kuali documents.")
    @ApiResponse(responseCode = "400", description = "page/size out of range.")
    @GetMapping("/search")
    public ResponseEntity<PageResponse<DocumentSearchResultResponse>> search(
            @Parameter(description = "Document number filter. Supports *wildcard* syntax; defaults to a substring match.")
            @RequestParam(required = false)
            String documentNumber,

            @Parameter(description = "Module filter: AWARD, PROPOSAL, NEGOTIATION, SUBAWARD, or IRB. Omit to search all modules.")
            @RequestParam(required = false)
            String module,

            @Parameter(description = "Business-record number filter (award number, proposal number, negotiation ID, subaward code, or protocol number).")
            @RequestParam(required = false)
            String businessRecordNumber,

            @Parameter(description = "Title filter. Supports *wildcard* syntax; defaults to a substring match.")
            @RequestParam(required = false)
            String title,

            @Parameter(description = "Status filter. Supports *wildcard* syntax; defaults to a substring match.")
            @RequestParam(required = false)
            String status,

            @Parameter(description = "Zero-based page index.")
            @RequestParam(defaultValue = "0")
            @Min(0)
            int page,

            @Parameter(description = "Page size, 1-100.")
            @RequestParam(defaultValue = "25")
            @Min(1)
            @Max(100)
            int size
    ) {
        return ResponseEntity.ok(
                service.search(
                        documentNumber,
                        module,
                        businessRecordNumber,
                        title,
                        status,
                        page,
                        size
                )
        );
    }
}
