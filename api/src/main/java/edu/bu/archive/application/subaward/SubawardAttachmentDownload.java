package edu.bu.archive.application.subaward;

import java.io.InputStream;

public record SubawardAttachmentDownload(
        String fileName,
        String mimeType,
        long contentLength,
        InputStream stream
) {
}
