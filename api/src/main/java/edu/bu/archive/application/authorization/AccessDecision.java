package edu.bu.archive.application.authorization;

import java.util.Set;

/** The decision for one record. Denied decisions carry no record data. */
public record AccessDecision(boolean allowed, Set<AccessReason> reasons, String denialCode) {

    public static AccessDecision allow(Set<AccessReason> reasons) {
        return new AccessDecision(true, Set.copyOf(reasons), null);
    }

    public static AccessDecision deny(String code) {
        return new AccessDecision(false, Set.of(), code);
    }
}
