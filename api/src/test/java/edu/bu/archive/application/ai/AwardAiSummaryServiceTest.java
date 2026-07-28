package edu.bu.archive.application.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.bu.archive.adapter.in.web.dto.award.AwardFamilyResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardRowResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSequenceResponse;
import edu.bu.archive.adapter.out.persistence.AwardArchiveRepository;
import edu.bu.archive.application.award.AwardArchiveService;
import edu.bu.archive.application.port.out.AiProvider;
import edu.bu.archive.config.AiProperties;
import edu.bu.archive.domain.model.ai.AiCitation;
import edu.bu.archive.domain.model.ai.AiRequest;
import edu.bu.archive.domain.model.ai.AiResponse;
import edu.bu.archive.domain.model.ai.AwardAiSummaryResult;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AwardAiSummaryServiceTest {

    private AwardArchiveService awardArchiveService;
    private AiProvider provider;
    private AiMetadataLogger metadataLogger;
    private AwardAiSummaryService service;

    @BeforeEach
    void setUp() {
        awardArchiveService = mock(AwardArchiveService.class);
        provider = mock(AiProvider.class);
        metadataLogger = mock(AiMetadataLogger.class);

        when(provider.providerName()).thenReturn("stub");
        when(provider.modelName())
                .thenReturn("deterministic-award-summary-v1");

        AiProperties properties = new AiProperties();
        properties.setProvider("stub");
        AiModelRouter router = new AiModelRouter(
                List.of(provider),
                properties
        );
        service = new AwardAiSummaryService(
                awardArchiveService,
                new AwardContextBuilder(
                        new SensitiveFieldRedactor(),
                        new ObjectMapper().findAndRegisterModules(),
                        properties
                ),
                router,
                metadataLogger,
                Clock.fixed(
                        Instant.parse("2026-07-28T12:00:00Z"),
                        ZoneOffset.UTC
                )
        );
    }

    @Test
    void usesHyphenatedAwardFamilyWithoutPhysicalIdResolution() {
        when(awardArchiveService.findFamily("A-100"))
                .thenReturn(family());
        when(provider.generate(any()))
                .thenReturn(validResponse("101"));

        AwardAiSummaryResult result =
                service.summarize(" A-100 ", "user-subject");

        ArgumentCaptor<AiRequest> request =
                ArgumentCaptor.forClass(AiRequest.class);
        verify(provider).generate(request.capture());
        verify(awardArchiveService).findFamily("A-100");
        assertThat(AwardArchiveRepository.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .doesNotContain("findAwardNumberById");

        assertThat(result.response().summary())
                .isEqualTo("Safe summary");
        assertThat(request.getValue().awardContext().records())
                .hasSize(1);
        assertThat(request.getValue().awardContext().records().getFirst())
                .satisfies(record -> {
                    assertThat(record.awardId()).isEqualTo(101L);
                    assertThat(record.title())
                            .doesNotContain("person@example.edu")
                            .doesNotContain("TOP-SECRET")
                            .doesNotContain("jdbc:postgresql");
                });
        assertThat(request.getValue().toString())
                .doesNotContain("ACCOUNT-SECRET")
                .doesNotContain("SPONSOR-AWARD-SECRET")
                .doesNotContain("person@example.edu")
                .doesNotContain("TOP-SECRET")
                .doesNotContain("jdbc:postgresql");

        verify(metadataLogger).log(
                eq(result.correlationId()),
                eq("user-subject"),
                eq("AWARD"),
                eq("A-100"),
                eq("stub"),
                eq("deterministic-award-summary-v1"),
                eq(0L),
                eq("SUCCESS")
        );
    }

    @Test
    void rejectsFabricatedCitationsAndLogsSafeFailure() {
        when(awardArchiveService.findFamily("A-100"))
                .thenReturn(family());
        when(provider.generate(any()))
                .thenReturn(validResponse("999"));

        AiSummaryExecutionException exception =
                catchThrowableOfType(
                        AiSummaryExecutionException.class,
                        () -> service.summarize(
                                "A-100",
                                "user-subject"
                        )
                );

        assertThat(exception)
                .hasCauseInstanceOf(AiProviderException.class);

        ArgumentCaptor<java.util.UUID> correlationId =
                ArgumentCaptor.forClass(java.util.UUID.class);
        verify(metadataLogger).log(
                correlationId.capture(),
                eq("user-subject"),
                eq("AWARD"),
                eq("A-100"),
                eq("stub"),
                eq("deterministic-award-summary-v1"),
                eq(0L),
                eq("SAFE_FAILURE")
        );
        assertThat(correlationId.getValue())
                .isEqualTo(exception.correlationId());
    }

    @Test
    void acceptsAndCanonicalizesGptCitationPresentation() {
        when(awardArchiveService.findFamily("A-100"))
                .thenReturn(family());
        when(provider.generate(any()))
                .thenReturn(responseWithCitations(
                        List.of(new AiCitation(
                                " Award ",
                                " 101 ",
                                " A-100 ",
                                1
                        ))
                ));

        AwardAiSummaryResult result =
                service.summarize("A-100", "user-subject");

        assertThat(result.response().citations())
                .containsExactly(new AiCitation(
                        "award",
                        "101",
                        "A-100",
                        1
                ));
    }

    @Test
    void rejectsCitationWithMismatchedSequence() {
        when(awardArchiveService.findFamily("A-100"))
                .thenReturn(family());
        when(provider.generate(any()))
                .thenReturn(responseWithCitations(
                        List.of(new AiCitation(
                                "award",
                                "101",
                                "A-100",
                                2
                        ))
                ));

        assertThatThrownBy(() ->
                service.summarize("A-100", "user-subject")
        )
                .isInstanceOf(AiSummaryExecutionException.class)
                .hasCauseInstanceOf(AiProviderException.class)
                .hasMessage(
                        "AI provider returned an unsupported citation"
                );
    }

    @Test
    void rejectsResponseWithMissingCitations() {
        when(awardArchiveService.findFamily("A-100"))
                .thenReturn(family());
        when(provider.generate(any()))
                .thenReturn(responseWithCitations(List.of()));

        assertThatThrownBy(() ->
                service.summarize("A-100", "user-subject")
        )
                .isInstanceOf(AiSummaryExecutionException.class)
                .hasCauseInstanceOf(AiProviderException.class)
                .hasMessage(
                        "AI provider returned an invalid response"
                );
    }

    @Test
    void rejectsUnsupportedCitationType() {
        when(awardArchiveService.findFamily("A-100"))
                .thenReturn(family());
        when(provider.generate(any()))
                .thenReturn(responseWithCitations(
                        List.of(new AiCitation(
                                "proposal",
                                "101",
                                "A-100",
                                1
                        ))
                ));

        assertThatThrownBy(() ->
                service.summarize("A-100", "user-subject")
        )
                .isInstanceOf(AiSummaryExecutionException.class)
                .hasCauseInstanceOf(AiProviderException.class)
                .hasMessage(
                        "AI provider returned an unsupported citation"
                );
    }

    @Test
    void missingAwardNeverCallsTheProvider() {
        when(awardArchiveService.findFamily("UNKNOWN"))
                .thenThrow(new NoSuchElementException(
                        "Award not found: UNKNOWN"
                ));

        assertThatThrownBy(() ->
                service.summarize("UNKNOWN", "user-subject")
        )
                .isInstanceOf(AiSummaryExecutionException.class)
                .hasCauseInstanceOf(NoSuchElementException.class)
                .hasMessage("Award not found: UNKNOWN");

        verify(provider, never()).generate(any());
        verify(metadataLogger).log(
                any(),
                eq("user-subject"),
                eq("AWARD"),
                eq("UNKNOWN"),
                eq("stub"),
                eq("deterministic-award-summary-v1"),
                eq(0L),
                eq("SAFE_FAILURE")
        );
    }

    private AiResponse validResponse(
            String recordId
    ) {
        return responseWithCitations(List.of(
                new AiCitation(
                        "award",
                        recordId,
                        "A-100",
                        1
                )
        ));
    }

    private AiResponse responseWithCitations(
            List<AiCitation> citations
    ) {
        return new AiResponse(
                "Safe summary",
                citations,
                "stub",
                "deterministic-award-summary-v1",
                null,
                null
        );
    }

    private AwardFamilyResponse family() {
        AwardRowResponse row = new AwardRowResponse(
                101L,
                "A-100",
                1,
                "Contact person@example.edu token=TOP-SECRET "
                        + "jdbc:postgresql://db/archive",
                "ACTIVE",
                "ACTIVE",
                "Sponsor",
                "Prime",
                "Unit",
                "ACCOUNT-SECRET",
                "SPONSOR-AWARD-SECRET",
                LocalDate.of(2020, 1, 1),
                null,
                true,
                true
        );
        return new AwardFamilyResponse(
                "A-100",
                row,
                List.of(
                        new AwardSequenceResponse(
                                1,
                                true,
                                List.of(row)
                        )
                )
        );
    }
}
