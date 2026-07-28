package edu.bu.archive.application.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class PromptHash {

    private PromptHash() {
    }

    public static String sha256(
            String prompt
    ) {
        if (prompt == null) {
            throw new IllegalArgumentException(
                    "Prompt is required"
            );
        }
        try {
            byte[] digest = MessageDigest
                    .getInstance("SHA-256")
                    .digest(prompt.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is not available",
                    exception
            );
        }
    }
}
