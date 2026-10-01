package edu.bu.archive.application.authorization;

/** A department unit in scope, and whether its grant asked for descendants. */
public record UnitGrant(String unitNumber, boolean includeDescendants) {
}
