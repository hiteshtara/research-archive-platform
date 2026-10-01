package edu.bu.archive.application.authorization;

import java.util.Map;

/** A WHERE-clause fragment (starting with " AND ...") and its named parameters. */
public record SqlFragment(String sql, Map<String, Object> params) {

    public static final SqlFragment NONE = new SqlFragment("", Map.of());
    public static final SqlFragment DENY_ALL = new SqlFragment(" AND FALSE ", Map.of());

    public SqlFragment {
        params = Map.copyOf(params);
    }
}
