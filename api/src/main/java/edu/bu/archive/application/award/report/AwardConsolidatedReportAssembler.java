package edu.bu.archive.application.award.report;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.pdf.PdfCopy;
import com.lowagie.text.pdf.PdfReader;
import edu.bu.archive.adapter.out.persistence.AwardArchivedAttachment;
import edu.bu.archive.adapter.out.persistence.AwardAttachmentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the consolidated Award PDF: the generated Award report first,
 * then every version-scoped attachment in archive order - each either
 * embedded page-for-page or represented by an information page saying why
 * it is not.
 *
 * MEMORY: nothing is aggregated in the heap. The report is rendered to a
 * temp file; each attachment is streamed S3 -> temp file; the merge
 * streams page-by-page into the response. Peak heap is roughly one
 * PdfReader over the largest single attachment (95.9 MiB observed across
 * the whole archive), not the aggregate (444 MiB worst case per version).
 * Every temp file is deleted in a finally block, on success and failure
 * alike.
 *
 * PDF DETECTION: by sniffing the %PDF- header, never by content_type.
 * ~210,000 archived rows carry repeatedly JSON-escaped content types
 * ("application/pdf", "\\"application/pdf\\"", deeper still) and ~52,000
 * are pure escape-character garbage; trusting the column would misfile a
 * large minority of genuine PDFs as non-PDF. The fixture family alone
 * spans four spellings of application/pdf. Sniffing is also the only
 * defence against a mislabelled file.
 *
 * NO RASTERIZATION: PdfCopy copies page objects, preserving original page
 * size, orientation and quality.
 */
@Component
public class AwardConsolidatedReportAssembler {

    private static final Logger log =
            LoggerFactory.getLogger(AwardConsolidatedReportAssembler.class);

    /** Every PDF begins with these bytes. */
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};

    private final AwardReportPdfRenderer renderer;
    private final AwardAttachmentStorage attachmentStorage;
    private final long maxAggregateBytes;
    private final int maxAttachmentCount;

    public AwardConsolidatedReportAssembler(
            AwardReportPdfRenderer renderer,
            AwardAttachmentStorage attachmentStorage,
            @Value("${app.award.report.max-attachment-bytes:262144000}")
            long maxAggregateBytes,
            @Value("${app.award.report.max-attachment-count:250}")
            int maxAttachmentCount
    ) {
        this.renderer = renderer;
        this.attachmentStorage = attachmentStorage;
        this.maxAggregateBytes = maxAggregateBytes;
        this.maxAttachmentCount = maxAttachmentCount;
    }

    /**
     * Streams the consolidated PDF. Never throws merely because an
     * attachment was unusable or a limit was reached - the report and its
     * manifest are always produced, and the affected attachments are
     * named on information pages instead.
     */
    public void assemble(
            AwardReportData data,
            List<AwardReportAttachment> attachments,
            OutputStream outputStream
    ) throws IOException {
        Path workDir = Files.createTempDirectory("award-report-");
        try {
            List<Prepared> prepared = prepare(attachments, workDir);

            List<AwardReportAttachmentOutcome> manifest = new ArrayList<>();
            for (Prepared item : prepared) {
                manifest.add(new AwardReportAttachmentOutcome(
                        item.attachment(), item.status(), item.pageCount()));
            }

            // The report is rendered only AFTER every attachment has been
            // classified, so the manifest can state each one's real
            // outcome rather than a guess.
            Path reportFile = workDir.resolve("report.pdf");
            try (OutputStream reportOut = Files.newOutputStream(reportFile)) {
                renderer.render(data, manifest, reportOut);
            } catch (DocumentException e) {
                throw new IOException("Failed to render Award report PDF", e);
            }

            merge(reportFile, prepared, manifest, workDir, outputStream);
        } finally {
            deleteRecursively(workDir);
        }
    }

    // --- phase 1: fetch + classify, one temp file per attachment --------
    private List<Prepared> prepare(
            List<AwardReportAttachment> attachments,
            Path workDir
    ) {
        List<Prepared> prepared = new ArrayList<>();
        long aggregate = 0;
        int embeddedSoFar = 0;

        for (AwardReportAttachment attachment : attachments) {
            if (!attachment.hasArchivedObject()) {
                prepared.add(Prepared.unavailable(attachment,
                        AwardReportAttachmentOutcome.Status.NOT_ARCHIVED));
                continue;
            }
            if (embeddedSoFar >= maxAttachmentCount
                    || aggregate >= maxAggregateBytes) {
                prepared.add(Prepared.unavailable(attachment,
                        AwardReportAttachmentOutcome.Status.SKIPPED_LIMIT));
                continue;
            }

            Path file = workDir.resolve("att-" + attachment.awardAttachmentId() + ".bin");
            long written;
            try {
                written = download(attachment, file);
            } catch (StorageMissingException e) {
                prepared.add(Prepared.unavailable(attachment,
                        AwardReportAttachmentOutcome.Status.STORAGE_MISSING));
                continue;
            } catch (Exception e) {
                // Deliberately broad: no single attachment may abort the
                // report. The failure is surfaced on its own page.
                log.warn("Award attachment {} could not be read from storage: {}",
                        attachment.awardAttachmentId(), e.toString());
                prepared.add(Prepared.unavailable(attachment,
                        AwardReportAttachmentOutcome.Status.STORAGE_ERROR));
                continue;
            }

            if (written == 0) {
                prepared.add(Prepared.unavailable(attachment,
                        AwardReportAttachmentOutcome.Status.EMPTY_FILE));
                continue;
            }
            if (!looksLikePdf(file)) {
                prepared.add(Prepared.unavailable(attachment,
                        AwardReportAttachmentOutcome.Status.NOT_PDF));
                continue;
            }

            int pages;
            try {
                pages = pageCountOf(file);
            } catch (EncryptedPdfException e) {
                prepared.add(Prepared.unavailable(attachment,
                        AwardReportAttachmentOutcome.Status.ENCRYPTED_PDF));
                continue;
            } catch (Exception e) {
                log.warn("Award attachment {} is not a readable PDF: {}",
                        attachment.awardAttachmentId(), e.toString());
                prepared.add(Prepared.unavailable(attachment,
                        AwardReportAttachmentOutcome.Status.CORRUPT_PDF));
                continue;
            }

            aggregate += written;
            embeddedSoFar++;
            prepared.add(new Prepared(attachment,
                    AwardReportAttachmentOutcome.Status.EMBEDDED, file, pages));
        }
        return prepared;
    }

    private long download(AwardReportAttachment attachment, Path target)
            throws IOException {
        AwardArchivedAttachment row = new AwardArchivedAttachment(
                attachment.awardAttachmentId(),
                0L,
                attachment.fileName(),
                attachment.contentType(),
                attachment.s3Bucket(),
                attachment.s3Key(),
                attachment.fileSizeBytes(),
                attachment.uploadStatus()
        );
        AwardAttachmentStorage.StoredObject object;
        try {
            object = attachmentStorage.open(row);
        } catch (RuntimeException e) {
            if (isMissingObject(e)) {
                throw new StorageMissingException(e);
            }
            throw e;
        }
        try (InputStream in = object.stream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return Files.size(target);
    }

    private static boolean isMissingObject(RuntimeException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String name = t.getClass().getSimpleName();
            if (name.contains("NoSuchKey") || name.contains("NoSuchBucket")
                    || name.contains("FileNotFound")
                    || name.contains("NoSuchFile")) {
                return true;
            }
        }
        return false;
    }

    /** Header sniff. content_type is never consulted. */
    static boolean looksLikePdf(Path file) {
        byte[] head = new byte[PDF_MAGIC.length];
        try (InputStream in = Files.newInputStream(file)) {
            int read = in.readNBytes(head, 0, head.length);
            if (read < head.length) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (head[i] != PDF_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static int pageCountOf(Path file) throws IOException {
        PdfReader reader = new PdfReader(file.toString());
        try {
            if (reader.isEncrypted()) {
                throw new EncryptedPdfException();
            }
            return reader.getNumberOfPages();
        } finally {
            reader.close();
        }
    }

    // --- phase 2: page-level merge, streamed ----------------------------
    private void merge(
            Path reportFile,
            List<Prepared> prepared,
            List<AwardReportAttachmentOutcome> manifest,
            Path workDir,
            OutputStream outputStream
    ) throws IOException {
        Document document = new Document();
        PdfCopy copy;
        try {
            copy = new PdfCopy(document, outputStream);
        } catch (DocumentException e) {
            throw new IOException("Failed to open consolidated PDF", e);
        }
        document.open();
        try {
            appendAll(copy, reportFile);

            for (int i = 0; i < prepared.size(); i++) {
                Prepared item = prepared.get(i);
                if (item.status() == AwardReportAttachmentOutcome.Status.EMBEDDED) {
                    appendAll(copy, item.file());
                } else {
                    Path info = workDir.resolve("info-" + i + ".pdf");
                    try (OutputStream out = Files.newOutputStream(info)) {
                        renderer.renderAttachmentInformationPage(
                                manifest.get(i), out);
                    } catch (DocumentException e) {
                        throw new IOException(
                                "Failed to render attachment information page", e);
                    }
                    appendAll(copy, info);
                }
            }
        } finally {
            document.close();
        }
    }

    private static void appendAll(PdfCopy copy, Path pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf.toString());
        try {
            int pages = reader.getNumberOfPages();
            for (int page = 1; page <= pages; page++) {
                try {
                    copy.addPage(copy.getImportedPage(reader, page));
                } catch (Exception e) {
                    throw new IOException(
                            "Failed to copy page " + page + " of " + pdf.getFileName(), e);
                }
            }
        } finally {
            reader.close();
        }
    }

    private static void deleteRecursively(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // Best effort: a leftover temp file must never
                            // fail a download that already succeeded.
                        }
                    });
        } catch (IOException e) {
            log.warn("Could not clean consolidated report temp dir {}: {}",
                    dir, e.toString());
        }
    }

    private record Prepared(
            AwardReportAttachment attachment,
            AwardReportAttachmentOutcome.Status status,
            Path file,
            int pageCount
    ) {
        static Prepared unavailable(
                AwardReportAttachment attachment,
                AwardReportAttachmentOutcome.Status status
        ) {
            return new Prepared(attachment, status, null, 0);
        }
    }

    private static final class EncryptedPdfException extends RuntimeException {
    }

    private static final class StorageMissingException extends RuntimeException {
        StorageMissingException(Throwable cause) {
            super(cause);
        }
    }
}
