package edu.bu.archive.application.document;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.document.DocumentSearchResultResponse;
import edu.bu.archive.adapter.out.persistence.DocumentSearchRepository;
import edu.bu.archive.adapter.out.persistence.DocumentSearchRow;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentSearchServiceTest {

    private final DocumentSearchRepository repository =
            mock(DocumentSearchRepository.class);
    private final DocumentSearchService service =
            new DocumentSearchService(repository);

    // --- Real CARB-X fixtures from
    // docs/architecture/KUALI_DOCUMENT_METRIC_INVESTIGATION.md ---

    @Test
    void awardResultRoutesToTheExistingAwardPageByAwardId() {
        when(repository.count(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1L);
        when(repository.search(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt()
        )).thenReturn(List.of(new DocumentSearchRow(
                "AWARD", "1037915", "204713-00001", "CARB-X",
                "Active", "544", LocalDate.of(2020, 1, 1), "3561610"
        )));

        PageResponse<DocumentSearchResultResponse> result =
                service.search("1037915", null, null, null, null, 0, 25);

        DocumentSearchResultResponse row = result.content().get(0);
        assertThat(row.module()).isEqualTo("AWARD");
        assertThat(row.documentNumber()).isEqualTo("1037915");
        assertThat(row.businessRecordNumber()).isEqualTo("204713-00001");
        assertThat(row.targetRoute()).isEqualTo("/awards/3561610");
    }

    @Test
    void proposalResultRoutesToTheExistingProposalPageByProposalNumberDirectly() {
        // Two real document numbers for the same proposal 01128961
        // (versions 3 and 4) - both remain distinct Kuali documents but
        // resolve to the same Proposal route, per the CARB-X trace.
        when(repository.count(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(2L);
        when(repository.search(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt()
        )).thenReturn(List.of(
                new DocumentSearchRow(
                        "PROPOSAL", "430102", "01128961", "CARB-X",
                        "Funded", "3", LocalDate.of(2019, 6, 1), "01128961"
                ),
                new DocumentSearchRow(
                        "PROPOSAL", "451704", "01128961", "CARB-X",
                        "Funded", "4", LocalDate.of(2019, 6, 1), "01128961"
                )
        ));

        PageResponse<DocumentSearchResultResponse> result =
                service.search(null, "PROPOSAL", "01128961", null, null, 0, 25);

        assertThat(result.content()).hasSize(2);
        assertThat(result.content())
                .extracting(DocumentSearchResultResponse::documentNumber)
                .containsExactly("430102", "451704");
        assertThat(result.content())
                .extracting(DocumentSearchResultResponse::targetRoute)
                .containsOnly("/proposals/01128961");
        assertThat(result.content())
                .extracting(DocumentSearchResultResponse::businessRecordNumber)
                .containsOnly("01128961");
    }

    @Test
    void negotiationResultRoutesByNegotiationId() {
        when(repository.count(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1L);
        when(repository.search(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt()
        )).thenReturn(List.of(new DocumentSearchRow(
                "NEGOTIATION", "367756", "355", null,
                "Complete", null, LocalDate.of(2021, 3, 1), "355"
        )));

        PageResponse<DocumentSearchResultResponse> result =
                service.search(null, "NEGOTIATION", null, null, null, 0, 25);

        assertThat(result.content().get(0).targetRoute()).isEqualTo("/negotiations/355");
    }

    @Test
    void subawardResultRoutesBySubawardId() {
        when(repository.count(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1L);
        when(repository.search(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt()
        )).thenReturn(List.of(new DocumentSearchRow(
                "SUBAWARD", "343156", "1363", "Subaward Title",
                "Active", "1", LocalDate.of(2018, 9, 1), "1363"
        )));

        PageResponse<DocumentSearchResultResponse> result =
                service.search(null, "SUBAWARD", null, null, null, 0, 25);

        assertThat(result.content().get(0).targetRoute()).isEqualTo("/subawards/1363");
    }

    // --- IRB: schema-ready, but this dev database currently has zero
    // rows. No fake IRB example is fabricated here - this tests the
    // routing/mapping logic in isolation via a mocked repository row,
    // the same way every other module's own routing test does; it does
    // not assert anything about real dev data. ---

    @Test
    void irbResultWouldRouteByProtocolIdIfEverPopulated() {
        when(repository.count(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1L);
        when(repository.search(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt()
        )).thenReturn(List.of(new DocumentSearchRow(
                "IRB", "999999", "PROTO-1", "Sample Protocol",
                "Approved", "1", LocalDate.of(2022, 1, 1), "42"
        )));

        PageResponse<DocumentSearchResultResponse> result =
                service.search(null, "IRB", null, null, null, 0, 25);

        assertThat(result.content().get(0).targetRoute()).isEqualTo("/irb/history/42");
    }

    @Test
    void emptyIrbBehaviorReturnsEmptyPageNotAnError() {
        when(repository.count(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0L);
        when(repository.search(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt()
        )).thenReturn(List.of());

        PageResponse<DocumentSearchResultResponse> result =
                service.search(null, "IRB", null, null, null, 0, 25);

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
    }

    // --- Filters and identity ---

    @Test
    void moduleFilterIsUppercasedBeforeBinding() {
        when(repository.count(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0L);
        when(repository.search(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt()
        )).thenReturn(List.of());

        service.search(null, "award", null, null, null, 0, 25);

        verify(repository).count(
                anyString(), anyString(), org.mockito.ArgumentMatchers.eq("AWARD"),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()
        );
    }

    @Test
    void invalidModuleIsBoundAsLiteralTextNotRejectedOrWidened() {
        // An unrecognized module can never equal one of the five fixed
        // union literals, so it naturally yields zero rows via ordinary
        // equality - never a 400, never silently matching every module.
        when(repository.count(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0L);
        when(repository.search(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt()
        )).thenReturn(List.of());

        PageResponse<DocumentSearchResultResponse> result =
                service.search(null, "NOT_A_REAL_MODULE", null, null, null, 0, 25);

        assertThat(result.content()).isEmpty();
        verify(repository).count(
                anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq("NOT_A_REAL_MODULE"),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()
        );
    }

    @Test
    void blankModuleMeansNoFilter() {
        when(repository.count(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0L);
        when(repository.search(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt()
        )).thenReturn(List.of());

        service.search(null, "  ", null, null, null, 0, 25);

        verify(repository).count(
                anyString(), anyString(), org.mockito.ArgumentMatchers.eq(""),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()
        );
    }

    @Test
    void sqlInjectionLikeInputIsPassedThroughAsOrdinaryBoundText() {
        when(repository.count(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0L);
        when(repository.search(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt()
        )).thenReturn(List.of());

        String maliciousInput = "'; DROP TABLE archive.award_version; --";

        service.search(maliciousInput, null, null, null, null, 0, 25);

        // The raw value is bound as a literal filter value, never
        // interpolated into SQL text - DocumentSearchRepository always
        // uses .param(...) for every filter. This test proves the
        // service passes the string through unmodified (trimmed only),
        // not stripped/escaped/rejected - safety comes from parameter
        // binding, not input sanitization.
        verify(repository).count(
                org.mockito.ArgumentMatchers.eq(maliciousInput),
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString()
        );
    }

    // --- Pagination ---

    @Test
    void paginationClampsAndComputesOffset() {
        when(repository.count(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(120L);
        when(repository.search(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt()
        )).thenReturn(List.of());

        PageResponse<DocumentSearchResultResponse> result =
                service.search(null, null, null, null, null, 2, 25);

        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(25);
        assertThat(result.totalElements()).isEqualTo(120L);
        assertThat(result.totalPages()).isEqualTo(5);
        verify(repository).search(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq(25),
                org.mockito.ArgumentMatchers.eq(50)
        );
    }
}
