package edu.bu.archive.application.authorization;

/**
 * A Kuali employee PERSON_ID as archived on contact rows (Kuali source:
 * KcPerson.personId is the KIM principal id). Only employees have one;
 * non-employee (rolodex) contacts never map to a signed-in person.
 * Private: {@link #toString()} masks the value.
 */
public record KualiPersonId(String value) {

    public KualiPersonId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Kuali PERSON_ID is required");
        }
    }

    @Override
    public String toString() {
        return "KualiPersonId[****]";
    }
}
