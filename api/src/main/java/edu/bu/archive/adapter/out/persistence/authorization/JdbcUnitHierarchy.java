package edu.bu.archive.adapter.out.persistence.authorization;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import edu.bu.archive.application.authorization.UnitHierarchy;

/**
 * archive.unit ancestry, walked upward from the record's unit. Bounded depth
 * so a cycle in source data can never loop; an unknown unit is never a
 * descendant of anything (fails closed).
 */
@Repository
public class JdbcUnitHierarchy implements UnitHierarchy {

    static final int MAX_DEPTH = 32;

    private final JdbcClient jdbc;

    public JdbcUnitHierarchy(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isSameOrDescendant(String unitNumber, String ancestorUnitNumber) {
        if (unitNumber == null || ancestorUnitNumber == null) {
            return false;
        }
        if (unitNumber.equals(ancestorUnitNumber)) {
            return true;
        }
        return jdbc.sql("""
                        WITH RECURSIVE up(unit_number, parent_unit_number, depth) AS (
                            SELECT unit_number, parent_unit_number, 0
                            FROM archive.unit WHERE unit_number = :unit
                            UNION ALL
                            SELECT u.unit_number, u.parent_unit_number, up.depth + 1
                            FROM archive.unit u
                            JOIN up ON u.unit_number = up.parent_unit_number
                            WHERE up.depth < :maxDepth
                        )
                        SELECT EXISTS (SELECT 1 FROM up WHERE unit_number = :ancestor AND depth > 0)
                        """)
                .param("unit", unitNumber)
                .param("ancestor", ancestorUnitNumber)
                .param("maxDepth", MAX_DEPTH)
                .query(Boolean.class)
                .single();
    }
}
