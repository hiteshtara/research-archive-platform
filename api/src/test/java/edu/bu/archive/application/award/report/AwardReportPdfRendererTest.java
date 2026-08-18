package edu.bu.archive.application.award.report;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;

import edu.bu.archive.adapter.in.web.dto.award.AwardAmountHistoryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCommentsResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSapTransmissionResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardTermsResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardVersionSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneySummaryResponse;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * Exercises AwardReportPdfRenderer directly against synthetic
 * AwardReportData fixtures (never real archive data) and verifies the
 * actual rendered PDF bytes via OpenPDF's own PdfReader/
 * PdfTextExtractor - not just "it didn't throw". This is what proves
 * identifiers survive verbatim, sections paginate with repeated
 * headers, and no markup-escaping corruption occurs (every value is
 * added as literal Chunk/Phrase text, never parsed as HTML).
 */
class AwardReportPdfRendererTest {

    private final AwardReportPdfRenderer renderer =
            new AwardReportPdfRenderer(new edu.bu.archive.application.ai.SensitiveFieldRedactor());

    @Test
    void emptySectionsRenderSafelyWithNoExceptionsAndNoDataMessage() throws Exception {
        String text = renderToText(baseData());

        assertThat(text).contains("No archived data available.");
        assertThat(text).doesNotContain("null");
    }

    @Test
    void exactIdentifiersAndLeadingZerosArePreserved() throws Exception {
        AwardSummaryResponse summary = summary(
                "0900000-00001", "Leading Zero Award", "Active", 7, "0009941234"
        );
        AwardReportData data = withSummary(baseData(), summary);

        String text = renderToText(data);

        assertThat(text).contains("0900000-00001");
        assertThat(text).contains("0009941234");
    }

    @Test
    void versionsAppearInTheOrderProvidedNotResorted() throws Exception {
        List<AwardVersionSummaryResponse> versions = List.of(
                version(9001L, 5, "DOC-FIRST"),
                version(9002L, 3, "DOC-SECOND"),
                version(9003L, 1, "DOC-THIRD")
        );
        AwardReportData data = withVersions(baseData(), versions);

        String text = renderToText(data);

        // Matched on the numeric award_id, not the document_number
        // string - a narrow table column can legitimately word-wrap a
        // long hyphenated document number (as any real Award document
        // number could), which would make a literal substring match
        // unreliable here without reflecting an actual ordering bug.
        int firstIndex = text.indexOf("9001");
        int secondIndex = text.indexOf("9002");
        int thirdIndex = text.indexOf("9003");

        assertThat(firstIndex).isPositive();
        assertThat(secondIndex).isGreaterThan(firstIndex);
        assertThat(thirdIndex).isGreaterThan(secondIndex);
    }

    @Test
    void specialCharactersAndUnicodeAppearLiterallyUnescaped() throws Exception {
        String hostileTitle = "Award <script>alert(1)</script> & \"quoted\" café résumé";
        AwardSummaryResponse summary = summary(
                "900000-00001", hostileTitle, "Active", 1, "DOC-0001"
        );
        AwardReportData data = withSummary(baseData(), summary);

        String text = renderToText(data);

        assertThat(text).contains(hostileTitle);
        assertThat(text).doesNotContain("&lt;script&gt;");
        assertThat(text).doesNotContain("&amp;");
    }

    @Test
    void sapTransmissionAuthorizationHeaderIsRedactedNotRenderedVerbatim() throws Exception {
        // Mirrors the real shape found in archived Award SAP
        // transmission sentData/returnedData - a serialized HTTP
        // headers dump whose Authorization entry carries a real
        // Basic-auth credential (base64(username:password) - trivially
        // reversible). Synthetic credential value only; never the real
        // one.
        String rawSentData =
                "Headers: {SOAPAction=[urn:example], "
                        + "Authorization=[Basic c3ludGhldGljOnBhc3N3b3JkMTIz], "
                        + "Accept=[*/*]} Payload: <soap:Envelope><Body/></soap:Envelope>";

        AwardSapTransmissionResponse transmission = new AwardSapTransmissionResponse(
                1L, "900000-00001", 1, "INIT", "TX", "S", true,
                LocalDate.of(2020, 1, 1), "C", 1, "SPN", "M", "SAPDOC-1",
                rawSentData, null, List.of()
        );
        AwardReportData data = withSapTransmissions(baseData(), List.of(transmission));

        String text = renderToText(data);

        assertThat(text)
                .doesNotContain("c3ludGhldGljOnBhc3N3b3JkMTIz")
                .doesNotContain("Basic c3ludGhldGljOnBhc3N3b3JkMTIz")
                .contains("[REDACTED]")
                .contains("SOAPAction=[urn:example]")
                .contains("soap:Envelope");
    }

    @Test
    void largeAmountHistoryPaginatesWithRepeatedTableHeaders() throws Exception {
        List<AwardAmountHistoryResponse> amounts = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            amounts.add(new AwardAmountHistoryResponse(
                    (long) i, 5000L, "900000-00001", i,
                    BigDecimal.valueOf(1000 + i), BigDecimal.valueOf(200 + i),
                    BigDecimal.valueOf(1200 + i), BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.valueOf(1000 + i), BigDecimal.valueOf(200 + i),
                    BigDecimal.valueOf(1200 + i), LocalDate.of(2020, 1, 1).plusDays(i),
                    "DOC-" + i, 1L
            ));
        }
        AwardReportData data = withAmounts(baseData(), amounts);

        byte[] pdfBytes = render(data);
        PdfReader reader = new PdfReader(pdfBytes);
        try {
            assertThat(reader.getNumberOfPages()).isGreaterThan(3);

            String fullText = extractAllText(reader);
            // "Seq #" is the Amount History table's first header cell -
            // short enough to never line-wrap, so its occurrence count
            // is exactly the number of times the header row repeated
            // (once per page the table spans, via setHeaderRows(1)).
            long headerOccurrences = countOccurrences(fullText, "Seq #");
            assertThat(headerOccurrences).isGreaterThan(1);
        } finally {
            reader.close();
        }
    }

    @Test
    void reportDataContainsNoAttachmentOrStorageInternalFields() {
        Set<String> forbiddenFragments = Set.of(
                "s3", "bucket", "checksum", "attachment", "embedding",
                "credential", "presign", "objectkey", "storagekey"
        );

        for (RecordComponent component : allRecordComponentsRecursively(AwardReportData.class, new java.util.HashSet<>())) {
            String lowerName = component.getName().toLowerCase(java.util.Locale.ROOT);
            for (String forbidden : forbiddenFragments) {
                assertThat(lowerName)
                        .describedAs(
                                "AwardReportData (or a nested record) has a field "
                                        + "'%s' on %s that looks like an attachment/"
                                        + "storage-internal field - the Complete Award "
                                        + "Report must never include these",
                                component.getName(), component.getDeclaringRecord()
                        )
                        .doesNotContain(forbidden);
            }
        }
    }

    private static java.util.List<RecordComponent> allRecordComponentsRecursively(
            Class<?> type, java.util.Set<Class<?>> visited
    ) {
        java.util.List<RecordComponent> result = new ArrayList<>();
        if (!type.isRecord() || !visited.add(type)) {
            return result;
        }
        for (RecordComponent component : type.getRecordComponents()) {
            result.add(component);
            Class<?> componentType = component.getType();
            if (componentType.isRecord()) {
                result.addAll(allRecordComponentsRecursively(componentType, visited));
            } else if (java.util.List.class.isAssignableFrom(componentType)) {
                Class<?> elementType = genericListElementType(component);
                if (elementType != null) {
                    result.addAll(allRecordComponentsRecursively(elementType, visited));
                }
            }
        }
        return result;
    }

    private static Class<?> genericListElementType(RecordComponent component) {
        var genericType = component.getGenericType();
        if (genericType instanceof java.lang.reflect.ParameterizedType parameterized) {
            var typeArguments = parameterized.getActualTypeArguments();
            if (typeArguments.length == 1 && typeArguments[0] instanceof Class<?> elementType) {
                return elementType;
            }
        }
        return null;
    }

    // --- rendering helpers ---

    private byte[] render(AwardReportData data) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        renderer.render(data, out);
        return out.toByteArray();
    }

    private String renderToText(AwardReportData data) throws Exception {
        byte[] pdfBytes = render(data);
        PdfReader reader = new PdfReader(pdfBytes);
        try {
            return extractAllText(reader);
        } finally {
            reader.close();
        }
    }

    private static String extractAllText(PdfReader reader) throws Exception {
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        StringBuilder builder = new StringBuilder();
        for (int page = 1; page <= reader.getNumberOfPages(); page++) {
            builder.append(extractor.getTextFromPage(page)).append('\n');
        }
        return builder.toString();
    }

    private static long countOccurrences(String haystack, String needle) {
        long count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }

    // --- fixture builders (synthetic data only) ---

    private static AwardReportData baseData() {
        return new AwardReportData(
                summary("900000-00001", "Synthetic Test Award", "Active", 1, "DOC-0001"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                emptyBudgetSummary(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                emptyTimeAndMoneySummary(),
                List.of(),
                List.of(),
                new AwardTermsResponse(List.of(), List.of()),
                List.of(),
                new AwardCommentsResponse(List.of(), List.of()),
                List.of(),
                Instant.parse("2026-08-18T12:00:00Z")
        );
    }

    private static AwardReportData withSummary(AwardReportData base, AwardSummaryResponse summary) {
        return new AwardReportData(
                summary, base.versions(), base.people(), base.fundingProposals(),
                base.fundingSubawards(), base.associatedNegotiations(), base.amounts(),
                base.budgetSummary(), base.budgetVersions(), base.budgetPeriods(),
                base.budgetLineItems(), base.budgetPersonnel(), base.timeAndMoneySummary(),
                base.timeAndMoneyActions(), base.timeAndMoneyHistory(), base.terms(),
                base.customData(), base.comments(), base.sapTransmissions(), base.generatedAt()
        );
    }

    private static AwardReportData withVersions(
            AwardReportData base, List<AwardVersionSummaryResponse> versions
    ) {
        return new AwardReportData(
                base.summary(), versions, base.people(), base.fundingProposals(),
                base.fundingSubawards(), base.associatedNegotiations(), base.amounts(),
                base.budgetSummary(), base.budgetVersions(), base.budgetPeriods(),
                base.budgetLineItems(), base.budgetPersonnel(), base.timeAndMoneySummary(),
                base.timeAndMoneyActions(), base.timeAndMoneyHistory(), base.terms(),
                base.customData(), base.comments(), base.sapTransmissions(), base.generatedAt()
        );
    }

    private static AwardReportData withAmounts(
            AwardReportData base, List<AwardAmountHistoryResponse> amounts
    ) {
        return new AwardReportData(
                base.summary(), base.versions(), base.people(), base.fundingProposals(),
                base.fundingSubawards(), base.associatedNegotiations(), amounts,
                base.budgetSummary(), base.budgetVersions(), base.budgetPeriods(),
                base.budgetLineItems(), base.budgetPersonnel(), base.timeAndMoneySummary(),
                base.timeAndMoneyActions(), base.timeAndMoneyHistory(), base.terms(),
                base.customData(), base.comments(), base.sapTransmissions(), base.generatedAt()
        );
    }

    private static AwardReportData withSapTransmissions(
            AwardReportData base, List<AwardSapTransmissionResponse> sapTransmissions
    ) {
        return new AwardReportData(
                base.summary(), base.versions(), base.people(), base.fundingProposals(),
                base.fundingSubawards(), base.associatedNegotiations(), base.amounts(),
                base.budgetSummary(), base.budgetVersions(), base.budgetPeriods(),
                base.budgetLineItems(), base.budgetPersonnel(), base.timeAndMoneySummary(),
                base.timeAndMoneyActions(), base.timeAndMoneyHistory(), base.terms(),
                base.customData(), base.comments(), sapTransmissions, base.generatedAt()
        );
    }

    private static AwardSummaryResponse summary(
            String awardNumber, String title, String status, int sequenceNumber, String documentNumber
    ) {
        return new AwardSummaryResponse(
                5000L, awardNumber, sequenceNumber, title, status, "Test Sponsor", null,
                "Test PI", "Test Unit", LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 15),
                LocalDate.of(2020, 2, 1), null, BigDecimal.valueOf(100000), BigDecimal.valueOf(150000),
                "C", "Cost Reimbursement", "M", "Monthly", null, null, true, documentNumber
        );
    }

    private static AwardVersionSummaryResponse version(long awardId, int sequenceNumber, String documentNumber) {
        return new AwardVersionSummaryResponse(
                awardId, "900000-00001", sequenceNumber, "Active", "T", "Modification",
                LocalDate.of(2020, 1, 1), null, documentNumber, "1", sequenceNumber == 5
        );
    }

    private static AwardBudgetSummaryResponse emptyBudgetSummary() {
        return new AwardBudgetSummaryResponse(
                5000L, "900000-00001", 1, null, null, null, null, null,
                null, null, null, null, null, null, null
        );
    }

    private static TimeAndMoneySummaryResponse emptyTimeAndMoneySummary() {
        return new TimeAndMoneySummaryResponse(
                5000L, "900000-00001", 1, null, null, null, null, null, null,
                0L, null, null, null
        );
    }
}
