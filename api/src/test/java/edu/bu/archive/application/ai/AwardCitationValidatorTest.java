package edu.bu.archive.application.ai;

import edu.bu.archive.domain.model.ai.AiCitation;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AwardCitationValidatorTest {

    private final AwardCitationValidator validator =
            new AwardCitationValidator();
    private final AiCitation allowed =
            new AiCitation("award", "101", "A-100", 2);

    @Test
    void canonicalizesAnExactSuppliedCitation() {
        assertThat(validator.validateRequired(
                List.of(new AiCitation(
                        " Award ", " 101 ", " A-100 ", 2
                )),
                List.of(allowed)
        )).containsExactly(allowed);
    }

    @Test
    void rejectsFabricatedAndOutOfScopeCitations() {
        assertThatThrownBy(() -> validator.validateRequired(
                List.of(new AiCitation(
                        "award", "999", "A-100", 2
                )),
                List.of(allowed)
        )).isInstanceOf(AiProviderException.class)
                .hasMessage(
                        "AI provider returned an unsupported citation"
                );
    }
}
