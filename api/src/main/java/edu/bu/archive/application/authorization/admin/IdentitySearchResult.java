package edu.bu.archive.application.authorization.admin;

/**
 * One candidate identity an administrator can act on.
 *
 * <p>{@link Source} is part of the result on purpose. The archive cannot
 * enumerate BU's directory, so a search answers from three different stores
 * that mean three different things, and an administrator must be able to tell
 * them apart before linking anyone:
 *
 * <ul>
 *   <li>{@link Source#IDENTITY_LINK} - already linked in the archive.</li>
 *   <li>{@link Source#SIGN_IN_HISTORY} - has authenticated through BU at least
 *       once (an enrollment audit row exists), but holds no link.</li>
 *   <li>{@link Source#HISTORICAL_PERSON} - appears in archive.person, the
 *       historical KRIM extract. Says who they were on archived records, and
 *       nothing whatever about current employment or current access
 *       (approved decisions D-ID-1 and D-ADM-4).</li>
 * </ul>
 */
public record IdentitySearchResult(
        Source source,
        String institutionalIdentifier,
        String kualiPersonId,
        String displayName,
        boolean hasActiveLink,
        boolean suspended,
        boolean administrator,
        int activeGrantCount) {

    public enum Source { IDENTITY_LINK, SIGN_IN_HISTORY, HISTORICAL_PERSON }
}
