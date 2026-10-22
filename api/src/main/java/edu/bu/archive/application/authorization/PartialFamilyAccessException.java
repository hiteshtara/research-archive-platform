package edu.bu.archive.application.authorization;

/**
 * The caller may open this Award but not every version of its family, and
 * the endpoint (AI summary / questions / evidence search) builds its answer
 * from the whole family. Refused (403) rather than answered from a partial
 * family; whether partial-family AI should ever exist is policy P3.
 */
public class PartialFamilyAccessException extends RuntimeException {
    public static final String CODE = "AI_NOT_AVAILABLE_FOR_PARTIAL_ACCESS";

    public PartialFamilyAccessException() {
        super(CODE);
    }
}
