package edu.bu.archive.application.authorization;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders an {@link AccessScope} as one SQL predicate for a module's row
 * alias, to sit in the SAME WHERE as the search filters - so counts, facets,
 * ordering and LIMIT/OFFSET all see only in-scope rows. It must agree with
 * {@link RecordAccessEvaluator}; the integration tests compare the two.
 *
 * <p>Collections are bound as IN lists (Spring expands them); a clause whose
 * collection is empty is omitted, and a scope with no usable clause renders
 * FALSE.
 *
 * <p><strong>SEC-003 (business decision 2026-11-05).</strong> Each row
 * predicate is built in two halves: a family-scoped half (department and IO)
 * which {@link #familyWrap} may widen to the whole family, and a per-version
 * half (the contact clause) which is ORed in OUTSIDE that wrap and therefore
 * only ever matches the row being tested. The two halves are assembled by
 * {@link #combine}, so the rule lives in one place and applies identically to
 * all four modules - including Subaward and Negotiation, which inherit these
 * predicates and bind them to a joined Award or Proposal version.
 */
public final class RecordScopeSqlBuilder {

    /**
     * The latest version of {@code az_np}'s own proposal family, ordered
     * exactly as {@code JdbcRecordFactsRepository.latestProposalVersionKey}
     * orders it. The two must stay identical: if they ever disagree, the
     * list predicate and the record decision pick different versions of the
     * same Proposal, which is the class of disagreement SEC-003 exposed.
     */
    private static final String LATEST_PROPOSAL_VERSION =
            "(SELECT az_npl.proposal_id FROM archive.proposal_version az_npl"
            + " WHERE az_npl.proposal_number = az_np.proposal_number"
            + " ORDER BY az_npl.version_number DESC,"
            + " az_npl.source_update_timestamp DESC NULLS LAST,"
            + " az_npl.proposal_id DESC LIMIT 1)";

    private final AuthorizationPolicy policy;
    private final IoSqlStrategy io;

    public RecordScopeSqlBuilder(AuthorizationPolicy policy, IoSqlStrategy io) {
        this.policy = policy;
        this.io = io;
    }

    /**
     * @param expandedUnits department units already expanded per the
     *                      department-match strategy (descendants added only
     *                      when the strategy and the grant both ask for them)
     */
    public SqlFragment build(AccessOutcome outcome, RecordModule module, String alias, Set<String> expandedUnits) {
        if (!(outcome instanceof AccessOutcome.Scoped scoped)) {
            return SqlFragment.DENY_ALL;
        }
        AccessScope scope = scoped.scope();
        if (scope.central()) {
            return SqlFragment.NONE;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        String row = switch (module) {
            case AWARD -> awardRow(scope, expandedUnits, params, true);
            case PROPOSAL -> proposalRow(scope, expandedUnits, params);
            case SUBAWARD -> subawardRow(scope, expandedUnits, params);
            case NEGOTIATION -> negotiationRow(scope, expandedUnits, params);
            // No approved non-Central rule is implemented for IRB yet (P8).
            case IRB -> null;
        };
        if (row == null) {
            return SqlFragment.DENY_ALL;
        }
        return new SqlFragment(" AND " + row.replace("{row}", alias) + " ", params);
    }

    /**
     * Wraps the FAMILY-SCOPED half of a predicate so that any version of the
     * same family can satisfy it.
     *
     * <p>SEC-003: only department and IO clauses are ever passed here.
     * Contact clauses are ORed in outside the wrap by {@link #combine}, so
     * that being listed on one version cannot reach another. The emitted
     * string still carries {@code {row}} for the correlation, which the
     * caller resolves - which is how the same predicate serves the outer
     * Award/Proposal row and the joined {@code az_sa} / {@code az_na} /
     * {@code az_np} rows without knowing which it is.
     */
    private String familyWrap(RecordModule module, String rowPredicate) {
        if (rowPredicate == null) {
            return null;
        }
        if (policy.versionScope() == AuthorizationPolicy.VersionScope.PER_VERSION) {
            return rowPredicate;
        }
        // FAMILY_WIDE: some version of the same family satisfies the predicate.
        return switch (module) {
            case AWARD -> "EXISTS (SELECT 1 FROM archive.award_version az_fv"
                    + " WHERE az_fv.award_number = {row}.award_number AND "
                    + rowPredicate.replace("{row}", "az_fv") + ")";
            case PROPOSAL -> "EXISTS (SELECT 1 FROM archive.proposal_version az_fv"
                    + " WHERE az_fv.proposal_number = {row}.proposal_number AND "
                    + rowPredicate.replace("{row}", "az_fv") + ")";
            default -> null;
        };
    }

    /**
     * The complete Award rule for one row: the family-scoped clauses wrapped
     * per {@link AuthorizationPolicy.VersionScope}, ORed with the per-version
     * contact clause which is NEVER wrapped (SEC-003).
     *
     * <p>The contact clause needed no change to become version-specific: it
     * already correlates on {@code award_id}, which is the version key. Only
     * its position relative to the family wrap moved.
     */
    /**
     * @param includeParentAward false when this predicate is being built to
     *        decide a Subaward or Negotiation. SEC-005 (business decision
     *        2026-11-09) restricts PARENT_AWARD grants to Award records, and
     *        the inherited predicate is literally the Award predicate - so
     *        the clause is omitted there rather than filtered afterwards.
     *        Mirrors {@code RecordAccessEvaluator.DecisionContext}.
     */
    private String awardRow(AccessScope scope, Set<String> units, Map<String, Object> params,
                            boolean includeParentAward) {
        List<String> familyScoped = new ArrayList<>();
        if (!units.isEmpty()) {
            params.put("az_units", List.copyOf(units));
            familyScoped.add("{row}.lead_unit_number IN (:az_units)");
        }
        if (!scope.ioValues().isEmpty()) {
            io.ioPredicate(RecordModule.AWARD, "{row}").ifPresent(predicate -> {
                params.put("az_ios", List.copyOf(scope.ioValues()));
                familyScoped.add(predicate);
            });
        }
        /*
         * SEC-005. The set is already expanded through the hierarchy, bounded
         * and family-checked, by AccessScopeResolver - the same set the
         * evaluator tests - so this is a plain IN and the list cannot
         * disagree with the workspace.
         *
         * Family-scoped by nature: award_number is the family key, identical
         * across every version of an Award, so a PARENT_AWARD grant reaches
         * all versions of the Awards it names whatever versionScope says.
         * Wrapping it under FAMILY_WIDE is therefore a no-op rather than a
         * widening.
         */
        if (includeParentAward && !scope.parentAwardNumbers().isEmpty()) {
            params.put("az_parent_awards", List.copyOf(scope.parentAwardNumbers()));
            familyScoped.add("TRIM({row}.award_number) IN (:az_parent_awards)");
        }
        List<String> perVersion = new ArrayList<>();
        scope.contactPersonId().ifPresent(person -> {
            params.put("az_person", person.value());
            params.put("az_roles", List.copyOf(policy.researchStaffRoles()));
            perVersion.add("EXISTS (SELECT 1 FROM archive.award_person az_ap"
                    + " WHERE az_ap.award_id = {row}.award_id AND az_ap.person_id = :az_person"
                    + " AND UPPER(TRIM(az_ap.contact_role_code)) IN (:az_roles))");
        });
        return combine(RecordModule.AWARD, familyScoped, perVersion);
    }

    /**
     * ORs the family-scoped half (wrapped when FAMILY_WIDE) with the
     * per-version half (never wrapped). Renders FALSE when the scope yields
     * no usable clause at all, so an empty scope can never match a row.
     */
    private String combine(RecordModule module, List<String> familyScoped, List<String> perVersion) {
        List<String> parts = new ArrayList<>();
        if (!familyScoped.isEmpty()) {
            // Non-null for AWARD and PROPOSAL, the only modules combine serves.
            String wrapped = familyWrap(module, "(" + String.join(" OR ", familyScoped) + ")");
            if (wrapped != null) {
                parts.add(wrapped);
            }
        }
        parts.addAll(perVersion);
        return parts.isEmpty() ? "FALSE" : "(" + String.join(" OR ", parts) + ")";
    }

    /**
     * Subaward access is INHERITED from the Awards that fund it: approved
     * 2026-10-26, "a user may access a Subaward when they are authorized to
     * view at least ONE of its funding Awards".
     *
     * <p>Rendered as an EXISTS over archive.subaward_funding joined to
     * archive.award_version, with the ordinary Award predicate applied to the
     * joined Award row. That reuse is the point: there is no Subaward rule to
     * get wrong, only the Award rule evaluated somewhere else. Department
     * grants, descendant units and Research Staff contact access all behave
     * exactly as they do on an Award, because they ARE the Award clauses.
     *
     * <p>ANY-match falls out of EXISTS: one qualifying funding Award is
     * enough, and the predicate stops looking. An ALL-match rule would need
     * NOT EXISTS over the unauthorized ones and was explicitly not approved.
     *
     * <p><strong>FAMILY-level, revised 2026-10-26 on real data.</strong> The
     * first version of this predicate correlated on {@code subaward_id}, so
     * each version was decided by its own funding rows. Profiling the dev
     * archive showed why that cannot work: only 4,033 of 93,735 version rows
     * carry ANY funding row at all, because {@code subaward_funding} is
     * populated almost entirely on the latest version of a family. Per
     * version, a non-Central caller could reach <strong>4.06%</strong> of
     * version rows and 4.14% of a reachable family's own history - the
     * archive's historical record, which is the point of the archive, would
     * have been almost entirely invisible. Correlating on
     * {@code subaward_code} instead takes that to <strong>98.09%</strong>.
     *
     * <p>The family is derived by joining {@code archive.subaward} on
     * {@code subaward_id}, NEVER by reading
     * {@code subaward_funding.subaward_code}. That column is denormalised
     * and disagrees with its parent row for 8 funding rows in dev - pointing
     * at genuinely different families (3046 vs 1970, 3203 vs 4094) - so
     * trusting it would attribute those funding links to the wrong family
     * and grant access to it.
     *
     * <p>Three things this gets right by construction:
     * <ul>
     *   <li>No funding row anywhere in the family -> EXISTS is false ->
     *       denied.</li>
     *   <li>A funding row whose award_id resolves to nothing -> the INNER
     *       join drops it, so a broken reference can never authorise. There
     *       is no foreign key on subaward_funding.award_id, so dangling
     *       references are possible and must not read as permission.</li>
     *   <li>Revoking the last qualifying Award grant makes EXISTS false on
     *       the very next request - the predicate is rebuilt per request and
     *       nothing is memoised.</li>
     * </ul>
     *
     * <p>The family wrap is applied to the FUNDED Award row, not to the
     * outer one. familyWrap derives the family from whatever row it is
     * given, and the outer row here is a Subaward with no award_number - so
     * it is anchored on az_sa instead. That is not cosmetic: under
     * FAMILY_WIDE, {@link RecordAuthorizationService#canSeeAward} grants the
     * funded Award when ANY version of its family is in scope, and a
     * predicate that only tested the funded version would deny in SQL what
     * the evaluator permits in Java. The integration tests compare the two,
     * so the disagreement is a test failure, not a silent one.
     */
    private String subawardRow(AccessScope scope, Set<String> units, Map<String, Object> params) {
        String awardPredicate = awardRow(scope, units, params, false);
        if (awardPredicate == null || "FALSE".equals(awardPredicate)) {
            return "FALSE";
        }
        /*
         * awardRow already applied the family wrap to its family-scoped half
         * and left the contact clause per-row, so everything here needs is to
         * bind {row} to az_sa - the funded Award VERSION. That is what makes
         * the contact clause correlate on az_sa.award_id, which is exactly
         * how canSeeSubaward decides it: each funding award_id in turn, as
         * canSeeAward would. The outer Subaward row has no award_id at all.
         */
        return "EXISTS (SELECT 1 FROM archive.subaward az_fam"
                + " JOIN archive.subaward_funding az_sf ON az_sf.subaward_id = az_fam.subaward_id"
                + " JOIN archive.award_version az_sa ON az_sa.award_id = az_sf.award_id"
                + " WHERE az_fam.subaward_code = {row}.subaward_code"
                + " AND az_sf.award_id IS NOT NULL"
                + " AND " + awardPredicate.replace("{row}", "az_sa") + ")";
    }

    /**
     * Negotiation access, approved 2026-10-27 as a HYBRID rule after
     * profiling the dev archive. Three alternative paths, ORed: a valid
     * grant through any one of them permits access.
     *
     * <ol>
     *   <li><strong>Lead unit</strong> from
     *       {@code archive.negotiation_search_attribute}. Covers 10,718 of
     *       10,775 Negotiations (99.47%) and matches {@code archive.unit}
     *       100% of the time.</li>
     *   <li><strong>Award inheritance</strong>, only when the association
     *       type is explicitly {@code 'Award'}.</li>
     *   <li><strong>Proposal inheritance</strong>, only when the association
     *       type is explicitly {@code 'Institutional Proposal'}.</li>
     * </ol>
     *
     * <p><strong>The association type gate is a security control, not a
     * filter.</strong> {@code associated_document_id} is free TEXT and is
     * populated on EVERY Negotiation, including all 8,533 typed
     * {@code 'None'} - where it holds the Negotiation's own KC document
     * number. Matched without the type gate, those unassociated rows
     * collide with an Award workflow document number 7,246 times and with a
     * proposal_number 6,980 times; 6,073 of 10,775 identifiers match more
     * than one record kind. Every one of those is a coincidence, not a
     * relationship. Dropping the type gate would silently grant access to
     * thousands of unassociated Negotiations.
     *
     * <p>Note which column each gate resolves against:
     * {@code 'Award'} resolves to {@code award_number} and NEVER to
     * {@code workflow_document_number} - type-gated, award_number matches
     * 2,223/2,223 and the workflow column matches 0, so consulting the
     * workflow column would add only false hits.
     *
     * <p>{@code 'Subaward'} (16 rows) is deliberately NOT implemented: its
     * identifiers resolve ambiguously to both an Award document number and
     * a proposal number, and no reliable key was found. Those Negotiations
     * reach the lead-unit path like any other, and nothing else.
     *
     * <p>Inheritance reuses {@link #awardRow} and {@link #proposalRow}
     * verbatim, family-wrapped exactly as the Award and Proposal modules
     * wrap them, so department grants, descendant units and Research Staff
     * contact access behave identically - there is no second rule to get
     * wrong.
     */
    private String negotiationRow(AccessScope scope, Set<String> units, Map<String, Object> params) {
        List<String> paths = new ArrayList<>();

        if (!units.isEmpty()) {
            params.put("az_units", List.copyOf(units));
            paths.add("EXISTS (SELECT 1 FROM archive.negotiation_search_attribute az_nsa"
                    + " WHERE az_nsa.negotiation_id = {row}.negotiation_id"
                    + " AND az_nsa.lead_unit_number IN (:az_units))");
        }

        /*
         * Both inherited predicates arrive already family-wrapped where the
         * policy asks for it, with their contact clause left per-version
         * (SEC-003). What remains is to bind {row} to the joined version -
         * and to say WHICH version that is.
         *
         * An association names a FAMILY: associated_document_id holds an
         * award_number or a proposal_number, not a version key. So the join
         * has to pick a version, and it must pick the same one the Java path
         * picks. RecordAuthorizationService reaches these through
         * canSeeAwardNumber / canSeeProposalNumber, which resolve
         * is_primary_current = TRUE and the latest proposal_version
         * respectively, and then decide THAT version.
         *
         * Before SEC-003 the choice did not matter: every clause was
         * family-wrapped, so any version of the family that satisfied the
         * predicate satisfied it for all of them. Now that the contact
         * clause is per-version, an unpinned join would let a contact on a
         * SUPERSEDED version authorise the Negotiation in a list while the
         * workspace endpoint denies it - a visible row that 404s, with the
         * looser of the two answers winning. Pinning the join is both the
         * agreeing answer and the fail-closed one.
         */
        String awardPredicate = awardRow(scope, units, params, false);
        if (!"FALSE".equals(awardPredicate)) {
            paths.add("EXISTS (SELECT 1 FROM archive.award_version az_na"
                    + " WHERE {row}.negotiation_association_type_description = 'Award'"
                    + " AND az_na.award_number = TRIM({row}.associated_document_id)"
                    + " AND az_na.is_primary_current = TRUE"
                    + " AND " + awardPredicate.replace("{row}", "az_na") + ")");
        }

        String proposalPredicate = proposalRow(scope, units, params);
        if (!"FALSE".equals(proposalPredicate)) {
            paths.add("EXISTS (SELECT 1 FROM archive.proposal_version az_np"
                    + " WHERE {row}.negotiation_association_type_description"
                    + " = 'Institutional Proposal'"
                    + " AND az_np.proposal_number = TRIM({row}.associated_document_id)"
                    + " AND az_np.proposal_id = " + LATEST_PROPOSAL_VERSION
                    + " AND " + proposalPredicate.replace("{row}", "az_np") + ")");
        }

        return paths.isEmpty() ? "FALSE" : "(" + String.join(" OR ", paths) + ")";
    }

    /** As {@link #awardRow}: the proposal_person clause is per-version (SEC-003). */
    private String proposalRow(AccessScope scope, Set<String> units, Map<String, Object> params) {
        List<String> familyScoped = new ArrayList<>();
        if (!units.isEmpty()) {
            params.put("az_units", List.copyOf(units));
            familyScoped.add("{row}.lead_unit_number IN (:az_units)");
        }
        List<String> perVersion = new ArrayList<>();
        scope.contactPersonId().ifPresent(person -> {
            params.put("az_person", person.value());
            params.put("az_roles", List.copyOf(policy.researchStaffRoles()));
            perVersion.add("EXISTS (SELECT 1 FROM archive.proposal_person az_pp"
                    + " WHERE az_pp.proposal_id = {row}.proposal_id AND az_pp.person_id = :az_person"
                    + " AND UPPER(TRIM(az_pp.contact_role_code)) IN (:az_roles))");
        });
        // IO grants: no Proposal rule (design 4.6).
        return combine(RecordModule.PROPOSAL, familyScoped, perVersion);
    }
}
