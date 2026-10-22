package edu.bu.archive.adapter.out.persistence.authorization;

import java.util.Optional;

import edu.bu.archive.application.authorization.IoSqlStrategy;
import edu.bu.archive.application.authorization.RecordModule;

/**
 * SQL form of the approved IO rule (decision D-A): an IO grant matches Award rows whose
 * account number equals the granted value, exactly, after trimming. Awards only.
 */
public final class AwardAccountNumberIoSql implements IoSqlStrategy {

    @Override
    public Optional<String> ioPredicate(RecordModule module, String rowAlias) {
        return module == RecordModule.AWARD
                ? Optional.of("TRIM(" + rowAlias + ".account_number) IN (:az_ios)")
                : Optional.empty();
    }
}
