package edu.bu.archive.application.authorization.viewaccessas;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import edu.bu.archive.application.authorization.AuthorizationPolicy;

/**
 * The artifact a tester reads:
 *
 * <pre>
 * Record | Profile | Expected | Actual | Rule | PASS/FAIL
 * </pre>
 *
 * <p>The Rule column is the deliverable. A bare PERMIT/DENY tells a tester
 * nothing they could not have guessed; naming the engine's own reason or
 * denial code is what makes a run into evidence.
 *
 * <p>Every table stamps the policy, the evaluation instant and the migration
 * high-water mark, because a decision table outlives the policy that produced
 * it and an unstamped one is indistinguishable from a stale one.
 */
public final class DecisionTable {

    /** One evaluated cell. {@code expected} is the tester's claim; {@code actual} is the engine's. */
    public record Row(
            String record,
            String profile,
            Verdict expected,
            Verdict actual,
            String rule
    ) {
        public boolean passed() {
            return expected == actual;
        }

        public String status() {
            return passed() ? "PASS" : "FAIL";
        }
    }

    private final String title;
    private final AuthorizationPolicy policy;
    private final Instant evaluatedAt;
    private final List<Row> rows = new ArrayList<>();

    public DecisionTable(String title, AuthorizationPolicy policy, Instant evaluatedAt) {
        this.title = title;
        this.policy = policy;
        this.evaluatedAt = evaluatedAt;
    }

    public DecisionTable add(String record, SyntheticProfile profile, Verdict expected,
                             ViewAccessAsHarness.Evaluation evaluation) {
        rows.add(new Row(record, profile.name(), expected, evaluation.verdict(), evaluation.rule()));
        return this;
    }

    public List<Row> rows() {
        return List.copyOf(rows);
    }

    public List<Row> failures() {
        return rows.stream().filter(row -> !row.passed()).toList();
    }

    public long count(Verdict verdict) {
        return rows.stream().filter(row -> row.actual() == verdict).count();
    }

    /** Fixed-width so successive runs diff cleanly. */
    public String render() {
        int recordWidth = width("Record", Row::record);
        int profileWidth = width("Profile", Row::profile);
        int ruleWidth = width("Rule", Row::rule);

        StringBuilder out = new StringBuilder();
        out.append("View Access As - decision table\n");
        out.append(title).append('\n');
        out.append("policy        versionScope=").append(policy.versionScope())
                .append("  departmentMatch=").append(policy.departmentMatch())
                .append("  contactDerivation=").append(policy.contactDerivation()).append('\n');
        out.append("              researchStaffRoles=")
                .append(policy.researchStaffRoles().stream().sorted().toList()).append('\n');
        out.append("evaluated at  ").append(evaluatedAt).append('\n');
        out.append("engine        RecordAccessEvaluator + AccessScopeResolver (production classes)\n");
        out.append("data          synthetic RecordFacts - no archive record was read\n\n");

        out.append(pad("Record", recordWidth)).append(" | ")
                .append(pad("Profile", profileWidth)).append(" | ")
                .append(pad("Expected", 13)).append(" | ")
                .append(pad("Actual", 13)).append(" | ")
                .append(pad("Rule", ruleWidth)).append(" | Status\n");
        out.append("-".repeat(recordWidth)).append("-+-")
                .append("-".repeat(profileWidth)).append("-+-")
                .append("-".repeat(13)).append("-+-")
                .append("-".repeat(13)).append("-+-")
                .append("-".repeat(ruleWidth)).append("-+-------\n");

        for (Row row : rows) {
            out.append(pad(row.record(), recordWidth)).append(" | ")
                    .append(pad(row.profile(), profileWidth)).append(" | ")
                    .append(pad(row.expected().name(), 13)).append(" | ")
                    .append(pad(row.actual().name(), 13)).append(" | ")
                    .append(pad(row.rule(), ruleWidth)).append(" | ")
                    .append(row.status()).append('\n');
        }

        out.append('\n');
        out.append(rows.size()).append(" rows   ")
                .append(count(Verdict.PERMIT)).append(" PERMIT   ")
                .append(count(Verdict.DENY)).append(" DENY   ")
                .append(count(Verdict.INDETERMINATE)).append(" INDETERMINATE   ")
                .append(failures().size()).append(" FAIL\n");
        return out.toString();
    }

    /** Writes beside the surefire reports. Never committed; regenerated per run. */
    public Path writeTo(Path directory, String fileName) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve(fileName);
        Files.writeString(file, render(), StandardCharsets.UTF_8);
        return file;
    }

    private int width(String header, java.util.function.Function<Row, String> field) {
        int widest = header.length();
        for (Row row : rows) {
            widest = Math.max(widest, field.apply(row).length());
        }
        return widest;
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
    }
}
