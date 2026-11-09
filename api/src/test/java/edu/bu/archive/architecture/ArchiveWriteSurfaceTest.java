package edu.bu.archive.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SEC-009, application half: no code path in the API writes archive data.
 *
 * <p>{@code database/roles/archive_api_role.sql} justifies granting SELECT and
 * nothing else on the archive schema with a claim about this source tree —
 * "there is NO INSERT, UPDATE or DELETE against any archive table anywhere in
 * api/src/main/java". That was true when a person read the tree. This test is
 * what keeps it true, and it fails on the commit that breaks it rather than at
 * the next audit.
 *
 * <p>Three sweeps, because each alone has a blind spot:
 *
 * <ol>
 *   <li><b>Schema-qualified writes.</b> Every write statement naming
 *       {@code schema.table} must name the {@code authz} schema.
 *   <li><b>Unqualified writes.</b> A write naming a bare table resolves by
 *       {@code search_path} and so cannot be read as safe either way. There
 *       are none, and there should stay none.
 *   <li><b>DML call sites.</b> Every JdbcClient {@code .update()} — the call
 *       that executes DML whatever the SQL text looks like — must live in the
 *       authorization persistence package. SQL assembled from fragments would
 *       slip past sweeps 1 and 2; this catches it by where it is issued from.
 * </ol>
 *
 * <p>Sweep 2 matches only full statement shapes ({@code UPDATE x SET},
 * {@code INSERT INTO x (}, {@code DELETE FROM x WHERE}) rather than a verb
 * followed by a word. The first version of this test matched the looser thing
 * and flagged four PDF column headings and a sentence containing "copy of":
 * prose inside string literals, which the comment stripper deliberately keeps
 * because that is also where the SQL lives. {@code COPY} is left out of the
 * source sweep entirely for the same reason — it is checked where it is
 * actually enforceable, against the database role.
 *
 * <p>The database role is the control that actually stops a write (see
 * {@code ArchiveReadOnlyPrivilegeIntegrationTest}). This is the control that
 * stops one being written, where the failure is cheap to read and needs no
 * container.
 */
class ArchiveWriteSurfaceTest {

    /** A write statement against a schema-qualified relation. */
    private static final Pattern QUALIFIED_WRITE = Pattern.compile(
            "(?i)(?<verb>INSERT\\s+INTO|DELETE\\s+FROM|MERGE\\s+INTO|TRUNCATE(?:\\s+TABLE)?"
                    + "|UPDATE)\\s+(?<schema>[A-Za-z_][A-Za-z0-9_$]*)\\.(?<table>[A-Za-z_][A-Za-z0-9_$]*)");

    /**
     * A write statement against a bare relation name. Each alternative
     * requires the clause that follows the relation in real SQL, so a verb
     * that merely appears next to a word in prose does not match.
     */
    private static final Pattern UNQUALIFIED_WRITE = Pattern.compile(
            "(?i)(?<statement>INSERT\\s+INTO\\s+(?<t1>[A-Za-z_][A-Za-z0-9_$]*)\\s*(?:\\(|VALUES|SELECT|DEFAULT\\s+VALUES)"
                    + "|DELETE\\s+FROM\\s+(?<t2>[A-Za-z_][A-Za-z0-9_$]*)\\s+WHERE"
                    + "|UPDATE\\s+(?<t3>[A-Za-z_][A-Za-z0-9_$]*)\\s+SET"
                    + "|MERGE\\s+INTO\\s+(?<t4>[A-Za-z_][A-Za-z0-9_$]*)\\s+USING)");

    /**
     * {@code UPDATE} is a word in SQL as well as a statement. These are the
     * clauses it appears in that start no statement: {@code ON CONFLICT ... DO
     * UPDATE SET}, {@code SELECT ... FOR UPDATE}, {@code ON UPDATE CASCADE}.
     */
    private static final Set<String> NOT_A_STATEMENT_BEFORE_UPDATE =
            Set.of("do", "for", "on", "no");

    /** The one schema the API is allowed to write. */
    private static final String WRITABLE_SCHEMA = "authz";

    /** Where DML may be issued from. */
    private static final String AUTHORIZATION_PERSISTENCE =
            "edu/bu/archive/adapter/out/persistence/authorization";

    @Test
    @DisplayName("every schema-qualified write in the API targets the authz schema")
    void everyQualifiedWriteTargetsTheAuthzSchema() throws IOException {
        List<String> sources = javaSources();
        assertThat(sources)
                .as("API source files scanned - an empty scan must not pass")
                .hasSizeGreaterThanOrEqualTo(200);

        List<String> offenders = new ArrayList<>();
        int writesFound = 0;

        for (String file : sources) {
            String code = stripComments(Files.readString(path(file)));
            Matcher m = QUALIFIED_WRITE.matcher(code);
            while (m.find()) {
                if (m.group("verb").equalsIgnoreCase("update")
                        && precededByClauseKeyword(code, m.start())) {
                    continue;
                }
                writesFound++;
                String schema = m.group("schema").toLowerCase(Locale.ROOT);
                if (!schema.equals(WRITABLE_SCHEMA)) {
                    offenders.add(file + ": " + m.group("verb").toUpperCase(Locale.ROOT) + " "
                            + m.group("schema") + "." + m.group("table"));
                }
            }
        }

        assertThat(writesFound)
                .as("qualified write statements found - the sweep is worthless if it matches "
                        + "nothing, and the authz admin repositories contain several")
                .isGreaterThanOrEqualTo(8);
        assertThat(offenders)
                .as("every write in the API must target the authz schema. An archive.* target "
                        + "breaks the read-only guarantee (SEC-009)")
                .isEmpty();
    }

    @Test
    @DisplayName("no write in the API names an unqualified relation")
    void noWriteNamesAnUnqualifiedRelation() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (String file : javaSources()) {
            String code = stripComments(Files.readString(path(file)));
            Matcher m = UNQUALIFIED_WRITE.matcher(code);
            while (m.find()) {
                String statement = m.group("statement");
                if (statement.toLowerCase(Locale.ROOT).startsWith("update")
                        && precededByClauseKeyword(code, m.start())) {
                    continue;
                }
                offenders.add(file + ": " + statement.replaceAll("\\s+", " ").trim());
            }
        }

        assertThat(offenders)
                .as("a write against a bare relation name resolves by search_path, so it cannot "
                        + "be read as safe. Qualify it - and if it names an archive table, "
                        + "SEC-009 says it should not exist at all")
                .isEmpty();
    }

    @Test
    @DisplayName("DML is issued only from the authorization persistence package")
    void dmlIsIssuedOnlyFromTheAuthorizationPackage() throws IOException {
        List<String> offenders = new ArrayList<>();
        int callSites = 0;

        for (String file : javaSources()) {
            String code = stripComments(Files.readString(path(file)));
            int occurrences = countOccurrences(code, ".update()");
            if (occurrences == 0) {
                continue;
            }
            callSites += occurrences;
            if (!file.startsWith(AUTHORIZATION_PERSISTENCE)) {
                offenders.add(file + ": " + occurrences + " JdbcClient .update() call(s)");
            }
        }

        assertThat(callSites)
                .as("JdbcClient .update() call sites found")
                .isGreaterThanOrEqualTo(8);
        assertThat(offenders)
                .as("JdbcClient.update() executes DML. Outside " + AUTHORIZATION_PERSISTENCE
                        + " there is nothing it could legitimately write: archive data is "
                        + "read-only")
                .isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static boolean precededByClauseKeyword(String code, int verbStart) {
        int end = verbStart;
        while (end > 0 && Character.isWhitespace(code.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0 && Character.isLetter(code.charAt(start - 1))) {
            start--;
        }
        return NOT_A_STATEMENT_BEFORE_UPDATE.contains(
                code.substring(start, end).toLowerCase(Locale.ROOT));
    }

    /**
     * Removes line comments, block comments and Javadoc, so prose describing a
     * write is not read as one. This repository's comments discuss the
     * {@code INSERT ... ON CONFLICT} shapes at length, so without this the
     * sweeps would flag the explanations of the code rather than the code.
     *
     * <p>String and text-block contents are deliberately KEPT — that is where
     * the SQL lives.
     */
    private static String stripComments(String java) {
        StringBuilder out = new StringBuilder(java.length());
        boolean inLine = false;
        boolean inBlock = false;
        for (int i = 0; i < java.length(); i++) {
            char c = java.charAt(i);
            char next = i + 1 < java.length() ? java.charAt(i + 1) : '\0';
            if (inLine) {
                if (c == '\n') {
                    inLine = false;
                    out.append(c);
                }
            } else if (inBlock) {
                if (c == '*' && next == '/') {
                    inBlock = false;
                    i++;
                } else if (c == '\n') {
                    out.append(c);
                }
            } else if (c == '/' && next == '/') {
                inLine = true;
                i++;
            } else if (c == '/' && next == '*') {
                inBlock = true;
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    /** Source-root-relative paths of every API main source, for readable failures. */
    private static List<String> javaSources() throws IOException {
        Path root = sourceRoot();
        try (Stream<Path> files = Files.walk(root)) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .map(p -> root.relativize(p).toString())
                    .sorted()
                    .toList();
        }
    }

    private static Path path(String relative) {
        return sourceRoot().resolve(relative);
    }

    /** Resolves whether the suite runs from the repository root or from {@code api/}. */
    private static Path sourceRoot() {
        Path c = Path.of("src", "main", "java");
        return Files.isDirectory(c) ? c : Path.of("api", "src", "main", "java");
    }
}
