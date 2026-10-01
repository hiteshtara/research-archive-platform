package edu.bu.archive.application.authorization;

import java.util.Map;

/** A WHERE-clause fragment (starting with " AND ...") and its named parameters. */
public record SqlFragment(String sql, Map<String, Object> params) {

    public static final SqlFragment NONE = new SqlFragment("", Map.of());
    public static final SqlFragment DENY_ALL = new SqlFragment(" AND FALSE ", Map.of());

    public SqlFragment {
        params = Map.copyOf(params);
    }

    /**
     * The same predicate with every named parameter renamed to {@code prefix + name}, so two
     * fragments (e.g. Award and Proposal) can share one statement without colliding.
     */
    public SqlFragment withParameterPrefix(String prefix) {
        if (params.isEmpty()) {
            return this;
        }
        String renamed = sql;
        java.util.Map<String, Object> prefixed = new java.util.LinkedHashMap<>();
        // Longest names first, so ":az_unit" never rewrites part of ":az_units".
        java.util.List<String> names = new java.util.ArrayList<>(params.keySet());
        names.sort(java.util.Comparator.comparingInt(String::length).reversed());
        for (String name : names) {
            renamed = renamed.replaceAll(":" + java.util.regex.Pattern.quote(name) + "(?![A-Za-z0-9_])",
                    java.util.regex.Matcher.quoteReplacement(":" + prefix + name));
            prefixed.put(prefix + name, params.get(name));
        }
        return new SqlFragment(renamed, prefixed);
    }
}
