package edu.bu.archive.application.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.bu.archive.adapter.in.web.dto.award.AwardFamilyResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardAmountResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardPersonResponse;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AwardAiSummaryServiceTest {

    private AwardArchiveService awardArchiveService;
    private AiProvider provider;
    private AiMetadataLogger metadataLogger;
    private AwardAiSummaryService service;
    private AiProperties properties;
    private AiModelRouter router;

    @BeforeEach
    void setUp() {
        awardArchiveService = mock(AwardArchiveService.class);
        provider = mock(AiProvider.class);
        metadataLogger = mock(AiMetadataLogger.class);

        when(provider.providerName()).thenReturn("stub");
        when(provider.modelName())
                .thenReturn("deterministic-award-summary-v1");

        properties = new AiProperties();
        properties.setProvider("stub");
        router = new AiModelRouter(
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
                new AwardAiSummaryCache(properties),
                properties,
                Clock.fixed(
                        Instant.parse("2026-07-28T12:00:00Z"),
                        ZoneOffset.UTC
                )
        );
    }

    @Test
    void buildsDeterministicCurrentRecordAndOrderedTimeline() {
        AwardFamilyResponse family = familyWithOutOfOrderRows();
        when(awardArchiveService.findFamily("A-100"))
                .thenReturn(family);
        when(awardArchiveService.findCurrentPeople("A-100"))
                .thenReturn(List.of(
                        person("PI", null, "Alex Researcher"),
                        person("COI", null, "Not The PI")
                ));
        when(awardArchiveService.findCurrentAmounts("A-100"))
                .thenReturn(List.of(amount(102L)));
        when(provider.generate(any()))
                .thenReturn(responseWithCitations(List.of(
                        citation("101", 1),
                        citation("102", 2)
                )));

        AwardAiSummaryResult result =
                service.summarize("A-100", "user-subject");

        assertThat(result.currentRecord()).satisfies(current -> {
            assertThat(current.awardId()).isEqualTo(102L);
            assertThat(current.awardNumber()).isEqualTo("A-100");
            assertThat(current.sequenceNumber()).isEqualTo(2);
            assertThat(current.status()).isEqualTo("ACTIVE");
            assertThat(current.sponsor()).isEqualTo("Sponsor");
            assertThat(current.leadUnit()).isEqualTo("Unit");
            assertThat(current.principalInvestigators())
                    .containsExactly("Alex Researcher");
            assertThat(current.beginDate())
                    .isEqualTo(LocalDate.of(2021, 1, 1));
            assertThat(current.anticipatedTotalAmount())
                    .isEqualByComparingTo("1200.00");
            assertThat(current.obligatedTotalAmount())
                    .isEqualByComparingTo("900.00");
        });
        assertThat(result.timeline())
                .extracting(record -> record.awardId())
                .containsExactly(101L, 102L);
    }

    @Test
    void modelNarrativeCannotOverrideDeterministicFacts() {
        when(awardArchiveService.findFamily("A-100"))
                .thenReturn(familyWithOutOfOrderRows());
        when(provider.generate(any())).thenReturn(new AiResponse(
                """
                {"currentRecord":{"status":"MODEL STATUS"},\
                "timeline":[{"sequenceNumber":999}],\
                "sponsor":"MODEL SPONSOR","pi":"MODEL PI",\
                "amounts":999999}
                """,
                List.of("MODEL STATUS"),
                "MODEL SPONSOR",
                List.of(citation("101", 1)),
                "stub",
                "deterministic-award-summary-v1",
                null,
                null
        ));

        AwardAiSummaryResult result =
                service.summarize("A-100", "user-subject");

        assertThat(result.currentRecord().status())
                .isEqualTo("ACTIVE");
        assertThat(result.currentRecord().sponsor())
                .isEqualTo("Sponsor");
        assertThat(result.currentRecord().leadUnit())
                .isEqualTo("Unit");
        assertThat(result.timeline())
                .extracting(record -> record.sequenceNumber())
                .containsExactly(1, 2);
    }

    @Test
    void rejectsBlankNarrativeSections() {
        when(awardArchiveService.findFamily("A-100"))
                .thenReturn(familyWithOutOfOrderRows());
        when(provider.generate(any())).thenReturn(new AiResponse(
                "Overview",
                List.of(" "),
                "Assessment",
                List.of(citation("101", 1)),
                "stub",
                "deterministic-award-summary-v1",
                null,
                null
        ));

        assertThatThrownBy(() ->
                service.summarize("A-100", "user-subject")
        )
                .isInstanceOf(AiSummaryExecutionException.class)
                .hasCauseInstanceOf(AiProviderException.class);
    }

    @Test
    void reusesValidatedNarrativeOnAnExactCacheHit() {
        properties.setCacheEnabled(true);
        service = new AwardAiSummaryService(
                awardArchiveService,
                new AwardContextBuilder(
                        new SensitiveFieldRedactor(),
                        new ObjectMapper().findAndRegisterModules(),
                        properties
                ),
                router,
                metadataLogger,
                new AwardAiSummaryCache(properties),
                properties,
                Clock.fixed(
                        Instant.parse("2026-07-28T12:00:00Z"),
                        ZoneOffset.UTC
                )
        );
        when(awardArchiveService.findFamily("A-100"))
                .thenReturn(
                        multiSequenceFamilyWithStatus("ACTIVE"),
                        multiSequenceFamilyWithStatus("CLOSED")
                );
        when(awardArchiveService.findCurrentPeople("A-100"))
                .thenReturn(
                        List.of(person(
                                "PI", null, "First PI"
                        )),
                        List.of(person(
                                "PI", null, "Updated PI"
                        ))
                );
        when(provider.generate(any()))
                .thenReturn(validResponse("101"));

        AwardAiSummaryResult first =
                service.summarize("A-100", "user-subject");
        AwardAiSummaryResult second =
                service.summarize("A-100", "user-subject");

        assertThat(second.response()).isEqualTo(first.response());
        assertThat(first.currentRecord().status())
                .isEqualTo("ACTIVE");
        assertThat(second.currentRecord().status())
                .isEqualTo("CLOSED");
        assertThat(second.currentRecord().principalInvestigators())
                .containsExactly("Updated PI");
        assertThat(second.timeline())
                .last()
                .satisfies(record ->
                        assertThat(record.status())
                                .isEqualTo("CLOSED")
                );
        verify(provider, times(1)).generate(any());
        verify(awardArchiveService, times(2))
                .findCurrentPeople("A-100");
        verify(awardArchiveService, times(2))
                .findCurrentAmounts("A-100");
    }

    @Test
    void usesHyphenatedAwardFamilyWithoutPhysicalIdResolution() {
        when(awardArchiveService.findFamily("A-100"))
                .thenReturn(familyWithOutOfOrderRows());
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

        assertThat(result.response().overview())
                .isEqualTo("Safe summary");
        assertThat(request.getValue().awardContext().records())
                .hasSize(2);
        assertThat(request.getValue().awardContext().records().getFirst())
                .satisfies(record -> {
                    assertThat(record.awardId()).isEqualTo(101L);
                    assertThat(record.changes().title())
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
                eq(serializedLength(
                        request.getValue().awardContext()
                )),
                eq(2),
                eq("ai_success"),
                eq(null),
                eq(null),
                eq(false),
                eq("award-summary-v2"),
                eq(AwardAiSummaryService.SYSTEM_PROMPT_HASH)
        );
    }

    @Test
    void singleSequenceUsesImmediateDeterministicPath() {
        when(awardArchiveService.findFamily("A-100"))
                .thenReturn(family());

        AwardAiSummaryResult result =
                service.summarize("A-100", "user-subject");

        verify(provider, never()).generate(any());
        assertThat(result.response().provider())
                .isEqualTo("deterministic");
        assertThat(result.response().model()).isEqualTo("none");
        assertThat(result.response().overview())
                .contains(
                        "Award A-100 has 1 archived sequence.",
                        "Current status: ACTIVE.",
                        "Sponsor: Sponsor.",
                        "Prime sponsor: Prime.",
                        "Lead unit: Unit.",
                        "Begin date: 2020-01-01."
                )
                .doesNotContain("Closeout date");
        assertThat(result.response().notableChanges())
                .containsExactly(
                        "There are no historical sequence changes "
                                + "to summarize."
                );
        assertThat(result.response().citations())
                .containsExactly(citation("101", 1));
        assertThat(result.timeline())
                .extracting(record -> record.awardId())
                .containsExactly(101L);
        verify(metadataLogger).log(
                eq(result.correlationId()),
                eq("user-subject"),
                eq("AWARD"),
                eq("A-100"),
                eq("deterministic"),
                eq("none"),
                eq(0L),
                anyInt(),
                eq(1),
                eq("deterministic_single_sequence"),
                eq(null),
                eq(null),
                eq(false),
                eq("award-summary-v2"),
                eq(AwardAiSummaryService.SYSTEM_PROMPT_HASH)
        );
    }

    @Test
    void deterministicSummaryOmitsUnavailableFacts() {
        AwardRowResponse row = new AwardRowResponse(
                201L, "B-200", 1, "Title", null, null,
                null, null, null, null, null, null, null,
                true, true
        );
        when(awardArchiveService.findFamily("B-200"))
                .thenReturn(new AwardFamilyResponse(
                        "B-200",
                        row,
                        List.of(new AwardSequenceResponse(
                                1, true, List.of(row)
                        ))
                ));

        AwardAiSummaryResult result =
                service.summarize("B-200", "user-subject");

        assertThat(result.response().overview())
                .isEqualTo(
                        "Award B-200 has 1 archived sequence."
                )
                .doesNotContain(
                        "null",
                        "Current status",
                        "Sponsor",
                        "Lead unit",
                        "date"
                );
        verify(provider, never()).generate(any());
    }

    @Test
    void multiSequenceStillInvokesConfiguredProvider() {
        when(awardArchiveService.findFamily("A-100"))
                .thenReturn(familyWithOutOfOrderRows());
        when(provider.generate(any()))
                .thenReturn(validResponse("101"));

        AwardAiSummaryResult result =
                service.summarize("A-100", "user-subject");

        verify(provider).generate(any());
        assertThat(result.response().provider()).isEqualTo("stub");
        assertThat(result.response().overview())
                .isEqualTo("Safe summary");
    }

    @Test
    void preservesNarrativeForTwentyOneSequenceAward() {
        AwardFamilyResponse family =
                familyWithSequenceCount(21);
        when(awardArchiveService.findFamily("A-100"))
                .thenReturn(family);
        when(provider.generate(any())).thenReturn(new AiResponse(
                "Twenty-one sequence overview",
                List.of(
                        "Status changed across archived sequences.",
                        "Sponsor information changed."
                ),
                "Twenty-one archived sequences were supplied.",
                List.of(
                        citation("101", 1),
                        citation("121", 21)
                ),
                "stub",
                "deterministic-award-summary-v1",
                500L,
                80L
        ));

        AwardAiSummaryResult result =
                service.summarize("A-100", "user-subject");

        verify(provider).generate(any());
        assertThat(result.response().overview())
                .isEqualTo("Twenty-one sequence overview");
        assertThat(result.response().notableChanges())
                .containsExactly(
                        "Status changed across archived sequences.",
                        "Sponsor information changed."
                );
        assertThat(result.response().archiveAssessment())
                .isEqualTo(
                        "Twenty-one archived sequences were supplied."
                );
        assertThat(result.response().citations())
                .containsExactly(
                        citation("101", 1),
                        citation("121", 21)
                );
        assertThat(result.timeline()).hasSize(21);
        assertThat(result.response().provider()).isEqualTo("stub");
    }

    @Test
    void blankValidAiNarrativeUsesDeterministicFallback() {
        when(awardArchiveService.findFamily("A-100"))
                .thenReturn(familyWithOutOfOrderRows());
        when(provider.generate(any())).thenReturn(new AiResponse(
                " ",
                List.of(),
                "",
                List.of(citation("101", 1)),
                "stub",
                "deterministic-award-summary-v1",
                12L,
                3L
        ));

        AwardAiSummaryResult result =
                service.summarize("A-100", "user-subject");

        assertThat(result.response().provider())
                .isEqualTo("deterministic");
        assertThat(result.response().model()).isEqualTo("none");
        assertThat(result.response().overview())
                .contains(
                        "Award A-100 has 2 archived sequences.",
                        "Current status: ACTIVE.",
                        "Sponsor: Sponsor."
                );
        assertThat(result.response().citations())
                .containsExactly(citation("101", 1));
        assertThat(result.currentRecord().awardId())
                .isEqualTo(102L);
        assertThat(result.timeline())
                .extracting(record -> record.awardId())
                .containsExactly(101L, 102L);
        verify(metadataLogger).log(
                eq(result.correlationId()),
                eq("user-subject"),
                eq("AWARD"),
                eq("A-100"),
                eq("stub"),
                eq("deterministic-award-summary-v1"),
                eq(0L),
                anyInt(),
                eq(2),
                eq("deterministic_empty_narrative_fallback"),
                eq(12L),
                eq(3L),
                eq(false),
                eq("award-summary-v2"),
                eq(AwardAiSummaryService.SYSTEM_PROMPT_HASH)
        );
    }

    @Test
    void rejectsFabricatedCitationsAndLogsSafeFailure() {
        when(awardArchiveService.findFamily("A-100"))
                .thenReturn(familyWithOutOfOrderRows());
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
                anyInt(),
                eq(2),
                eq("ai_failure"),
                eq(null),
                eq(null),
                eq(false),
                eq("award-summary-v2"),
                eq(AwardAiSummaryService.SYSTEM_PROMPT_HASH)
        );
        assertThat(correlationId.getValue())
                .isEqualTo(exception.correlationId());
    }

    @Test
    void acceptsAndCanonicalizesGptCitationPresentation() {
        when(awardArchiveService.findFamily("A-100"))
                .thenReturn(familyWithOutOfOrderRows());
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
                .thenReturn(familyWithOutOfOrderRows());
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
                .thenReturn(familyWithOutOfOrderRows());
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
                .thenReturn(familyWithOutOfOrderRows());
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
                eq("unresolved"),
                eq("unresolved"),
                eq(0L),
                anyInt(),
                eq(0),
                eq("ai_failure"),
                eq(null),
                eq(null),
                eq(false),
                eq("award-summary-v2"),
                eq(AwardAiSummaryService.SYSTEM_PROMPT_HASH)
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

    private int serializedLength(
            edu.bu.archive.domain.model.ai.AwardAiContext context
    ) {
        try {
            return new ObjectMapper()
                    .findAndRegisterModules()
                    .writeValueAsString(context)
                    .length();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private AiResponse responseWithCitations(
            List<AiCitation> citations
    ) {
        return new AiResponse(
                "Safe summary",
                List.of("Status changed"),
                "Archive history is complete.",
                citations,
                "stub",
                "deterministic-award-summary-v1",
                null,
                null
        );
    }

    private AiCitation citation(
            String recordId,
            int sequenceNumber
    ) {
        return new AiCitation(
                "award",
                recordId,
                "A-100",
                sequenceNumber
        );
    }

    private AwardFamilyResponse familyWithOutOfOrderRows() {
        AwardRowResponse historical = row(
                101L,
                1,
                false,
                false,
                LocalDate.of(2020, 1, 1)
        );
        AwardRowResponse current = row(
                102L,
                2,
                true,
                true,
                LocalDate.of(2021, 1, 1)
        );
        return new AwardFamilyResponse(
                "A-100",
                current,
                List.of(
                        new AwardSequenceResponse(
                                2,
                                true,
                                List.of(current)
                        ),
                        new AwardSequenceResponse(
                                1,
                                false,
                                List.of(historical)
                        )
                )
        );
    }

    private AwardRowResponse row(
            long awardId,
            int sequence,
            boolean current,
            boolean primaryCurrent,
            LocalDate beginDate
    ) {
        return new AwardRowResponse(
                awardId,
                "A-100",
                sequence,
                "Authoritative title",
                "ACTIVE",
                "ACTIVE",
                "Sponsor",
                "Prime",
                "Unit",
                null,
                null,
                beginDate,
                null,
                current,
                primaryCurrent
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

    private AwardAmountResponse amount(
            Long awardId
    ) {
        return new AwardAmountResponse(
                1L, awardId, "A-100", 2,
                null, null, null, null, null, null,
                new BigDecimal("1200.00"),
                new BigDecimal("900.00"),
                null, 1L
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

    private AwardFamilyResponse familyWithStatus(
            String status
    ) {
        AwardRowResponse row = new AwardRowResponse(
                101L,
                "A-100",
                1,
                "Authoritative updated title",
                status,
                status,
                "Authoritative sponsor",
                "Prime",
                "Authoritative unit",
                null,
                null,
                LocalDate.of(2020, 1, 1),
                null,
                true,
                true
        );
        return new AwardFamilyResponse(
                "A-100",
                row,
                List.of(new AwardSequenceResponse(
                        1,
                        true,
                        List.of(row)
                ))
        );
    }

    private AwardFamilyResponse multiSequenceFamilyWithStatus(
            String status
    ) {
        AwardRowResponse historical = row(
                101L,
                1,
                false,
                false,
                LocalDate.of(2020, 1, 1)
        );
        AwardRowResponse current = new AwardRowResponse(
                102L,
                "A-100",
                2,
                "Authoritative updated title",
                status,
                status,
                "Authoritative sponsor",
                "Prime",
                "Authoritative unit",
                null,
                null,
                LocalDate.of(2021, 1, 1),
                null,
                true,
                true
        );
        return new AwardFamilyResponse(
                "A-100",
                current,
                List.of(
                        new AwardSequenceResponse(
                                1, false, List.of(historical)
                        ),
                        new AwardSequenceResponse(
                                2, true, List.of(current)
                        )
                )
        );
    }

    private AwardFamilyResponse familyWithSequenceCount(
            int sequenceCount
    ) {
        List<AwardSequenceResponse> sequences =
                IntStream.rangeClosed(1, sequenceCount)
                        .mapToObj(sequenceNumber -> {
                            boolean current =
                                    sequenceNumber == sequenceCount;
                            AwardRowResponse row = row(
                                    100L + sequenceNumber,
                                    sequenceNumber,
                                    current,
                                    current,
                                    LocalDate.of(
                                            2020,
                                            1,
                                            1
                                    ).plusYears(
                                            sequenceNumber - 1L
                                    )
                            );
                            return new AwardSequenceResponse(
                                    sequenceNumber,
                                    current,
                                    List.of(row)
                            );
                        })
                        .toList();
        AwardRowResponse current =
                sequences.getLast().rows().getFirst();
        return new AwardFamilyResponse(
                "A-100",
                current,
                sequences
        );
    }
}
