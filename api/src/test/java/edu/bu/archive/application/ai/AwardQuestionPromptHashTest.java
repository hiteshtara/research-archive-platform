package edu.bu.archive.application.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AwardQuestionPromptHashTest {

    @Test
    void hashesTheExactQuestionPromptAndChangesWithText() {
        assertThat(PromptHash.sha256(
                AwardAiQuestionService.QUESTION_PROMPT
        )).isEqualTo(
                AwardAiQuestionService.QUESTION_PROMPT_HASH
        );
        assertThat(AwardAiQuestionService.QUESTION_PROMPT_HASH)
                .isEqualTo(
                        "29e9caf6afd9b5e16540c5a97db2be9e"
                                + "a405a599ed2d32370dad1cba2c74a310"
                )
                .isNotEqualTo(PromptHash.sha256(
                        AwardAiQuestionService.QUESTION_PROMPT + " "
                ));
    }
}
