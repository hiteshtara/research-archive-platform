package edu.bu.archive.application.authorization.admin;

/**
 * Refused: an administrator tried to administer their own access in a way
 * that widens it or locks them out - granting themselves access, widening
 * their own grant, or suspending themselves.
 *
 * <p>Self-administration that only reduces access (revoking your own grant,
 * narrowing it, stepping down as administrator) is not refused.
 *
 * <p>Mapped to {@code 403 SELF_ADMINISTRATION_REQUIRES_APPROVAL}. Until the
 * approval workflow exists these actions are refused outright; once it does,
 * a second administrator's approval will authorise them.
 */
public class SelfAdministrationException extends RuntimeException {

    public static final String CODE = "SELF_ADMINISTRATION_REQUIRES_APPROVAL";

    public SelfAdministrationException(String message) {
        super(message);
    }
}
