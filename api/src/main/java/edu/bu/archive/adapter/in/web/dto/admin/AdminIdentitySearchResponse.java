package edu.bu.archive.adapter.in.web.dto.admin;

import edu.bu.archive.application.authorization.admin.IdentitySearchResult;

/**
 * One search hit, carrying the store it came from and a plain-language note
 * about what that store proves. The archive cannot search BU's directory, so
 * an administrator must be able to see the difference between "this person has
 * signed in" and "this name appears on a 2003 Award".
 */
public record AdminIdentitySearchResponse(
        String source,
        String sourceNote,
        String institutionalIdentifier,
        String kualiPersonId,
        String displayName,
        boolean hasActiveLink) {

    public static AdminIdentitySearchResponse from(IdentitySearchResult result) {
        return new AdminIdentitySearchResponse(
                result.source().name(),
                note(result.source()),
                result.institutionalIdentifier(),
                result.kualiPersonId(),
                result.displayName(),
                result.hasActiveLink());
    }

    private static String note(IdentitySearchResult.Source source) {
        return switch (source) {
            case IDENTITY_LINK -> "Already linked in the archive.";
            case SIGN_IN_HISTORY -> "Has authenticated through BU, but holds no archive link.";
            case HISTORICAL_PERSON -> "Historical record from Kuali. Says who they were on "
                    + "archived records; says nothing about current employment or access.";
        };
    }
}
