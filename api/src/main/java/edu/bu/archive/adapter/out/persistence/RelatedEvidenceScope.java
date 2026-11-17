package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.application.authorization.SqlFragment;

/**
 * The three related-module predicates that decide which RELATED_* evidence
 * rows a caller may receive from
 * {@link AwardEvidenceRetrievalRepository#findNearestEvidence} (QA H5).
 *
 * <p>Each fragment is exactly what {@code RecordAccess.relatedScopeSql}
 * renders for that module, bound to the alias the repository uses for the
 * related record: {@link #PROPOSAL_ALIAS}, {@link #NEGOTIATION_ALIAS} and
 * {@link #SUBAWARD_ALIAS}. There is no evidence-specific rule.
 */
public record RelatedEvidenceScope(
        SqlFragment proposal,
        SqlFragment negotiation,
        SqlFragment subaward
) {

    /** A row of archive.proposal_version: the exact linked Proposal version. */
    public static final String PROPOSAL_ALIAS = "ev_proposal";
    /** A row of archive.negotiation. */
    public static final String NEGOTIATION_ALIAS = "ev_negotiation";
    /** A row of archive.subaward: the funded Subaward (family-level rule). */
    public static final String SUBAWARD_ALIAS = "ev_subaward";

    /** Enforcement off, or a Central caller: the pre-H5 statement, unchanged. */
    public static final RelatedEvidenceScope UNRESTRICTED =
            new RelatedEvidenceScope(SqlFragment.NONE, SqlFragment.NONE, SqlFragment.NONE);

    /** Fail-closed default: no related evidence at all. */
    public static final RelatedEvidenceScope DENY_ALL =
            new RelatedEvidenceScope(SqlFragment.DENY_ALL, SqlFragment.DENY_ALL, SqlFragment.DENY_ALL);

    public RelatedEvidenceScope {
        proposal = proposal == null ? SqlFragment.DENY_ALL : proposal;
        negotiation = negotiation == null ? SqlFragment.DENY_ALL : negotiation;
        subaward = subaward == null ? SqlFragment.DENY_ALL : subaward;
    }

    /** True only when no related type carries a predicate. */
    public boolean isUnrestricted() {
        return proposal.sql().isBlank() && negotiation.sql().isBlank() && subaward.sql().isBlank();
    }
}
