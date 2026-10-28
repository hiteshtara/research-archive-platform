package edu.bu.archive.application.authorization.admin;

import java.util.List;

import edu.bu.archive.application.authorization.IdentityLink;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.KualiPersonId;

/**
 * Administrative writes and lookups over authz.identity_link and
 * authz.person_status (V082).
 *
 * <p>Separate from {@link edu.bu.archive.application.authorization.IdentityLinkRepository},
 * which is the read-only port the request path uses. Keeping the write methods
 * out of that interface means the per-request authorization path cannot reach
 * them even by accident.
 *
 * <p><strong>Nothing here consults KRIM ACTV_IND.</strong> Approved decision
 * D-ID-1: historical KRIM inactive status does not revoke an
 * administrator-verified archive link, so linking an identity to a principal
 * that KRIM marks inactive is permitted and is a normal case - most people on
 * decades of archived records no longer work at BU.
 */
public interface AdminIdentityRepository {

    /** Every link (active or revoked) for this institutional identity. */
    List<IdentityLink> findLinksFor(InstitutionalIdentifier identity);

    /**
     * Create an ACTIVE link from an administrator's verification.
     *
     * <p>{@code method} is recorded so an administrator-created link is
     * distinguishable from one created by automatic enrollment, which remains
     * disabled.
     *
     * @throws IllegalStateException if an active link already exists for this
     *         Cognito identity, which would make resolution ambiguous
     */
    IdentityLink link(String cognitoIssuer, String cognitoSubject,
                      InstitutionalIdentifier identity, KualiPersonId principal,
                      String loginName, String verifiedBy);

    /**
     * Re-link an archive identity that has been linked before - the Cognito
     * user pool replaced, or federation re-created, so the SAME person now
     * signs in under a new subject.
     *
     * <p>Separate from {@link #link} on purpose, and this is the important
     * part of the design. At the schema level "the same person under a new
     * subject" and "a different person given someone else's identity" are the
     * same two statements: revoke the old link, create a new one. The grants
     * hang off the archive identity, so the second case silently transfers
     * every one of them.
     *
     * <p>No constraint can tell those apart - only a human can. So re-linking
     * is a distinct, named, separately audited act
     * ({@code IDENTITY_RELINKED}) that an administrator must choose, rather
     * than something that happens by calling the ordinary link method on a
     * used identity. {@link #link} refuses an identity with any prior
     * history, which is what forces the choice.
     *
     * @param activeGrantsInherited how many active grants the new Cognito
     *        identity will inherit, so the caller can state it in the audit
     *        detail and the administrator can see it before agreeing
     * @throws IllegalStateException if the identity has no prior link (use
     *         {@link #link}), or already has an active one
     */
    IdentityLink relink(String cognitoIssuer, String cognitoSubject,
                        InstitutionalIdentifier identity, KualiPersonId principal,
                        String loginName, String verifiedBy);

    /** Active, unrevoked grants currently attached to an archive identity. */
    int activeGrantCountFor(InstitutionalIdentifier identity);

    /** True when this archive identity has ever been linked, active or not. */
    boolean hasEverBeenLinked(InstitutionalIdentifier identity);

    /**
     * Revoke a link. Status change, never a delete: the row is the evidence
     * that the access existed.
     *
     * @return the institutional identity the link belonged to
     * @throws java.util.NoSuchElementException if no active link has that id
     */
    InstitutionalIdentifier revokeLink(long linkId, String revokedBy);

    /** Current suspension state; false when no row exists. */
    boolean isSuspended(InstitutionalIdentifier identity);

    /** Set or clear suspension. Upserts authz.person_status. */
    void setSuspended(InstitutionalIdentifier identity, boolean suspended,
                      String changedBy, String reason);

    /**
     * Candidate identities matching free text, newest/most relevant first.
     *
     * <p>Searches linked identities, prior sign-in attempts and - per approved
     * decision D-ADM-4 - the historical archive.person extract. It does NOT
     * query BU's directory: the archive has no such access, so an identity
     * that has never signed in and is absent from archive.person can only be
     * entered manually.
     */
    List<IdentitySearchResult> search(String query, int limit);
}
