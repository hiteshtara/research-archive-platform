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
import java.io.IOException;
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
 * Tests for the consolidated Award report (report-with-attachments.pdf).
 *
 * These assert on REAL generated PDF bytes - page counts from PdfReader
 * and text from PdfTextExtractor - not on mock interactions, because the
 * whole point of the feature is what ends up in the merged document.
 */
class AwardConsolidatedReportAssemblerTest {

    private final AwardReportPdfRenderer renderer =
            new AwardReportPdfRenderer(
                    new edu.bu.archive.application.ai.SensitiveFieldRedactor());

    private static final class FakeStorage implements AwardAttachmentStorage {
        private final Map<Long, byte[]> bytes = new HashMap<>();
        private final Map<Long, RuntimeException> failures = new HashMap<>();

        void put(long id, byte[] content) {
            bytes.put(id, content);
        }

        void fail(long id, RuntimeException e) {
            failures.put(id, e);
        }

        @Override
        public StoredObject open(AwardArchivedAttachment attachment) {
            RuntimeException failure = failures.get(attachment.awardAttachmentId());
            if (failure != null) {
                throw failure;
            }
            byte[] content = bytes.get(attachment.awardAttachmentId());
            if (content == null) {
                throw new IllegalStateException("no fake content registered");
            }
            return new StoredObject(new ByteArrayInputStream(content), content.length);
        }
    }

    private static final class NoSuchKeyException extends RuntimeException {
    }

    private static byte[] pdfWith(int pages, String marker) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document();
        PdfWriter.getInstance(document, out);
        document.open();
        for (int i = 1; i <= pages; i++) {
            document.add(new Paragraph(marker + " page " + i));
            if (i < pages) {
                document.newPage();
            }
        }
        document.close();
        return out.toByteArray();
    }

    private static AwardReportAttachment attachment(
            long id, String fileName, String contentType, String uploadStatus,
            String bucket, String key, LocalDateTime ts
    ) {
        return new AwardReportAttachment(
                id, fileName, contentType, "Description " + id, "TYPE" + id,
                "FINAL", 1024L, uploadStatus, bucket, key, ts, "user" + id);
    }

    private static AwardReportAttachment archived(
            long id, String fileName, String contentType, LocalDateTime ts) {
        return attachment(id, fileName, contentType, "UPLOADED",
                "bucket", "key/" + id, ts);
    }

    private AwardConsolidatedReportAssembler assembler(
            AwardAttachmentStorage storage) {
        return new AwardConsolidatedReportAssembler(
                renderer, storage, 250L * 1024 * 1024, 250);
    }

    private AwardConsolidatedReportAssembler assembler(
            AwardAttachmentStorage storage, long maxBytes, int maxCount) {
        return new AwardConsolidatedReportAssembler(
                renderer, storage, maxBytes, maxCount);
    }

    private byte[] assemble(
            AwardConsolidatedReportAssembler assembler,
            List<AwardReportAttachment> attachments
    ) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assembler.assemble(baseData(), attachments, out);
        return out.toByteArray();
    }

    private static String textOf(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            StringBuilder all = new StringBuilder();
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                all.append(extractor.getTextFromPage(page)).append('\n');
            }
            return all.toString();
        } finally {
            reader.close();
        }
    }

    private static int pageCount(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            return reader.getNumberOfPages();
        } finally {
            reader.close();
        }
    }

    private byte[] reportOnly() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        renderer.render(baseData(), out);
        return out.toByteArray();
    }

    @Test
    void awardWithNoAttachmentsProducesTheReportAlone() throws Exception {
        byte[] pdf = assemble(assembler(new FakeStorage()), List.of());

        assertThat(pageCount(pdf)).isEqualTo(pageCount(reportOnly()));
        assertThat(textOf(pdf))
                .as("no manifest section when there are no attachments")
                .doesNotContain("Attachment not embedded");
    }

    @Test
    void singleArchivedPdfIsEmbeddedAfterTheReport() throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.put(1L, pdfWith(2, "ALPHA"));

        byte[] pdf = assemble(assembler(storage), List.of(
                archived(1L, "alpha.pdf", "application/pdf",
                        LocalDateTime.of(2020, 1, 1, 0, 0))));

        // report + its Attachments manifest + the 2 embedded pages.
        assertThat(pageCount(pdf))
                .isGreaterThanOrEqualTo(pageCount(reportOnly()) + 2);
        assertThat(textOf(pdf)).contains("ALPHA page 1", "ALPHA page 2");
        assertThat(textOf(pdf))
                .as("the consolidated report carries a manifest the plain one does not")
                .contains("alpha.pdf");
    }

    @Test
    void multipleArchivedPdfsAreAllEmbedded() throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.put(1L, pdfWith(1, "ONE"));
        storage.put(2L, pdfWith(3, "TWO"));

        byte[] pdf = assemble(assembler(storage), List.of(
                archived(1L, "one.pdf", "application/pdf",
                        LocalDateTime.of(2021, 1, 1, 0, 0)),
                archived(2L, "two.pdf", "application/pdf",
                        LocalDateTime.of(2020, 1, 1, 0, 0))));

        assertThat(pageCount(pdf))
                .isGreaterThanOrEqualTo(pageCount(reportOnly()) + 4);
        assertThat(textOf(pdf)).contains("ONE page 1", "TWO page 3");
    }

    @Test
    void generatedReportPagesComeFirstAndAttachmentsAfter() throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.put(1L, pdfWith(1, "ATTACHMENTBODY"));

        byte[] pdf = assemble(assembler(storage), List.of(
                archived(1L, "a.pdf", "application/pdf",
                        LocalDateTime.of(2020, 1, 1, 0, 0))));

        PdfReader reader = new PdfReader(pdf);
        try {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            String firstPage = extractor.getTextFromPage(1);
            String lastPage = extractor.getTextFromPage(reader.getNumberOfPages());
            assertThat(firstPage).doesNotContain("ATTACHMENTBODY");
            assertThat(lastPage).contains("ATTACHMENTBODY");
            assertThat(reader.getNumberOfPages())
                    .isGreaterThan(pageCount(reportOnly()) - 1);
        } finally {
            reader.close();
        }
    }

    @Test
    void attachmentsAreOrderedAsSupplied() throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.put(1L, pdfWith(1, "FIRSTDOC"));
        storage.put(2L, pdfWith(1, "SECONDDOC"));
        storage.put(3L, pdfWith(1, "THIRDDOC"));

        byte[] pdf = assemble(assembler(storage), List.of(
                archived(3L, "c.pdf", "application/pdf",
                        LocalDateTime.of(2022, 1, 1, 0, 0)),
                archived(2L, "b.pdf", "application/pdf",
                        LocalDateTime.of(2021, 1, 1, 0, 0)),
                archived(1L, "a.pdf", "application/pdf",
                        LocalDateTime.of(2020, 1, 1, 0, 0))));

        String text = textOf(pdf);
        assertThat(text.indexOf("THIRDDOC")).isLessThan(text.indexOf("SECONDDOC"));
        assertThat(text.indexOf("SECONDDOC")).isLessThan(text.indexOf("FIRSTDOC"));
    }

    @Test
    void realPdfWithCorruptedContentTypeIsStillEmbedded() throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.put(1L, pdfWith(1, "ESCAPEDTYPE"));

        byte[] pdf = assemble(assembler(storage), List.of(
                archived(1L, "weird.pdf", "\"\\\"application/pdf\\\"\"",
                        LocalDateTime.of(2020, 1, 1, 0, 0))));

        assertThat(textOf(pdf))
                .as("a genuine PDF must embed regardless of its recorded MIME type")
                .contains("ESCAPEDTYPE page 1");
    }

    @Test
    void fileClaimingToBePdfButIsNotGetsAnInformationPage() throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.put(1L, "PK zip not a pdf".getBytes(StandardCharsets.UTF_8));

        byte[] pdf = assemble(assembler(storage), List.of(
                archived(1L, "lies.pdf", "application/pdf",
                        LocalDateTime.of(2020, 1, 1, 0, 0))));

        String text = textOf(pdf);
        assertThat(text).contains("Attachment not embedded");
        assertThat(text).contains("is not a PDF");
        assertThat(text).contains("lies.pdf");
    }

    @Test
    void nonPdfAttachmentGetsAnInformationPageWithMetadata() throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.put(1L, "a,b,c".getBytes(StandardCharsets.UTF_8));

        byte[] pdf = assemble(assembler(storage), List.of(
                archived(1L, "sheet.xlsx",
                        "application/vnd.openxmlformats-officedocument"
                                + ".spreadsheetml.sheet",
                        LocalDateTime.of(2020, 3, 4, 5, 6))));

        String text = textOf(pdf);
        assertThat(text).contains("sheet.xlsx");
        assertThat(text).contains("Attachment not embedded");
        assertThat(text).contains("user1");
    }

    @Test
    void notArchivedAttachmentGetsAnInformationPage() throws Exception {
        byte[] pdf = assemble(assembler(new FakeStorage()), List.of(
                attachment(1L, "never.pdf", "application/pdf",
                        "MISSING_SOURCE_CONTENT", null, null,
                        LocalDateTime.of(2020, 1, 1, 0, 0))));

        String text = textOf(pdf);
        assertThat(text).contains("Attachment not archived");
        assertThat(text).contains("never.pdf");
    }

    @Test
    void zeroByteFileGetsAnInformationPage() throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.put(1L, new byte[0]);

        byte[] pdf = assemble(assembler(storage), List.of(
                archived(1L, "empty.pdf", "application/pdf",
                        LocalDateTime.of(2020, 1, 1, 0, 0))));

        assertThat(textOf(pdf)).contains("zero bytes");
    }

    @Test
    void corruptPdfGetsAnInformationPageAndDoesNotFailTheReport()
            throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.put(1L, "%PDF-1.4 truncated garbage".getBytes(StandardCharsets.UTF_8));

        byte[] pdf = assemble(assembler(storage), List.of(
                archived(1L, "broken.pdf", "application/pdf",
                        LocalDateTime.of(2020, 1, 1, 0, 0))));

        String text = textOf(pdf);
        assertThat(text).contains("could not be read");
        assertThat(text).contains("broken.pdf");
    }

    @Test
    void missingStorageObjectGetsAnInformationPage() throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.fail(1L, new NoSuchKeyException());

        byte[] pdf = assemble(assembler(storage), List.of(
                archived(1L, "gone.pdf", "application/pdf",
                        LocalDateTime.of(2020, 1, 1, 0, 0))));

        assertThat(textOf(pdf)).contains("could not be found in archive storage");
    }

    @Test
    void storageAccessErrorGetsAnInformationPage() throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.fail(1L, new RuntimeException("S3 access denied"));

        byte[] pdf = assemble(assembler(storage), List.of(
                archived(1L, "denied.pdf", "application/pdf",
                        LocalDateTime.of(2020, 1, 1, 0, 0))));

        String text = textOf(pdf);
        assertThat(text).contains("archive storage could not be read");
        assertThat(text)
                .as("storage internals must never leak into the PDF")
                .doesNotContain("key/1");
    }

    @Test
    void oneBadAttachmentDoesNotPreventOthersFromEmbedding() throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.put(1L, "%PDF-1.4 broken".getBytes(StandardCharsets.UTF_8));
        storage.put(2L, pdfWith(1, "GOODDOC"));

        byte[] pdf = assemble(assembler(storage), List.of(
                archived(1L, "bad.pdf", "application/pdf",
                        LocalDateTime.of(2021, 1, 1, 0, 0)),
                archived(2L, "good.pdf", "application/pdf",
                        LocalDateTime.of(2020, 1, 1, 0, 0))));

        String text = textOf(pdf);
        assertThat(text).contains("could not be read");
        assertThat(text).contains("GOODDOC page 1");
    }

    @Test
    void manifestListsEveryAttachmentWithArchivedAndEmbeddedStatus()
            throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.put(1L, pdfWith(1, "EMBEDDABLE"));
        storage.put(2L, "not a pdf".getBytes(StandardCharsets.UTF_8));

        byte[] pdf = assemble(assembler(storage), List.of(
                archived(1L, "yes.pdf", "application/pdf",
                        LocalDateTime.of(2021, 1, 1, 0, 0)),
                archived(2L, "no.docx", "application/msword",
                        LocalDateTime.of(2020, 1, 1, 0, 0)),
                attachment(3L, "missing.pdf", "application/pdf",
                        "MISSING_SOURCE_CONTENT", null, null,
                        LocalDateTime.of(2019, 1, 1, 0, 0))));

        String text = textOf(pdf);
        assertThat(text).contains("Attachments");
        assertThat(text).contains("yes.pdf", "no.docx", "missing.pdf");
        assertThat(text).contains("Embedded");
        assertThat(text).contains("Not archived");
    }

    @Test
    void guardrailOnCountStillProducesReportAndNamesOmittedAttachments()
            throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.put(1L, pdfWith(1, "KEPTDOC"));
        storage.put(2L, pdfWith(1, "DROPPEDDOC"));

        byte[] pdf = assemble(assembler(storage, 250L * 1024 * 1024, 1), List.of(
                archived(1L, "kept.pdf", "application/pdf",
                        LocalDateTime.of(2021, 1, 1, 0, 0)),
                archived(2L, "dropped.pdf", "application/pdf",
                        LocalDateTime.of(2020, 1, 1, 0, 0))));

        String text = textOf(pdf);
        assertThat(text).contains("KEPTDOC page 1");
        assertThat(text).doesNotContain("DROPPEDDOC page 1");
        assertThat(text).contains("size or count limit");
        assertThat(text).contains("dropped.pdf");
    }

    @Test
    void guardrailOnAggregateBytesStillProducesReport() throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.put(1L, pdfWith(1, "FIRSTKEPT"));
        storage.put(2L, pdfWith(1, "SECONDSKIPPED"));

        byte[] pdf = assemble(assembler(storage, 1L, 250), List.of(
                archived(1L, "one.pdf", "application/pdf",
                        LocalDateTime.of(2021, 1, 1, 0, 0)),
                archived(2L, "two.pdf", "application/pdf",
                        LocalDateTime.of(2020, 1, 1, 0, 0))));

        String text = textOf(pdf);
        assertThat(text).contains("size or count limit");
        assertThat(pageCount(pdf)).isGreaterThan(1);
    }

    @Test
    void tempFilesAreCleanedUpOnSuccess() throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.put(1L, pdfWith(1, "CLEANUP"));
        long before = countAwardReportTempDirs();

        assemble(assembler(storage), List.of(
                archived(1L, "a.pdf", "application/pdf",
                        LocalDateTime.of(2020, 1, 1, 0, 0))));

        assertThat(countAwardReportTempDirs()).isEqualTo(before);
    }

    @Test
    void tempFilesAreCleanedUpWhenTheOutputStreamFails() throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.put(1L, pdfWith(1, "CLEANUP"));
        long before = countAwardReportTempDirs();

        java.io.OutputStream exploding = new java.io.OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("client disconnected");
            }
        };

        try {
            assembler(storage).assemble(baseData(), List.of(
                    archived(1L, "a.pdf", "application/pdf",
                            LocalDateTime.of(2020, 1, 1, 0, 0))), exploding);
        } catch (Exception expected) {
            // the download fails; temp files must still be gone
        }

        assertThat(countAwardReportTempDirs())
                .as("finally-block cleanup must run on failure too")
                .isEqualTo(before);
    }

    private static long countAwardReportTempDirs() throws IOException {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        try (var paths = Files.list(tmp)) {
            return paths.filter(p -> p.getFileName().toString()
                    .startsWith("award-report-")).count();
        }
    }

    private static AwardReportData baseData() {
        return new AwardReportData(
                summary(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                new AwardBudgetSummaryResponse(
                        5000L, "900000-00001", 1, null, null, null, null, null,
                        null, null, null, null, null, null, null),
                List.of(), List.of(), List.of(), List.of(),
                new TimeAndMoneySummaryResponse(
                        5000L, "900000-00001", 1, null, null, null, null, null,
                        null, 0L, null, null, null),
                List.of(), List.of(),
                new AwardTermsResponse(List.of(), List.of()),
                List.of(),
                new AwardCommentsResponse(List.of(), List.of()),
                List.of(), Instant.parse("2026-01-01T00:00:00Z"));
    }

    /*
     * V078 widened AwardSummaryResponse from 23 to 39 components and
     * regrouped them, so this fixture is written one-per-line: a
     * positional record this wide is unreadable inline and a silently
     * shifted argument would still compile.
     */
    private static AwardSummaryResponse summary() {
        return new AwardSummaryResponse(
                5000L,                  // awardId
                "900000-00001",         // awardNumber
                1,                      // sequenceNumber
                "Synthetic Test Award", // title
                "Active",               // status
                null,                   // grantNumber
                "Test Unit",            // leadUnit
                null,                   // accountType
                null,                   // activityType
                null,                   // awardType
                null,                   // federalClinicalTrial
                "Test Sponsor",         // sponsor
                null,                   // sponsorCode
                null,                   // sponsorAwardNumber
                null,                   // primeSponsor
                null,                   // primeSponsorCode
                null,                   // primeSponsorAwardId
                null,                   // modificationNumber
                null,                   // fainId
                null,                   // nsfScienceCode
                null,                   // nsfSequenceNumber
                null,                   // alnNumber
                null,                   // alnProgramTitleName
                null,                   // awardEffectiveDate
                null,                   // obligationStartDate
                null,                   // awardExecutionDate
                null,                   // beginDate
                null,                   // closeoutDate
                BigDecimal.ZERO,        // obligatedTotalAmount
                BigDecimal.ZERO,        // anticipatedTotalAmount
                null,                   // basisOfPaymentCode
                null,                   // basisOfPaymentDescription
                null,                   // methodOfPaymentCode
                null,                   // methodOfPaymentDescription
                null,                   // principalInvestigator
                null,                   // rootAwardNumber
                null,                   // parentAwardNumber
                true,                   // primaryCurrent
                "DOC-0001"              // documentNumber
        );
    }
}
