package edu.bu.archive.application.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SqlFragmentTest {

    @Test
    void prefixingRenamesEveryParameterWithoutTouchingLongerNames() {
        var fragment = new SqlFragment(" AND (av.lead_unit_number IN (:az_units) OR p = :az_unit OR q = :az_units)",
                Map.of("az_units", List.of("U1"), "az_unit", "U2"));
        var prefixed = fragment.withParameterPrefix("doc_award_");
        assertThat(prefixed.sql())
                .isEqualTo(" AND (av.lead_unit_number IN (:doc_award_az_units) OR p = :doc_award_az_unit "
                        + "OR q = :doc_award_az_units)");
        assertThat(prefixed.params()).containsOnly(
                Map.entry("doc_award_az_units", List.of("U1")), Map.entry("doc_award_az_unit", "U2"));
    }

    @Test
    void fragmentsWithoutParametersAreUnchanged() {
        assertThat(SqlFragment.NONE.withParameterPrefix("x_")).isSameAs(SqlFragment.NONE);
        assertThat(SqlFragment.DENY_ALL.withParameterPrefix("x_")).isSameAs(SqlFragment.DENY_ALL);
    }
}
