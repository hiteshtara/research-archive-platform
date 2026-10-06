package edu.bu.archive.application.authorization.fixtures;

import java.util.Map;

import edu.bu.archive.application.authorization.UnitHierarchy;

/** TEST-ONLY unit tree from a child -> parent map. */
public final class MapUnitHierarchy implements UnitHierarchy {

    private final Map<String, String> parentOf;

    public MapUnitHierarchy(Map<String, String> parentOf) {
        this.parentOf = Map.copyOf(parentOf);
    }

    @Override
    public boolean isSameOrDescendant(String unitNumber, String ancestorUnitNumber) {
        String current = unitNumber;
        for (int depth = 0; current != null && depth < 32; depth++) {
            if (current.equals(ancestorUnitNumber)) {
                return true;
            }
            current = parentOf.get(current);
        }
        return false;
    }
}
