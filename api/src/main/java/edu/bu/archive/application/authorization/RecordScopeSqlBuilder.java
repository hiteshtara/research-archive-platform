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
 */
public final class RecordScopeSqlBuilder {

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
            case AWARD -> familyWrap(module, alias, awardRow(scope, expandedUnits, params));
            case PROPOSAL -> familyWrap(module, alias, proposalRow(scope, expandedUnits, params));
            case SUBAWARD -> subawardRow(scope, expandedUnits, params);
            // No approved non-Central rule is implemented for these yet (P8).
            case NEGOTIATION, IRB -> null;
        };
        if (row == null) {
            return SqlFragment.DENY_ALL;
        }
        return new SqlFragment(" AND " + row.replace("{row}", alias) + " ", params);
    }

    private String familyWrap(RecordModule module, String alias, String rowPredicate) {
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

    private String awardRow(AccessScope scope, Set<String> units, Map<String, Object> params) {
        List<String> clauses = new ArrayList<>();
        if (!units.isEmpty()) {
            params.put("az_units", List.copyOf(units));
            clauses.add("{row}.lead_unit_number IN (:az_units)");
        }
        scope.contactPersonId().ifPresent(person -> {
            params.put("az_person", person.value());
            params.put("az_roles", List.copyOf(policy.researchStaffRoles()));
            clauses.add("EXISTS (SELECT 1 FROM archive.award_person az_ap"
                    + " WHERE az_ap.award_id = {row}.award_id AND az_ap.person_id = :az_person"
                    + " AND UPPER(TRIM(az_ap.contact_role_code)) IN (:az_roles))");
        });
        if (!scope.ioValues().isEmpty()) {
            io.ioPredicate(RecordModule.AWARD, "{row}").ifPresent(predicate -> {
                params.put("az_ios", List.copyOf(scope.ioValues()));
                clauses.add(predicate);
            });
        }
        return clauses.isEmpty() ? "FALSE" : "(" + String.join(" OR ", clauses) + ")";
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
        String fundedAward = awardRow(scope, units, params);
        if (fundedAward == null || "FALSE".equals(fundedAward)) {
            return "FALSE";
        }
        // Alias is unused by familyWrap - it emits {row}, resolved below.
        String awardPredicate = familyWrap(RecordModule.AWARD, "{row}", fundedAward);
        return "EXISTS (SELECT 1 FROM archive.subaward az_fam"
                + " JOIN archive.subaward_funding az_sf ON az_sf.subaward_id = az_fam.subaward_id"
                + " JOIN archive.award_version az_sa ON az_sa.award_id = az_sf.award_id"
                + " WHERE az_fam.subaward_code = {row}.subaward_code"
                + " AND az_sf.award_id IS NOT NULL"
                + " AND " + awardPredicate.replace("{row}", "az_sa") + ")";
    }

    private String proposalRow(AccessScope scope, Set<String> units, Map<String, Object> params) {
        List<String> clauses = new ArrayList<>();
        if (!units.isEmpty()) {
            params.put("az_units", List.copyOf(units));
            clauses.add("{row}.lead_unit_number IN (:az_units)");
        }
        scope.contactPersonId().ifPresent(person -> {
            params.put("az_person", person.value());
            params.put("az_roles", List.copyOf(policy.researchStaffRoles()));
            clauses.add("EXISTS (SELECT 1 FROM archive.proposal_person az_pp"
                    + " WHERE az_pp.proposal_id = {row}.proposal_id AND az_pp.person_id = :az_person"
                    + " AND UPPER(TRIM(az_pp.contact_role_code)) IN (:az_roles))");
        });
        // IO grants: no Proposal rule (design 4.6).
        return clauses.isEmpty() ? "FALSE" : "(" + String.join(" OR ", clauses) + ")";
    }
}
