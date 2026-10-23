package edu.bu.archive.application.authorization;

import java.util.Optional;

/**
 * One person listed on a record version. Non-employee (rolodex) contacts
 * have no Kuali PERSON_ID and can never match a signed-in person.
 */
public record RecordContact(Optional<KualiPersonId> personId, String roleCode, boolean employee) {

    public static RecordContact employee(String personId, String roleCode) {
        return new RecordContact(Optional.of(new KualiPersonId(personId)), roleCode, true);
    }

    public static RecordContact nonEmployee(String roleCode) {
        return new RecordContact(Optional.empty(), roleCode, false);
    }
}
