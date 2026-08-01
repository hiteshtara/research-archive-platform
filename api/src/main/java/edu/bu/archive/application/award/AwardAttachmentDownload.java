package edu.bu.archive.application.award;

import java.io.InputStream;

public record AwardAttachmentDownload(
        String fileName,
        String mimeType,
        long contentLength,
        InputStream stream
) {
}
