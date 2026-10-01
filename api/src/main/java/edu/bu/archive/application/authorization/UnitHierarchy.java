package edu.bu.archive.application.authorization;

/** Kuali unit tree (archive.unit), used only by LEAD_UNIT_WITH_DESCENDANTS. */
public interface UnitHierarchy {

    boolean isSameOrDescendant(String unitNumber, String ancestorUnitNumber);
}
