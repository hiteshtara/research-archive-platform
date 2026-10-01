package edu.bu.archive.demo.authz;

import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;

import edu.bu.archive.application.authorization.IoResolver;
import edu.bu.archive.application.authorization.IoSqlStrategy;
import edu.bu.archive.application.authorization.RecordModule;

/**
 * Synthetic IO values (authz_demo.award_io), shared by the persona demo and
 * the local SAML lab. The real IO field remains unresolved (D-A).
 */
public final class SyntheticIo {

    private SyntheticIo() {
    }

    public static IoResolver resolver(JdbcClient jdbc) {
        return (module, versionKey) -> module != RecordModule.AWARD
                ? Set.of()
                : Set.copyOf(jdbc.sql("SELECT io_value FROM authz_demo.award_io WHERE award_id = CAST(:id AS BIGINT)")
                        .param("id", versionKey).query(String.class).list());
    }

    public static IoSqlStrategy sqlStrategy() {
        return (module, rowAlias) -> module == RecordModule.AWARD
                ? Optional.of("EXISTS (SELECT 1 FROM authz_demo.award_io az_io WHERE az_io.award_id = "
                        + rowAlias + ".award_id AND az_io.io_value IN (:az_ios))")
                : Optional.empty();
    }
}
