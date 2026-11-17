package edu.bu.archive.application.authorization.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The server reason rule (decision D4) against the SHARED case file.
 *
 * <p>{@code docs/security/reason-rule-cases.json} is read by this test and by
 * {@code ui/src/features/securityAdmin/reasonRuleSharedCases.test.mjs}, so the
 * server rule and the UI rule are proved to accept and refuse exactly the
 * same inputs. A case added for one side is automatically a case for the
 * other.
 */
class ReasonsTest {

    record Case(String input, boolean valid, String why) { }

    static Stream<Arguments> sharedCases() throws IOException {
        List<Case> cases = new ObjectMapper().readValue(
                Files.readString(casesFile()), new TypeReference<List<Case>>() { });
        assertThat(cases).as("the shared case file is not empty").hasSizeGreaterThan(50);
        return cases.stream().map(c -> Arguments.of(c.input(), c.valid(), c.why()));
    }

    @ParameterizedTest(name = "[{index}] valid={1} ({2})")
    @MethodSource("sharedCases")
    void everySharedCaseIsDecidedTheSameWayAsTheUiRule(String input, boolean valid, String why) {
        if (valid) {
            assertThat(Reasons.require(input))
                    .as(why)
                    .isEqualTo(Reasons.normalize(input));
            assertThat(Reasons.isAcceptable(input)).isTrue();
        } else {
            assertThatThrownBy(() -> Reasons.require(input))
                    .as(why)
                    .isInstanceOf(ReasonRequiredException.class);
            assertThat(Reasons.isAcceptable(input)).isFalse();
        }
    }

    @Test
    void nullIsRefusedLikeBlank() {
        assertThatThrownBy(() -> Reasons.require(null))
                .isInstanceOf(ReasonRequiredException.class);
    }

    @Test
    void theReasonIsStoredNormalised() {
        assertThat(Reasons.require("  Left   the\tdepartment \n "))
                .isEqualTo("Left the department");
    }

    @Test
    void everyListedPlaceholderIsRefusedAloneAndRepeated() {
        for (String phrase : Reasons.PLACEHOLDERS) {
            assertThat(Reasons.isPlaceholder(phrase)).as(phrase).isTrue();
            assertThat(Reasons.isAcceptable(phrase + " " + phrase + " " + phrase))
                    .as(phrase + " repeated")
                    .isFalse();
        }
    }

    @Test
    void aReasonRequiredRefusalIsAlsoAnIllegalArgument() {
        // Existing callers treat IllegalArgumentException as a refused request.
        assertThatThrownBy(() -> Reasons.require("n/a"))
                .isInstanceOf(IllegalArgumentException.class)
                .extracting(e -> ((ReasonRequiredException) e).getMessage())
                .asString()
                .contains("placeholder");
    }

    private static Path casesFile() {
        for (Path candidate : List.of(
                Path.of("..", "docs", "security", "reason-rule-cases.json"),
                Path.of("docs", "security", "reason-rule-cases.json"))) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("cannot locate docs/security/reason-rule-cases.json");
    }
}
