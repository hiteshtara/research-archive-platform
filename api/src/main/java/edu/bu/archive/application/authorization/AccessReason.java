package edu.bu.archive.application.authorization;

/** Why a record is in scope - for "why can this user see X" explanations. */
public enum AccessReason {
    CENTRAL,
    DEPARTMENT,
    RESEARCH_STAFF_CONTACT,
    IO_GRANT,
    /** SEC-005: a PARENT_AWARD grant reached this Award. */
    PARENT_AWARD
}
