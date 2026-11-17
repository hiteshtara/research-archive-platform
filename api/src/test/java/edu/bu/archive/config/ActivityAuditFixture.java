package edu.bu.archive.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Synthetic archive and identity fixtures for the SEC-008 / US-SEC-004
 * end-to-end activity-audit suites. No BU data.
 *
 * <p>One record of each in-scope module the synthetic user MAY see (lead unit
 * 7777, which they hold a UNIT grant on) and one they may NOT (unit 1234).
 * IRB is out of release scope and deliberately absent.
 */
final class ActivityAuditFixture {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    static final String ISSUER = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_SYNAUDIT";
    static final String SUBJECT = "syn-audit-subject-0001";
    static final String UNLINKED_SUBJECT = "syn-audit-subject-unlinked";
    static final String INSTITUTIONAL_ID = "ARP-SYNAUDIT-00000001";

    /** Values that must NEVER appear anywhere in an activity row. */
    static final String SECRET_TOKEN = "syn-secret-jwt-token-value-7f3a";
    static final String SECRET_FILE_NAME = "SYN-SECRET-FILENAME-91c2.pdf";
    static final String SECRET_DESCRIPTION = "SYN-SECRET-DESCRIPTION-44be";
    static final String SECRET_SEARCH_TERM = "synsecretsearchterm5d1e";
    static final String SECRET_CONTENT = "%PDF-1.4 SYN-SECRET-FILE-CONTENT-b81f";

    /** Owned by each module's PERMITTED record; downloadable. */
    static final long ATTACHMENT_ID = 4242L;
    /** Owned by each module's OTHER (denied) record. */
    static final long FOREIGN_ATTACHMENT_ID = 4343L;
    /** No attachment of any module has this id. */
    static final long MISSING_ATTACHMENT_ID = 999_999L;

    static final Path ATTACHMENT_DIRECTORY;

    static {
        try {
            ATTACHMENT_DIRECTORY = Files.createTempDirectory("syn-activity-audit-attachments");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The in-scope modules, each with one permitted and one denied record. */
    enum Module {
        AWARD("/api/v1/awards/%d/summary", "/api/v1/awards/%d/attachments/%d/download",
                5002L, "SYN-AWD-OK", 6001L, "SYN-AWD-NO"),
        PROPOSAL("/api/v1/proposals/%d", "/api/v1/proposals/%d/attachments/%d/download",
                8001L, "SYN-PROP-OK", 8101L, "SYN-PROP-NO"),
        SUBAWARD("/api/subawards/%d", "/api/subawards/%d/attachments/%d/download",
                9001L, "SYN-SUB-OK", 9005L, "SYN-SUB-NO"),
        NEGOTIATION("/api/negotiations/%d", "/api/negotiations/%d/attachments/%d/download",
                100L, "100", 101L, "101");

        final String viewPath;
        final String downloadPath;
        final long permittedId;
        final String permittedKey;
        final long deniedId;
        final String deniedKey;

        Module(String viewPath, String downloadPath, long permittedId, String permittedKey,
               long deniedId, String deniedKey) {
            this.viewPath = viewPath;
            this.downloadPath = downloadPath;
            this.permittedId = permittedId;
            this.permittedKey = permittedKey;
            this.deniedId = deniedId;
            this.deniedKey = deniedKey;
        }

        String view(long id) {
            return viewPath.formatted(id);
        }

        String download(long id, long attachmentId) {
            return downloadPath.formatted(id, attachmentId);
        }
    }

    private ActivityAuditFixture() {
    }

    /** Every committed migration, then the held-back V089, then the archive seed. */
    static void prepare(PostgreSQLContainer<?> postgres) throws Exception {
        try (Connection connection = postgres.createConnection("");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            for (Path migration : migrations(locate("migrations"))) {
                statement.execute(Files.readString(migration));
            }
            for (Path migration : migrations(locate("migrations-pending-approval"))) {
                statement.execute(Files.readString(migration));
            }
            seed(statement);
        }
    }

    private static void seed(Statement statement) throws Exception {
        statement.execute("""
                INSERT INTO archive.unit (unit_number, unit_name, parent_unit_number) VALUES
                ('7777', 'SYN CHEMISTRY', NULL),
                ('1234', 'SYN PHYSICS',   NULL)
                """);
        statement.execute("""
                INSERT INTO archive.award_version (
                    award_id, award_number, sequence_number, title,
                    status_description, sponsor_code, sponsor_name,
                    lead_unit_number, lead_unit_name, award_effective_date,
                    is_primary_current, workflow_document_number
                ) VALUES
                (5002, 'SYN-AWD-OK', 1, 'Synthetic permitted award', 'Active',
                 '0200', 'SYN SPONSOR', '7777', 'SYN CHEMISTRY', DATE '2020-01-01', TRUE, 'SYN-DOC-A1'),
                (6001, 'SYN-AWD-NO', 1, 'Synthetic denied award',    'Active',
                 '0100', 'SYN SPONSOR', '1234', 'SYN PHYSICS',   DATE '2020-06-01', TRUE, 'SYN-DOC-A2')
                """);
        // The Award report needs a Time & Money summary row to build.
        statement.execute("""
                INSERT INTO archive.award_amount_info (
                    award_amount_info_id, award_id, award_number, sequence_number
                ) VALUES (71, 5002, 'SYN-AWD-OK', 1)
                """);
        statement.execute("""
                INSERT INTO archive.proposal_version (
                    proposal_id, proposal_number, version_number, title,
                    proposal_sequence_status, sponsor_code, sponsor_name,
                    lead_unit_number, lead_unit_name, principal_investigator_name,
                    document_number
                ) VALUES
                (8001, 'SYN-PROP-OK', 1, 'Synthetic permitted proposal', 'ACTIVE',
                 '0200', 'SYN SPONSOR', '7777', 'SYN CHEMISTRY', 'SYN PI', 'SYN-DOC-P1'),
                (8101, 'SYN-PROP-NO', 1, 'Synthetic denied proposal',    'ACTIVE',
                 '0100', 'SYN SPONSOR', '1234', 'SYN PHYSICS',   'SYN PI', 'SYN-DOC-P2')
                """);
        statement.execute("""
                INSERT INTO archive.subaward (
                    subaward_id, subaward_code, sequence_number, document_number,
                    title, status_description, subaward_sequence_status,
                    organization_id, account_number, requisitioner_unit
                ) VALUES
                (9001, 'SYN-SUB-OK', 1, 'SYN-SDOC-1', 'Synthetic permitted subaward',
                 'Active', 'ACTIVE', 'SYN-ORG', 'SYN-ACC1', '7777'),
                (9005, 'SYN-SUB-NO', 1, 'SYN-SDOC-2', 'Synthetic denied subaward',
                 'Active', 'ACTIVE', 'SYN-ORG', 'SYN-ACC2', '1234')
                """);
        statement.execute("""
                INSERT INTO archive.subaward_funding (
                    subaward_funding_source_id, subaward_id, subaward_code,
                    sequence_number, award_id, award_number
                ) VALUES
                (301, 9001, 'SYN-SUB-OK', 1, 5002, 'SYN-AWD-OK'),
                (302, 9005, 'SYN-SUB-NO', 1, 6001, 'SYN-AWD-NO')
                """);
        statement.execute("""
                INSERT INTO archive.negotiation (
                    negotiation_id, document_number,
                    negotiation_association_type_code,
                    negotiation_association_type_description,
                    associated_document_id, negotiator_person_id, negotiator_full_name
                ) VALUES
                (100, 'SYN-NDOC-100', 'NO', 'None', 'SYN-NDOC-100', 'SYN-NEG', 'SYN NEGOTIATOR'),
                (101, 'SYN-NDOC-101', 'NO', 'None', 'SYN-NDOC-101', 'SYN-NEG', 'SYN NEGOTIATOR')
                """);
        statement.execute("""
                INSERT INTO archive.negotiation_search_attribute (
                    negotiation_id, attribute_source, title, lead_unit_number, lead_unit_name
                ) VALUES
                (100, 'UNASSOCIATED_DETAIL', 'Synthetic permitted negotiation', '7777', 'SYN CHEMISTRY'),
                (101, 'UNASSOCIATED_DETAIL', 'Synthetic denied negotiation',    '1234', 'SYN PHYSICS')
                """);
        /*
         * Records RELATED to the permitted Award, so its related-record list
         * endpoints (funding proposals, funding subawards, negotiations) have
         * rows to list - one the caller may open and, for proposals, one they
         * may not. Listing them must not be recorded as viewing them.
         */
        statement.execute("""
                INSERT INTO archive.award_funding_proposal (
                    award_funding_proposal_id, award_id, proposal_id, active_flag
                ) VALUES (601, 5002, 8001, 'Y'), (602, 5002, 8101, 'Y')
                """);
        statement.execute("""
                INSERT INTO archive.negotiation (
                    negotiation_id, document_number,
                    negotiation_association_type_code,
                    negotiation_association_type_description,
                    associated_document_id, negotiator_person_id, negotiator_full_name
                ) VALUES
                (102, 'SYN-NDOC-102', 'AWD', 'Award', 'SYN-AWD-OK', 'SYN-NEG', 'SYN NEGOTIATOR')
                """);
        statement.execute("""
                INSERT INTO archive.negotiation_search_attribute (
                    negotiation_id, attribute_source, title, lead_unit_number, lead_unit_name
                ) VALUES (102, 'AWARD', 'Synthetic related negotiation', '7777', 'SYN CHEMISTRY')
                """);
        statement.execute("""
                INSERT INTO authz.identity_link (
                    cognito_issuer, cognito_subject, institutional_identifier,
                    kuali_person_id, login_name, method, status, verified_by, verified_at
                ) VALUES ('%s', '%s', '%s', NULL, NULL, 'ADMIN_VERIFIED', 'ACTIVE',
                          'syn-test-operator', CURRENT_TIMESTAMP - INTERVAL '1 day')
                """.formatted(ISSUER, SUBJECT, INSTITUTIONAL_ID));
        statement.execute("""
                INSERT INTO authz.access_grant (
                    institutional_identifier, grant_type, unit_number, include_descendants,
                    granted_by, reason, valid_from
                ) VALUES ('%s', 'UNIT', '7777', FALSE, 'syn-test-operator', 'synthetic',
                          CURRENT_TIMESTAMP - INTERVAL '1 day')
                """.formatted(INSTITUTIONAL_ID));
        seedAttachments(statement);
    }

    /**
     * One real, downloadable attachment per module ({@link #ATTACHMENT_ID},
     * owned by the module's permitted record) and one owned by the module's
     * OTHER record ({@link #FOREIGN_ATTACHMENT_ID}), stored by the local
     * attachment storage under {@link #ATTACHMENT_DIRECTORY}. File names,
     * descriptions and bytes are secrets no audit row may copy.
     */
    private static void seedAttachments(Statement statement) throws Exception {
        for (Module module : Module.values()) {
            String key = "syn/" + module.name().toLowerCase() + "-" + ATTACHMENT_ID + ".pdf";
            Path file = ATTACHMENT_DIRECTORY.resolve(key);
            Files.createDirectories(file.getParent());
            Files.writeString(file, SECRET_CONTENT);
        }
        statement.execute("""
                INSERT INTO archive.attachment_object
                    (file_id, file_name, content_type, s3_bucket, s3_key, upload_status)
                VALUES (880, '%1$s', 'application/pdf', 'local-fixtures', 'syn/award-%2$d.pdf', 'UPLOADED'),
                       (881, 'foreign.pdf', 'application/pdf', 'local-fixtures', 'syn/award-foreign.pdf', 'UPLOADED')
                """.formatted(SECRET_FILE_NAME, ATTACHMENT_ID));
        statement.execute("""
                INSERT INTO archive.award_attachment (
                    award_attachment_id, award_id, award_number, sequence_number, file_id, description
                ) VALUES (%1$d, 5002, 'SYN-AWD-OK', 1, 880, '%3$s'),
                         (%2$d, 6001, 'SYN-AWD-NO', 1, 881, 'foreign')
                """.formatted(ATTACHMENT_ID, FOREIGN_ATTACHMENT_ID, SECRET_DESCRIPTION));
        statement.execute("""
                INSERT INTO archive.proposal_attachment (
                    proposal_attachment_id, proposal_id, proposal_number, sequence_number,
                    file_name, content_type, s3_bucket, object_key, upload_status
                ) VALUES (%1$d, 8001, 'SYN-PROP-OK', 1, '%3$s', 'application/pdf',
                          'local-fixtures', 'syn/proposal-%1$d.pdf', 'UPLOADED'),
                         (%2$d, 8101, 'SYN-PROP-NO', 1, 'foreign.pdf', 'application/pdf',
                          'local-fixtures', 'syn/proposal-foreign.pdf', 'UPLOADED')
                """.formatted(ATTACHMENT_ID, FOREIGN_ATTACHMENT_ID, SECRET_FILE_NAME));
        statement.execute("""
                INSERT INTO archive.subaward_attachment (
                    attachment_id, subaward_id, subaward_code, sequence_number
                ) VALUES (%1$d, 9001, 'SYN-SUB-OK', 1), (%2$d, 9005, 'SYN-SUB-NO', 1)
                """.formatted(ATTACHMENT_ID, FOREIGN_ATTACHMENT_ID));
        statement.execute("""
                INSERT INTO archive.subaward_attachment_archive (
                    attachment_id, subaward_id, subaward_code, sequence_number,
                    original_file_name, mime_type, s3_bucket, s3_key, archive_status
                ) VALUES (%1$d, 9001, 'SYN-SUB-OK', 1, '%3$s', 'application/pdf',
                          'local-fixtures', 'syn/subaward-%1$d.pdf', 'ARCHIVED'),
                         (%2$d, 9005, 'SYN-SUB-NO', 1, 'foreign.pdf', 'application/pdf',
                          'local-fixtures', 'syn/subaward-foreign.pdf', 'ARCHIVED')
                """.formatted(ATTACHMENT_ID, FOREIGN_ATTACHMENT_ID, SECRET_FILE_NAME));
        statement.execute("""
                INSERT INTO archive.archived_attachment (
                    archived_attachment_id, module_code, source_attachment_id, parent_record_id,
                    original_file_name, content_type, s3_bucket, s3_key, archive_status
                ) VALUES (%1$d, 'NEGOTIATION', %1$d, 100, '%3$s', 'application/pdf',
                          'local-fixtures', 'syn/negotiation-%1$d.pdf', 'ARCHIVED'),
                         (%2$d, 'NEGOTIATION', %2$d, 101, 'foreign.pdf', 'application/pdf',
                          'local-fixtures', 'syn/negotiation-foreign.pdf', 'ARCHIVED')
                """.formatted(ATTACHMENT_ID, FOREIGN_ATTACHMENT_ID, SECRET_FILE_NAME));
    }

    /** Points the local attachment storage at the fixture's files. */
    static void attachmentStorage(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("app.attachments.storage", () -> "local");
        registry.add("app.attachments.local-directory", ATTACHMENT_DIRECTORY::toString);
        registry.add("app.attachments.local-bucket", () -> "local-fixtures");
    }

    /** A validated access token for the linked synthetic user, carrying the attachment role. */
    static RequestPostProcessor linkedUser() {
        return user(SUBJECT);
    }

    /** Authenticated, but with no identity link at all. */
    static RequestPostProcessor unlinkedUser() {
        return user(UNLINKED_SUBJECT);
    }

    private static RequestPostProcessor user(String subject) {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(jwt -> jwt
                        .tokenValue(SECRET_TOKEN)
                        .issuer(ISSUER)
                        .subject(subject)
                        .claim("token_use", "access")
                        .claim("auth_time", Instant.now().minusSeconds(60))
                        .claim("username", "syn-audit-user"))
                .authorities(new SimpleGrantedAuthority("ROLE_ArchiveAttachmentViewer"));
    }

    private static List<Path> migrations(Path dir) throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> MIGRATION_VERSION.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(ActivityAuditFixture::version))
                    .toList();
        }
    }

    private static int version(Path path) {
        Matcher matcher = MIGRATION_VERSION.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            throw new IllegalStateException("not a migration: " + path);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static Path locate(String name) {
        Path candidate = Path.of("database", name);
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        Path parent = Path.of("..", "database", name);
        if (Files.isDirectory(parent)) {
            return parent;
        }
        throw new IllegalStateException("database/" + name + " not found");
    }
}
