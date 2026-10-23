package edu.bu.archive.application.authorization;

/** The caller's BU sign-in is missing or too old: 401, sign in again. No detail. */
public class ReauthenticationRequiredException extends RuntimeException {

    public static final String CODE = "REAUTHENTICATION_REQUIRED";

    public ReauthenticationRequiredException() {
        super(CODE);
    }
}
