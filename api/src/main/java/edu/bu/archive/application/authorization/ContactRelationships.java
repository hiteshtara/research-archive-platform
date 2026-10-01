package edu.bu.archive.application.authorization;

import java.util.Set;

/**
 * Whether a KIM principal (contact PERSON_ID) is listed, in a qualifying role, on at least
 * one archived record. Used only under ContactDerivation.VERIFIED_PRINCIPAL, so that a KIM
 * account alone never grants access.
 */
public interface ContactRelationships {

    boolean isQualifyingContact(KualiPersonId person, Set<String> roles);

    /** No contact is ever qualifying: VERIFIED_PRINCIPAL then grants nothing. */
    ContactRelationships NONE = (person, roles) -> false;
}
