package edu.bu.archive.application.award.report;

/**
 * What actually happened to one attachment while assembling the
 * consolidated report. Every attachment produces exactly one outcome, so
 * nothing can be silently dropped: an attachment is either EMBEDDED, or
 * it appears as an information page stating why it is not, and either way
 * it appears on the manifest.
 */
public record AwardReportAttachmentOutcome(
        AwardReportAttachment attachment,
        Status status,
        int embeddedPageCount
) {

    public enum Status {
        EMBEDDED("Embedded", null),
        NOT_ARCHIVED("Not archived",
                "Attachment not archived - the archive holds this "
                        + "attachment's metadata but its file was never "
                        + "captured from the source system."),
        NOT_PDF("Not embedded",
                "Attachment not embedded because the archived file is "
                        + "not a PDF."),
        EMPTY_FILE("Not embedded",
                "Attachment not embedded because the archived file is "
                        + "zero bytes."),
        CORRUPT_PDF("Not embedded",
                "Attachment not embedded because the archived PDF could "
                        + "not be read."),
        ENCRYPTED_PDF("Not embedded",
                "Attachment not embedded because the archived PDF is "
                        + "password-protected or encrypted."),
        STORAGE_MISSING("Not embedded",
                "Attachment not embedded because the archived file could "
                        + "not be found in archive storage."),
        STORAGE_ERROR("Not embedded",
                "Attachment not embedded because archive storage could "
                        + "not be read."),
        SKIPPED_LIMIT("Not embedded",
                "Attachment not embedded because this report reached its "
                        + "configured size or count limit.");

        private final String manifestLabel;
        private final String reason;

        Status(String manifestLabel, String reason) {
            this.manifestLabel = manifestLabel;
            this.reason = reason;
        }

        public String manifestLabel() {
            return manifestLabel;
        }

        /** Null for EMBEDDED; a user-facing sentence otherwise. */
        public String reason() {
            return reason;
        }
    }

    public boolean embedded() {
        return status == Status.EMBEDDED;
    }
}
