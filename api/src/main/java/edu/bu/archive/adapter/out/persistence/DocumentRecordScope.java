package edu.bu.archive.adapter.out.persistence;

import java.util.LinkedHashMap;
import java.util.Map;

import edu.bu.archive.application.authorization.RecordModule;
import edu.bu.archive.application.authorization.SqlFragment;

/**
 * Record authorization for the fixed document unions (Document Explorer, document search).
 * Each module branch carries a {@code {{MODULE_SCOPE}}} marker right after its own WHERE, so
 * the scope predicate filters rows BEFORE the union - counts, facets, ordering and
 * LIMIT/OFFSET all see only in-scope documents. Award and Proposal use the same predicates as
 * their searches (family-wide versions, sub-units by grant flag, PI/MPI/COI, IO = Award
 * account). Negotiation, Subaward and IRB have no non-Central rule yet (decision #3), so their
 * branches render FALSE for a restricted caller: excluded, not supported.
 */
final class DocumentRecordScope {

    record Rendered(String sql, Map<String, Object> params) {
    }

    private static final Map<RecordModule, String> ALIAS = Map.of(
            RecordModule.AWARD, "av",
            RecordModule.PROPOSAL, "pv",
            RecordModule.NEGOTIATION, "n",
            RecordModule.SUBAWARD, "s",
            RecordModule.IRB, "ipv");

    private DocumentRecordScope() {
    }

    static String marker(RecordModule module) {
        return "{{" + module.name() + "_SCOPE}}";
    }

    static Rendered render(String template, AwardArchiveRepository.RecordScope scope) {
        String sql = template;
        Map<String, Object> params = new LinkedHashMap<>();
        for (RecordModule module : RecordModule.values()) {
            String marker = marker(module);
            if (!sql.contains(marker)) {
                continue;
            }
            SqlFragment fragment = scope.sql(module, ALIAS.get(module))
                    .withParameterPrefix("doc_" + module.name().toLowerCase() + "_");
            sql = sql.replace(marker, fragment.sql());
            params.putAll(fragment.params());
        }
        return new Rendered(sql, params);
    }

    /** True when any module is narrowed for this caller (not enforced or Central: false). */
    static boolean restricted(AwardArchiveRepository.RecordScope scope) {
        for (RecordModule module : RecordModule.values()) {
            if (!scope.sql(module, ALIAS.get(module)).sql().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
