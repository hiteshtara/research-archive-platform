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
            // No approved non-Central rule is implemented for these yet (P8).
            case NEGOTIATION, SUBAWARD, IRB -> null;
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
