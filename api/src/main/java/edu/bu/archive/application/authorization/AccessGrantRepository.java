package edu.bu.archive.application.authorization;

import java.util.List;

/** Read-only port onto archive grants. */
public interface AccessGrantRepository {

    /** Every grant (active or not) for this institutional identity. */
    List<AccessGrant> findForGrantee(InstitutionalIdentifier grantee);
}
