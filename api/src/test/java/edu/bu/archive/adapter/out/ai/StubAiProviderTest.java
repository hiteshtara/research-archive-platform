package edu.bu.archive.adapter.out.ai;

import edu.bu.archive.domain.model.ai.AiRequest;
import edu.bu.archive.domain.model.ai.AiResponse;
import edu.bu.archive.domain.model.ai.AwardAiContext;
import edu.bu.archive.domain.model.ai.AwardAiContextChanges;
import edu.bu.archive.domain.model.ai.AwardAiContextRecord;
import edu.bu.archive.domain.model.ai.AiCitation;
import edu.bu.archive.domain.model.ai.AwardQuestionProviderRequest;
import edu.bu.archive.domain.model.ai.AwardQuestionSupport;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StubAiProviderTest {

    @Test
    void isDeterministicAndHasNoNetworkCollaborators() {
        StubAiProvider provider = new StubAiProvider();
        AiRequest request = new AiRequest(
                "Fixed system prompt",
                new AwardAiContext(
                        "A-100",
                        101L,
                        List.of(
                                new AwardAiContextRecord(
                                        101L,
                                        1,
                                        new AwardAiContextChanges(
                                                "Title",
                                                "ACTIVE",
                                                "ACTIVE",
                                                "Sponsor",
                                                null,
                                                "Unit",
                                                null,
                                                null
                                        ),
                                        null
                                )
                        ),
                        false
                )
        );

        AiResponse first = provider.generate(request);
        AiResponse second = provider.generate(request);

        assertThat(first).isEqualTo(second);
        assertThat(StubAiProvider.class.getDeclaredFields())
                .isEmpty();
        assertThat(first.provider()).isEqualTo("stub");
    }

    @Test
    void selectsQuestionSupportsDeterministically() {
        StubAiProvider provider = new StubAiProvider();
        AiCitation citation = new AiCitation(
                "award", "101", "A-100", 1
        );
        AwardQuestionProviderRequest request =
                new AwardQuestionProviderRequest(
                        "Fixed system prompt",
                        "Summarize the Award history",
                        "A-100",
                        List.of(new AwardQuestionSupport(
                                "status:sequence-1",
                                "Status was Active.",
                                List.of(citation)
                        )),
                        false
                );

        assertThat(provider.answerQuestion(request))
                .isEqualTo(provider.answerQuestion(request));
        assertThat(provider.answerQuestion(request).supportIds())
                .containsExactly("status:sequence-1");
        assertThat(provider.answerQuestion(request).citations())
                .containsExactly(citation);
    }
}
