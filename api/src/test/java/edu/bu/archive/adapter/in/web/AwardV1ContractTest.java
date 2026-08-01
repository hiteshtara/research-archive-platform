package edu.bu.archive.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardHierarchyNodeResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardHierarchyResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResultResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardVersionSummaryResponse;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * Golden-shape contract tests for the four v1 Award response DTOs -
 * not integration tests of any endpoint's business logic (that's
 * AwardV1ControllerTest/AwardArchiveServiceTest), but a guard against
 * an *accidental* breaking change: a field renamed, removed, or
 * re-typed (e.g. a date silently becoming a numeric timestamp array)
 * without anyone noticing, since Java records don't otherwise surface
 * their JSON shape anywhere in the compiler's view.
 *
 * The ObjectMapper here is built to mirror Spring Boot's own default
 * Jackson autoconfiguration (JavaTimeModule registered,
 * WRITE_DATES_AS_TIMESTAMPS disabled) rather than pulling in a full
 * Spring context, since no other test in this codebase does that.
 */
class AwardV1ContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void searchResultShapeIsStable() throws Exception {
        AwardSearchResultResponse result = new AwardSearchResultResponse(
                3L, "100004-00003", 1, "Title", "Approved Award",
                "MICHAEL MCCLEAN", "Brown University",
                "SPH ENVIRONMENTAL HEALTH", BigDecimal.TEN,
                "100004-00001", "100004-00002"
        );

        assertFieldNames(result, Set.of(
                "awardId", "awardNumber", "latestSequenceNumber",
                "title", "status", "principalInvestigator", "sponsor",
                "leadUnit", "currentObligatedAmount", "rootAwardNumber",
                "parentAwardNumber"
        ));
    }

    @Test
    void searchIsWrappedInTheGenericPageEnvelope() throws Exception {
        PageResponse<AwardSearchResultResponse> page = new PageResponse<>(
                List.of(), 0, 25, 0L, 0, true, true
        );

        assertFieldNames(page, Set.of(
                "content", "page", "size", "totalElements",
                "totalPages", "first", "last"
        ));
    }

    @Test
    void hierarchyShapeIsStable() throws Exception {
        AwardHierarchyNodeResponse node = new AwardHierarchyNodeResponse(
                "100004-00001", 1L, 9, null, true, "Title", "Closed",
                "MICHAEL MCCLEAN", "Brown University",
                "SPH ENVIRONMENTAL HEALTH", BigDecimal.TEN, List.of()
        );
        AwardHierarchyResponse hierarchy = new AwardHierarchyResponse(
                "100004-00001", "100004-00001", node,
                List.of("100004-00001")
        );

        assertFieldNames(hierarchy, Set.of(
                "rootAwardNumber", "requestedAwardNumber", "root",
                "selectedAwardPath"
        ));

        JsonNode rootNode = objectMapper.valueToTree(hierarchy).get("root");
        assertFieldNames(rootNode, Set.of(
                "awardNumber", "awardId", "latestSequenceNumber",
                "parentAwardNumber", "active", "title", "status",
                "principalInvestigator", "sponsor", "leadUnit",
                "currentObligatedAmount", "children"
        ));
    }

    @Test
    void summaryShapeIsStableAndDatesSerializeAsIsoStrings()
            throws Exception {
        AwardSummaryResponse summary = new AwardSummaryResponse(
                3L, "100004-00003", 1, "Title", "Approved Award",
                "Brown University", "NIH", "MICHAEL MCCLEAN",
                "SPH ENVIRONMENTAL HEALTH", LocalDate.of(2007, 9, 15),
                null, null, null, BigDecimal.TEN, BigDecimal.TEN,
                "1", "Cost reimbursement", "28", "Invoice",
                "100004-00001", "100004-00002"
        );

        assertFieldNames(summary, Set.of(
                "awardId", "awardNumber", "sequenceNumber", "title",
                "status", "sponsor", "primeSponsor",
                "principalInvestigator", "leadUnit",
                "awardEffectiveDate", "awardExecutionDate", "beginDate",
                "closeoutDate", "obligatedTotalAmount",
                "anticipatedTotalAmount", "basisOfPaymentCode",
                "basisOfPaymentDescription", "methodOfPaymentCode",
                "methodOfPaymentDescription", "rootAwardNumber",
                "parentAwardNumber"
        ));

        // FAIN and "account type" must never reappear as fields -
        // see AWARD_SEARCH_API_DESIGN.md's "Fields deliberately
        // omitted" section.
        String json = objectMapper.writeValueAsString(summary);
        assertThat(json).doesNotContainIgnoringCase("fain");
        assertThat(json).doesNotContainIgnoringCase("accountType");

        JsonNode node = objectMapper.valueToTree(summary);
        assertThat(node.get("awardEffectiveDate").asText())
                .isEqualTo("2007-09-15");
        assertThat(node.get("awardEffectiveDate").isTextual())
                .as("dates must serialize as ISO strings, not "
                        + "numeric timestamp arrays")
                .isTrue();
    }

    @Test
    void versionShapeIsStableAndTimestampsSerializeAsIsoStrings()
            throws Exception {
        AwardVersionSummaryResponse version =
                new AwardVersionSummaryResponse(
                        3L, "100004-00003", 1, "Approved Award",
                        "12", "Converted Record",
                        LocalDate.of(2007, 9, 15),
                        LocalDateTime.of(2015, 2, 11, 15, 26, 17),
                        null
                );

        assertFieldNames(version, Set.of(
                "awardId", "awardNumber", "sequenceNumber", "status",
                "transactionTypeCode", "transactionType",
                "awardEffectiveDate", "updateTimestamp",
                "documentNumber"
        ));

        JsonNode node = objectMapper.valueToTree(version);
        assertThat(node.get("updateTimestamp").isTextual())
                .as("timestamps must serialize as ISO strings, not "
                        + "numeric timestamp arrays")
                .isTrue();
        assertThat(node.get("updateTimestamp").asText())
                .isEqualTo("2015-02-11T15:26:17");
    }

    private void assertFieldNames(Object value, Set<String> expected)
            throws Exception {
        assertFieldNames(objectMapper.valueToTree(value), expected);
    }

    private void assertFieldNames(JsonNode node, Set<String> expected) {
        Set<String> actual = new HashSet<>();
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            actual.add(names.next());
        }
        assertThat(actual).isEqualTo(expected);
    }
}
