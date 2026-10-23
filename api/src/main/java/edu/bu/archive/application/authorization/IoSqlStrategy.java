package edu.bu.archive.application.authorization;

import java.util.Optional;

/**
 * How an IO grant is expressed in SQL for one module's row alias. The real
 * strategy has NO mapping (the authoritative IO field is pending decision
 * D-A), so IO grants reach no real record. A demo build may provide a
 * synthetic mapping.
 */
public interface IoSqlStrategy {

    /** A boolean SQL expression using the named parameter {@code :az_ios}, or empty. */
    Optional<String> ioPredicate(RecordModule module, String rowAlias);

    IoSqlStrategy NONE = (module, rowAlias) -> Optional.empty();
}
