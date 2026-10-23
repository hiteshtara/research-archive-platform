package edu.bu.archive.application.authorization;

import java.util.Optional;
import java.util.Set;

/**
 * The union of one person's active grants. There are no deny grants: a
 * record is in scope when ANY part of the scope reaches it, and removing one
 * grant removes only what that grant supplied.
 */
public record AccessScope(
        boolean central,
        Set<UnitGrant> units,
        Optional<KualiPersonId> contactPersonId,
        Set<String> ioValues
) {

    public AccessScope {
        units = Set.copyOf(units);
        ioValues = Set.copyOf(ioValues);
    }

    public boolean isEmpty() {
        return !central && units.isEmpty() && contactPersonId.isEmpty() && ioValues.isEmpty();
    }
}
