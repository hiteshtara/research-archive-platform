package edu.bu.archive.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardAmountHistoryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCentralAdministrationContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCommentResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCommentsResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCreditSplitResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardHierarchyNodeResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardHierarchyResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardNotepadEntryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardPersonDetailResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardPersonUnitResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardReportTermRecipientResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardReportTermResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSapTransmissionChildResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardDocumentNumberMatchResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSapTransmissionResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResultResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSponsorContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSponsorTermResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardTermsResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardUnitContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardUnitDetailsResponse;
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
    void exactDocumentMatchShapeIsStable() throws Exception {
        AwardDocumentNumberMatchResponse match =
                new AwardDocumentNumberMatchResponse(
                        1135067L, "100567-00001", 6, "328797", "Award",
                        "Title", "Approved Award"
                );

        assertFieldNames(match, Set.of(
                "awardId", "awardNumber", "sequenceNumber",
                "workflowDocumentNumber", "documentType", "title", "status"
        ));
    }

    @Test
    void searchResponseShapeIsStableAndWrapsThePageEnvelope()
            throws Exception {
        AwardSearchResponse response = new AwardSearchResponse(
                null,
                new PageResponse<>(List.of(), 0, 25, 0L, 0, true, true)
        );

        assertFieldNames(response, Set.of("exactDocumentMatch", "results"));
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
                        "328797",
                        null,
                        true
                );

        assertFieldNames(version, Set.of(
                "awardId", "awardNumber", "sequenceNumber", "status",
                "transactionTypeCode", "transactionType",
                "awardEffectiveDate", "updateTimestamp",
                "documentNumber", "modificationNumber", "primaryCurrent"
        ));

        JsonNode node = objectMapper.valueToTree(version);
        assertThat(node.get("updateTimestamp").isTextual())
                .as("timestamps must serialize as ISO strings, not "
                        + "numeric timestamp arrays")
                .isTrue();
        assertThat(node.get("updateTimestamp").asText())
                .isEqualTo("2015-02-11T15:26:17");
    }

    @Test
    void personDetailShapeIsStableAndNestsUnitsAndCreditSplits()
            throws Exception {
        AwardPersonDetailResponse person = new AwardPersonDetailResponse(
                10L, "P100", "MICHAEL MCCLEAN", "PI", "PI", true,
                BigDecimal.ONE, BigDecimal.ONE, null, BigDecimal.ONE,
                List.of(new AwardPersonUnitResponse(
                        "SPH-ENV", true,
                        List.of(new AwardCreditSplitResponse(
                                "OVERHEAD", BigDecimal.TEN
                        ))
                )),
                List.of(new AwardCreditSplitResponse(
                        "PROJECT", BigDecimal.TEN
                ))
        );

        assertFieldNames(person, Set.of(
                "awardPersonId", "personId", "fullName", "contactRoleCode",
                "keyPersonProjectRole", "leadPrincipalInvestigator",
                "academicYearEffort", "calendarYearEffort", "summerEffort",
                "totalEffort", "units", "creditSplits"
        ));

        JsonNode unit = objectMapper.valueToTree(person).get("units").get(0);
        assertFieldNames(unit, Set.of("unitNumber", "leadUnit", "creditSplits"));
    }

    @Test
    void unitDetailsShapeIsStable() throws Exception {
        AwardUnitDetailsResponse unit = new AwardUnitDetailsResponse(
                "1203250000", "CAS SPACE PHYSICS", "1200000000",
                "COLLEGE OF ARTS & SCIENCES (CAS)", "1", true
        );

        assertFieldNames(unit, Set.of(
                "unitNumber", "unitName", "parentUnitNumber",
                "parentUnitName", "organization", "leadUnit"
        ));
    }

    @Test
    void unitContactShapeIsStable() throws Exception {
        AwardUnitContactResponse contact = new AwardUnitContactResponse(
                "U17311007", "ERIN REYNOLDS",
                "Post-Award - Department Administrator", "1203250000", true,
                "EREYNOLD@BU.EDU", "617-358-0603"
        );

        assertFieldNames(contact, Set.of(
                "personId", "fullName", "projectRole", "unitNumber",
                "leadUnit", "email", "phone"
        ));
    }

    @Test
    void sponsorContactShapeIsStable() throws Exception {
        AwardSponsorContactResponse contact = new AwardSponsorContactResponse(
                "Jane Smith", "NIH", "TECHNICAL", "jane.smith@nih.gov",
                "301-555-0100"
        );

        assertFieldNames(contact, Set.of(
                "fullName", "organization", "contactRoleCode", "email",
                "phone"
        ));
    }

    @Test
    void centralAdministrationContactShapeIsStable() throws Exception {
        AwardCentralAdministrationContactResponse contact =
                new AwardCentralAdministrationContactResponse(
                        "U44984650", "NANCY SCHINDELE", "PAFO Administrator",
                        "NANCYSCH@BU.EDU", "617-358-5117"
                );

        assertFieldNames(contact, Set.of(
                "personId", "fullName", "projectRole", "email", "phone"
        ));
    }

    @Test
    void amountHistoryShapeIsStableAndWrappedInThePageEnvelope()
            throws Exception {
        AwardAmountHistoryResponse amount = new AwardAmountHistoryResponse(
                1L, 3L, "100004-00003", 1, BigDecimal.TEN, BigDecimal.ONE,
                BigDecimal.TEN, null, null, null, null, BigDecimal.TEN,
                LocalDate.of(2020, 1, 1), "DOC-1", 1L
        );

        assertFieldNames(amount, Set.of(
                "awardAmountInfoId", "awardId", "awardNumber",
                "sequenceNumber", "obligatedTotalDirect",
                "obligatedTotalIndirect", "obligatedTotalAmount",
                "anticipatedChangeDirect", "anticipatedChangeIndirect",
                "anticipatedTotalDirect", "anticipatedTotalIndirect",
                "anticipatedTotalAmount", "awardEffectiveDate",
                "documentNumber", "sourceVersionNumber"
        ));
    }

    @Test
    void termsShapeIsStableAndSeparatesSponsorFromReportTerms()
            throws Exception {
        AwardTermsResponse terms = new AwardTermsResponse(
                List.of(new AwardSponsorTermResponse(1L, 555L)),
                List.of(new AwardReportTermResponse(
                        200L, "FINANCIAL", "FIN-1", "ANNUAL",
                        "ANNIVERSARY", "PI", LocalDate.of(2021, 6, 30),
                        List.of(new AwardReportTermRecipientResponse(
                                900L, 42L, "PI", null, 1
                        ))
                ))
        );

        assertFieldNames(terms, Set.of("sponsorTerms", "reportTerms"));

        JsonNode reportTerm =
                objectMapper.valueToTree(terms).get("reportTerms").get(0);
        assertFieldNames(reportTerm, Set.of(
                "awardReportTermId", "reportClassCode", "reportCode",
                "frequencyCode", "frequencyBaseCode", "ospDistributionCode",
                "dueDate", "recipients"
        ));
    }

    @Test
    void commentsShapeIsStableAndKeepsNotepadSeparate() throws Exception {
        AwardCommentsResponse comments = new AwardCommentsResponse(
                List.of(new AwardCommentResponse(
                        1L, "GENERAL", "N", "text",
                        LocalDateTime.of(2021, 1, 1, 0, 0), "jsmith"
                )),
                List.of(new AwardNotepadEntryResponse(
                        1L, 1, "Kickoff", "note", "N",
                        LocalDateTime.of(2020, 1, 1, 0, 0), "jsmith",
                        LocalDateTime.of(2020, 1, 1, 0, 0), "jsmith"
                ))
        );

        assertFieldNames(comments, Set.of("comments", "notepadEntries"));
    }

    @Test
    void sapTransmissionShapeIsStableAndNeverRendersXmlAsHtmlByItself()
            throws Exception {
        AwardSapTransmissionResponse transmission =
                new AwardSapTransmissionResponse(
                        700L, "100004-00003", 1, "jsmith", "SAP-GW", "Y",
                        true, LocalDate.of(2021, 3, 1), "1", 28, "NIH",
                        "28", "DOC-1", "<xml>sent</xml>",
                        "<xml>returned</xml>",
                        List.of(new AwardSapTransmissionChildResponse(
                                800L, "100004-00099", 1, "DOC-1", "DOC-2",
                                "SPH", "SUB", "OH1", "B1", "N"
                        ))
                );

        assertFieldNames(transmission, Set.of(
                "transmissionId", "awardNumber", "sequenceNumber",
                "initiatorId", "transmitterId", "successIndicator",
                "successful", "transmissionDate", "basisOfPaymentCode",
                "accountTypeCode", "sponsorCode", "methodOfPaymentCode",
                "documentNumber", "sentData", "returnedData", "children"
        ));

        // sentData/returnedData must stay a plain JSON string - never a
        // nested object a client might mistake for pre-parsed/escaped
        // markup.
        JsonNode node = objectMapper.valueToTree(transmission);
        assertThat(node.get("sentData").isTextual()).isTrue();
        assertThat(node.get("sentData").asText())
                .isEqualTo("<xml>sent</xml>");
    }

    @Test
    void attachmentShapeIsStableAndNeverExposesS3BucketOrKey()
            throws Exception {
        AwardAttachmentResponse attachment = new AwardAttachmentResponse(
                500L, "100004-00003", 1, "budget.pdf", "application/pdf",
                "Budget justification", "BUD", "COMPLETE", 1024L,
                "UPLOADED", true, LocalDateTime.of(2021, 1, 1, 0, 0)
        );

        assertFieldNames(attachment, Set.of(
                "awardAttachmentId", "awardNumber", "sequenceNumber",
                "fileName", "contentType", "description", "typeCode",
                "documentStatusCode", "fileSizeBytes", "uploadStatus",
                "downloadable", "oracleUpdateTimestamp"
        ));

        String json = objectMapper.writeValueAsString(attachment);
        assertThat(json).doesNotContainIgnoringCase("s3Bucket");
        assertThat(json).doesNotContainIgnoringCase("s3Key");
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
