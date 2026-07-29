package edu.bu.archive.application.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.bu.archive.adapter.in.web.dto.award.AwardFamilyResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardPersonResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardRowResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSequenceResponse;
import edu.bu.archive.application.award.AwardArchiveService;
import edu.bu.archive.application.port.out.AiProvider;
import edu.bu.archive.config.AiProperties;
import edu.bu.archive.domain.model.ai.AiCitation;
import edu.bu.archive.domain.model.ai.AwardQuestionProviderResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AwardAiQuestionServiceTest {

    private AwardArchiveService archiveService;
    private AiProvider provider;
    private AiMetadataLogger metadataLogger;
    private AwardAiQuestionService service;

    @BeforeEach
    void setUp() {
        archiveService = mock(AwardArchiveService.class);
        provider = mock(AiProvider.class);
        metadataLogger = mock(AiMetadataLogger.class);
        when(provider.providerName()).thenReturn("stub");
        when(provider.modelName()).thenReturn("question-model");

        AiProperties properties = new AiProperties();
        properties.setProvider("stub");
        AwardContextBuilder contextBuilder =
                new AwardContextBuilder(
                        new SensitiveFieldRedactor(),
                        new ObjectMapper().findAndRegisterModules(),
                        properties
                );
        service = new AwardAiQuestionService(
                archiveService,
                new AwardQuestionRouter(),
                new AwardDeterministicFactResolver(
                        archiveService
                ),
                contextBuilder,
                new AwardSequenceDiffBuilder(),
                new AwardCitationValidator(),
                new AiModelRouter(
                        List.of(provider),
                        properties
                ),
                metadataLogger,
                properties,
                Clock.fixed(
                        Instant.parse("2026-07-28T12:00:00Z"),
                        ZoneOffset.UTC
                )
        );
    }

    @Test
    void answersCurrentFactsWithoutInvokingProvider() {
        when(archiveService.findFamily("A-100"))
                .thenReturn(family());

        var result = service.answer(
                "A-100",
                "What is the current status?",
                "user-subject"
        );

        assertThat(result.answer())
                .isEqualTo(
                        "The current archived Award status is Closed."
                );
        assertThat(result.answerType())
                .isEqualTo("deterministic_fact");
        assertThat(result.provider()).isEqualTo("deterministic");
        assertThat(result.model()).isEqualTo("none");
        assertThat(result.citations())
                .containsExactly(citation("102", 2));
        verify(provider, never()).answerQuestion(any());
    }

    @Test
    void answersCurrentPiWithoutSendingPeopleToProvider() {
        when(archiveService.findFamily("A-100"))
                .thenReturn(family());
        when(archiveService.findCurrentPeople("A-100"))
                .thenReturn(List.of(
                        person("PI", null, "Alex Researcher"),
                        person("COI", null, "Other Person")
                ));

        var result = service.answer(
                "A-100",
                "Who is the current principal investigator?",
                "user-subject"
        );

        assertThat(result.answer())
                .isEqualTo(
                        "The current archived principal investigator "
                                + "is Alex Researcher."
                );
        verify(provider, never()).answerQuestion(any());
    }

    @Test
    void causalQuestionReturnsInsufficientWithoutProvider() {
        when(archiveService.findFamily("A-100"))
                .thenReturn(family());

        var result = service.answer(
                "A-100",
                "Why was this Award closed?",
                "user-subject"
        );

        assertThat(result.answer())
                .isEqualTo(
                        AwardDeterministicFactResolver
                                .INSUFFICIENT_ANSWER
                );
        assertThat(result.answerType())
                .isEqualTo("insufficient_archive_data");
        assertThat(result.citations()).isEmpty();
        verify(provider, never()).answerQuestion(any());
    }

    @Test
    void validatesProviderPlanAndRendersDeterministicDiff() {
        when(archiveService.findFamily("A-100"))
                .thenReturn(family());
        when(provider.answerQuestion(any())).thenReturn(
                providerResponse(
                        List.of("status:sequence-1:sequence-2"),
                        List.of(
                                citation("101", 1),
                                citation("102", 2)
                        )
                )
        );

        var result = service.answer(
                "A-100",
                "Compare sequence 1 and sequence 2",
                "user-subject"
        );

        assertThat(result.answer())
                .isEqualTo(
                        "Between sequence 1 and sequence 2, "
                                + "the archived status changed from "
                                + "Active to Closed."
                );
        assertThat(result.answerType())
                .isEqualTo(
                        "ai_explained_sequence_comparison"
                );
        assertThat(result.provider()).isEqualTo("stub");
        assertThat(result.citations())
                .containsExactly(
                        citation("101", 1),
                        citation("102", 2)
                );

        ArgumentCaptor<
                edu.bu.archive.domain.model.ai
                        .AwardQuestionProviderRequest
                > request = ArgumentCaptor.forClass(
                        edu.bu.archive.domain.model.ai
                                .AwardQuestionProviderRequest.class
                );
        verify(provider).answerQuestion(request.capture());
        assertThat(request.getValue().supports())
                .extracting(support -> support.supportId())
                .contains(
                        "status:sequence-1:sequence-2"
                );
        assertThat(request.getValue().toString())
                .doesNotContain(
                        "ACCOUNT-SECRET",
                        "person@example.edu",
                        "jdbc:postgresql",
                        "sourceUpdate"
                );
    }

    @Test
    void rejectsFabricatedSupportIdsAndCitations() {
        when(archiveService.findFamily("A-100"))
                .thenReturn(family());
        when(provider.answerQuestion(any())).thenReturn(
                providerResponse(
                        List.of("fabricated"),
                        List.of(citation("101", 1))
                )
        );

        assertThatThrownBy(() -> service.answer(
                "A-100",
                "Summarize the Award history",
                "user-subject"
        )).isInstanceOf(AiSummaryExecutionException.class)
                .hasCauseInstanceOf(AiProviderException.class)
                .hasMessage(
                        "AI provider returned an unsupported support ID"
                );

        when(provider.answerQuestion(any())).thenReturn(
                providerResponse(
                        List.of("status:sequence-1:sequence-2"),
                        List.of(citation("999", 2))
                )
        );
        assertThatThrownBy(() -> service.answer(
                "A-100",
                "Compare sequence 1 and sequence 2",
                "user-subject"
        )).isInstanceOf(AiSummaryExecutionException.class)
                .hasCauseInstanceOf(AiProviderException.class)
                .hasMessage(
                        "AI provider returned an unsupported citation"
                );
    }

    @Test
    void unknownAwardDoesNotInvokeProvider() {
        when(archiveService.findFamily("MISSING"))
                .thenThrow(new java.util.NoSuchElementException(
                        "Award not found"
                ));

        assertThatThrownBy(() -> service.answer(
                "MISSING",
                "Summarize the Award history",
                "user-subject"
        )).isInstanceOf(AiSummaryExecutionException.class)
                .hasCauseInstanceOf(
                        java.util.NoSuchElementException.class
                );
        verify(provider, never()).answerQuestion(any());
    }

    private AwardQuestionProviderResponse providerResponse(
            List<String> supportIds,
            List<AiCitation> citations
    ) {
        return new AwardQuestionProviderResponse(
                supportIds,
                citations,
                "stub",
                "question-model",
                50L,
                10L
        );
    }

    private AwardFamilyResponse family() {
        AwardRowResponse historical = row(
                101L, 1, "Active", false
        );
        AwardRowResponse current = row(
                102L, 2, "Closed", true
        );
        return new AwardFamilyResponse(
                "A-100",
                current,
                List.of(
                        new AwardSequenceResponse(
                                2, true, List.of(current)
                        ),
                        new AwardSequenceResponse(
                                1, false, List.of(historical)
                        )
                )
        );
    }

    private AwardRowResponse row(
            long awardId,
            int sequence,
            String status,
            boolean current
    ) {
        return new AwardRowResponse(
                awardId,
                "A-100",
                sequence,
                "Award title",
                status,
                status,
                "Sponsor",
                "Prime sponsor",
                "Lead unit",
                "ACCOUNT-SECRET",
                "SPONSOR-AWARD-SECRET",
                LocalDate.of(2020, 1, 1),
                null,
                current,
                current
        );
    }

    private AwardPersonResponse person(
            String contactRole,
            String projectRole,
            String fullName
    ) {
        return new AwardPersonResponse(
                1L, 102L, "A-100", 2, "person-id", null,
                fullName, contactRole, projectRole, null,
                null, null, null, null, null, null
        );
    }

    private AiCitation citation(
            String recordId,
            int sequence
    ) {
        return new AiCitation(
                "award",
                recordId,
                "A-100",
                sequence
        );
    }
}
