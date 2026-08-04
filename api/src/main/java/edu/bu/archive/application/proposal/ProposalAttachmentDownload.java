package edu.bu.archive.application.proposal;

import java.io.InputStream;

public record ProposalAttachmentDownload(
        String fileName,
        String mimeType,
        long contentLength,
        InputStream stream
) {
}
