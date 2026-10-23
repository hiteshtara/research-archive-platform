package edu.bu.archive.application.authorization;

/** Kinds of archive grant. Requirements IDs 1-4 (Security Requirements tab). */
public enum GrantType {
    /** ID 1: every record. Does NOT include security administration. */
    CENTRAL,
    /** ID 2: records associated with one department unit. */
    UNIT,
    /** ID 4: records carrying one authorized IO value (field pending D-A). */
    IO,
    /** ID 3: records on which the person is directly listed in a qualifying role. */
    CONTACT_DERIVATION
}
