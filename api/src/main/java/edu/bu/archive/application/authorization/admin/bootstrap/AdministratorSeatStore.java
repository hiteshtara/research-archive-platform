package edu.bu.archive.application.authorization.admin.bootstrap;

import java.util.List;

import edu.bu.archive.application.authorization.InstitutionalIdentifier;

/**
 * What the second-administrator bootstrap needs from the database beyond the
 * ordinary repositories: a lock that serialises every bootstrap, and the
 * evidence that this one-time path has already been used.
 */
public interface AdministratorSeatStore {

    /**
     * Take the bootstrap advisory lock (the SAME one the first-administrator
     * bootstrap takes) and lock the ACTIVE membership rows, then return them.
     *
     * <p>MUST be called inside the caller's transaction. The lock is what
     * makes "exactly one active administrator" a fact at the moment of the
     * insert rather than at the moment of an earlier read: two concurrent
     * runs serialise, and the second sees two administrators and refuses.
     */
    List<InstitutionalIdentifier> lockActiveAdministrators();

    /**
     * True once a second administrator has ever been seated by this path,
     * read from the append-only admin audit. That is what makes it one-time:
     * revoking the second administrator later does not re-open it.
     */
    boolean secondAdministratorEverSeated();
}
