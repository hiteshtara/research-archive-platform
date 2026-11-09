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
        Set<String> ioValues,
        /**
         * SEC-005: Award numbers reached by PARENT_AWARD grants, ALREADY
         * expanded through the hierarchy by {@link AccessScopeResolver}.
         *
         * <p>Expanded here rather than per record so the evaluator and the
         * list SQL cannot disagree about which Awards a grant reaches.
         */
        Set<String> parentAwardNumbers
) {

    /** The shape before SEC-005; no PARENT_AWARD grants in scope. */
    public AccessScope(boolean central, Set<UnitGrant> units,
                       Optional<KualiPersonId> contactPersonId, Set<String> ioValues) {
        this(central, units, contactPersonId, ioValues, Set.of());
    }

    public AccessScope {
        units = Set.copyOf(units);
        ioValues = Set.copyOf(ioValues);
        parentAwardNumbers = Set.copyOf(parentAwardNumbers);
    }

    public boolean isEmpty() {
        return !central && units.isEmpty() && contactPersonId.isEmpty()
                && ioValues.isEmpty() && parentAwardNumbers.isEmpty();
    }
}
