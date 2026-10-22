package edu.bu.archive.application.authorization;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The authorization-relevant facts of ONE record version, loaded server-side.
 * {@code ioValues} are filled only by an {@link IoResolver}; the real resolver
 * is disabled until the authoritative IO field is confirmed (D-A).
 */
public record RecordFacts(
        RecordModule module,
        String versionKey,
        String familyKey,
        String leadUnitNumber,
        List<RecordContact> contacts,
        Set<String> ioValues
) {

    public RecordFacts {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(versionKey, "versionKey");
        Objects.requireNonNull(familyKey, "familyKey");
        contacts = List.copyOf(contacts);
        ioValues = Set.copyOf(ioValues);
    }
}
