package edu.bu.archive.application.negotiation;

import java.io.InputStream;

public record NegotiationAttachmentDownload(
        String fileName,
        String mimeType,
        long contentLength,
        InputStream stream
) {
}
