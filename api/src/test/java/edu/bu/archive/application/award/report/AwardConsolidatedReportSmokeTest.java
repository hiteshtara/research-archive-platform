package edu.bu.archive.application.award.report;

import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCommentsResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardTermsResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneySummaryResponse;
import edu.bu.archive.adapter.out.persistence.AwardArchivedAttachment;
import edu.bu.archive.adapter.out.persistence.AwardAttachmentStorage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end smoke test: one fixture Award carrying a real archived PDF,
 * a non-PDF, and a metadata-only attachment, asserted on the actual
 * bytes of the produced document rather than on any intermediate model.
 *
 * Writes the PDF to target/ so it can be opened by hand.
 */
class AwardConsolidatedReportSmokeTest {

    private final AwardReportPdfRenderer renderer =
            new AwardReportPdfRenderer(
                    new edu.bu.archive.application.ai.SensitiveFieldRedactor());

    private static final class FixtureStorage implements AwardAttachmentStorage {
        private final Map<Long, byte[]> bytes = new HashMap<>();

        void put(long id, byte[] content) {
            bytes.put(id, content);
        }

        @Override
        public StoredObject open(AwardArchivedAttachment attachment) {
            byte[] content = bytes.get(attachment.awardAttachmentId());
            if (content == null) {
                throw new IllegalStateException("missing fixture");
            }
            return new StoredObject(new ByteArrayInputStream(content), content.length);
        }
    }

    @Test
    void consolidatedReportOpensAndContainsEveryExpectedPart() throws Exception {
        FixtureStorage storage = new FixtureStorage();
        storage.put(101L, realPdf());
        storage.put(102L, "PK spreadsheet bytes".getBytes(StandardCharsets.UTF_8));

        List<AwardReportAttachment> attachments = List.of(
                new AwardReportAttachment(101L, "Proposal_Narrative.pdf",
                        "\"\\\"application/pdf\\\"\"", "Signed proposal narrative",
                        "PROPOSAL", "FINAL", 2048L, "UPLOADED", "bucket",
                        "key/101", LocalDateTime.of(2022, 5, 4, 9, 30), "jsmith"),
                new AwardReportAttachment(102L, "Budget_Worksheet.xlsx",
                        "application/vnd.openxmlformats-officedocument"
                                + ".spreadsheetml.sheet",
                        "Detailed budget", "BUDGET", "FINAL", 4096L, "UPLOADED",
                        "bucket", "key/102",
                        LocalDateTime.of(2021, 8, 2, 14, 15), "mjones"),
                new AwardReportAttachment(103L, "Legacy_Correspondence.pdf",
                        "application/pdf", "Never captured from source",
                        "CORRESPONDENCE", "FINAL", null,
                        "MISSING_SOURCE_CONTENT", null, null,
                        LocalDateTime.of(2019, 3, 1, 8, 0), "legacy"));

        AwardConsolidatedReportAssembler assembler =
                new AwardConsolidatedReportAssembler(
                        renderer, storage, 250L * 1024 * 1024, 250);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assembler.assemble(fixtureData(), attachments, out);
        byte[] pdf = out.toByteArray();

        Path artifact = Path.of("target", "smoke-consolidated-award-report.pdf");
        Files.createDirectories(artifact.getParent());
        Files.write(artifact, pdf);

        // 1. it is a readable PDF that opens
        PdfReader reader = new PdfReader(pdf);
        try {
            assertThat(reader.getNumberOfPages()).isGreaterThan(3);

            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder all = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                all.append(extractor.getTextFromPage(page)).append('\n');
            }
            String text = all.toString();

            // 2. Award report first
            assertThat(extractor.getTextFromPage(1))
                    .contains("105698-00001");
            assertThat(extractor.getTextFromPage(1))
                    .doesNotContain("SMOKEPDFBODY");

            // 3. attachment manifest, listing all three
            assertThat(text).contains("Attachments");
            assertThat(text).contains("Proposal_Narrative.pdf");
            assertThat(text).contains("Budget_Worksheet.xlsx");
            assertThat(text).contains("Legacy_Correspondence.pdf");
            assertThat(text).contains("Not archived");

            // 4. embedded PDF pages - despite a 3x-escaped content type
            assertThat(text).contains("SMOKEPDFBODY page 1");
            assertThat(text).contains("SMOKEPDFBODY page 2");

            // 5. information page for the non-PDF
            assertThat(text).contains("is not a PDF");
            assertThat(text).contains("mjones");

            // 6. information page for the metadata-only attachment
            assertThat(text).contains("Attachment not archived");
            assertThat(text).contains("legacy");

            // 7. no storage internals anywhere in the document
            assertThat(text).doesNotContain("key/101");
            assertThat(text).doesNotContain("key/102");
        } finally {
            reader.close();
        }

        System.out.println("SMOKE ARTIFACT: " + artifact.toAbsolutePath()
                + " (" + pdf.length + " bytes)");
    }

    private static byte[] realPdf() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document();
        PdfWriter.getInstance(document, out);
        document.open();
        document.add(new Paragraph("SMOKEPDFBODY page 1"));
        document.newPage();
        document.add(new Paragraph("SMOKEPDFBODY page 2"));
        document.close();
        return out.toByteArray();
    }

    private static AwardReportData fixtureData() {
        return new AwardReportData(
                new AwardSummaryResponse(
                        2727052L,                          // awardId
                        "105698-00001",                    // awardNumber
                        20,                                // sequenceNumber
                        "Autism Study",                    // title
                        "Closed",                          // status
                        null,                              // grantNumber
                        "SAR OCCUPATIONAL THERAPY",        // leadUnit
                        null,                              // accountType
                        null,                              // activityType
                        null,                              // awardType
                        null,                              // federalClinicalTrial
                        "University of Wisconsin System",  // sponsor
                        null,                              // sponsorCode
                        null,                              // sponsorAwardNumber
                        "NIH/National Institute on Aging", // primeSponsor
                        null,                              // primeSponsorCode
                        null,                              // primeSponsorAwardId
                        null,                              // modificationNumber
                        null,                              // fainId
                        null,                              // nsfScienceCode
                        null,                              // nsfSequenceNumber
                        null,                              // alnNumber
                        null,                              // alnProgramTitleName
                        null,                              // awardEffectiveDate
                        null,                              // obligationStartDate
                        null,                              // awardExecutionDate
                        null,                              // beginDate
                        null,                              // closeoutDate
                        BigDecimal.ZERO,                   // obligatedTotalAmount
                        BigDecimal.ZERO,                   // anticipatedTotalAmount
                        null,                              // basisOfPaymentCode
                        null,                              // basisOfPaymentDescription
                        null,                              // methodOfPaymentCode
                        null,                              // methodOfPaymentDescription
                        "GAEL I ORSMOND",                  // principalInvestigator
                        null,                              // rootAwardNumber
                        null,                              // parentAwardNumber
                        true,                              // primaryCurrent
                        "771264"                           // documentNumber
                ),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                new AwardBudgetSummaryResponse(
                        2727052L, "105698-00001", 20, null, null, null, null,
                        null, null, null, null, null, null, null, null),
                List.of(), List.of(), List.of(), List.of(),
                new TimeAndMoneySummaryResponse(
                        2727052L, "105698-00001", 20, null, null, null, null,
                        null, null, 0L, null, null, null),
                List.of(), List.of(),
                new AwardTermsResponse(List.of(), List.of()),
                List.of(),
                new AwardCommentsResponse(List.of(), List.of()),
                List.of(), Instant.parse("2026-09-22T12:00:00Z"));
    }
}
