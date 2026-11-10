package edu.bu.archive.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The release gate for the temporary View Access As QA harness.
 *
 * <p>The harness lives in test scope, so it cannot be packaged - but "cannot"
 * is a claim about the build, and a claim about the build is exactly the kind
 * of thing that silently stops being true. This asserts it on the artifact.
 *
 * <h2>Why a checklist is not enough</h2>
 *
 * {@code docs/QA_STATUS_PAGE.md} documents this project's previous temporary QA
 * feature and records why its hand-written removal list was insufficient:
 *
 * <blockquote>a branch cut after an unwanted change landed still carries that
 * change unless it removes it, however cleanly the branch merges</blockquote>
 *
 * and
 *
 * <blockquote><strong>Ancestry alone does not describe running
 * behaviour.</strong></blockquote>
 *
 * A checklist describes {@code main}. A release ships an <em>image</em>.
 *
 * <h2>The positive control</h2>
 *
 * A gate that fails to open the jar, or opens an empty one, would report "no
 * simulator classes found" and pass. So this also asserts that known
 * production authorization classes ARE present. Without that, absence of
 * evidence reads as evidence of absence - the exact failure mode recorded in
 * this project after three jar-inspection false negatives (a glob that matched
 * nothing, {@code unzip} and {@code strings} missing from the JRE image, and
 * macOS {@code strings} misreading {@code .class} files as Mach-O).
 */
@DisplayName("No simulator code reaches the production artifact")
class ViewAccessAsAbsentFromJarTest {

    /** Anything whose packaged path contains one of these must not ship. */
    private static final List<String> FORBIDDEN = List.of("viewaccessas", "ViewAccessAs");

    /** Must be present, or the inspection itself is not working. */
    private static final List<String> EXPECTED = List.of(
            "BOOT-INF/classes/edu/bu/archive/application/authorization/RecordAccessEvaluator.class",
            "BOOT-INF/classes/edu/bu/archive/application/authorization/AccessScopeResolver.class");

    @Test
    void theBuiltJarCarriesNoSimulatorClassesAndDoesCarryTheRealEngine() throws IOException {
        Optional<Path> jar = locateBootJar();
        assumeThat(jar)
                .as("no packaged jar in target/ - run `mvn package` or let the deploy script "
                        + "build one; this gate asserts on the artifact, not on the source tree")
                .isPresent();

        List<String> entries;
        try (ZipFile zip = new ZipFile(jar.get().toFile())) {
            entries = zip.stream().map(entry -> entry.getName()).toList();
        }

        // Positive control FIRST: if this fails, every result below is meaningless.
        assertThat(entries)
                .as("the inspection must be able to see known production classes, "
                        + "or its silence proves nothing")
                .contains(EXPECTED.toArray(String[]::new));

        for (String forbidden : FORBIDDEN) {
            List<String> offenders = entries.stream()
                    .filter(name -> name.contains(forbidden))
                    .toList();
            assertThat(offenders)
                    .as("the temporary QA simulator must never be packaged "
                            + "(matched \"" + forbidden + "\" in " + jar.get().getFileName() + ")")
                    .isEmpty();
        }
    }

    @Test
    void theCompiledMainClassesCarryNoSimulatorClass() throws IOException {
        /*
         * The jar check above is the artifact-level assertion, but it has a
         * blind spot: during `mvn package` the test phase runs BEFORE
         * repackaging, so the jar it inspects is the PREVIOUS build's. This
         * closes that gap - target/classes is what will be packaged into
         * BOOT-INF/classes, and it is current on every test run, so this is
         * the assertion that actually guards CI.
         */
        Path classes = Files.isDirectory(Path.of("target", "classes"))
                ? Path.of("target", "classes")
                : Path.of("api", "target", "classes");
        assumeThat(Files.isDirectory(classes))
                .as("no compiled main classes yet").isTrue();

        /*
         * Regular files only, deliberately. Maven's incremental compile
         * removes an orphaned .class but leaves its directory, and an empty
         * directory ships no code. The jar check above matches directory
         * entries too, so it is the stricter of the two - if they ever
         * disagree, that asymmetry is why.
         */
        try (Stream<Path> files = Files.walk(classes)) {
            List<String> offenders = files
                    .filter(Files::isRegularFile)
                    .map(path -> classes.relativize(path).toString())
                    .filter(name -> FORBIDDEN.stream().anyMatch(name::contains))
                    .sorted(Comparator.naturalOrder())
                    .toList();
            assertThat(offenders)
                    .as("nothing matching the simulator may be compiled into main classes, "
                            + "because everything here is packaged")
                    .isEmpty();
        }

        // Positive control: the same walk must be able to find a known
        // production class, or its silence about the simulator means nothing.
        try (Stream<Path> files = Files.walk(classes)) {
            assertThat(files.map(path -> classes.relativize(path).toString()))
                    .as("the walk must be able to see known production classes")
                    .contains("edu/bu/archive/application/authorization/RecordAccessEvaluator.class");
        }
    }

    @Test
    void theSimulatorSourceLivesOnlyInTestScope() throws IOException {
        /*
         * The source-tree half of the gate, which runs even when no jar has
         * been built. If a simulator class ever appears under src/main, the
         * jar assertion above would start failing on the next package - this
         * says so immediately, and names the file.
         */
        Path main = Path.of("src", "main", "java");
        Path mainDir = Files.isDirectory(main) ? main : Path.of("api", "src", "main", "java");
        assumeThat(Files.isDirectory(mainDir)).as("main source tree not found from " + Path.of(".")
                .toAbsolutePath()).isTrue();

        try (Stream<Path> files = Files.walk(mainDir)) {
            List<String> offenders = files
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String text = path.toString();
                        return text.contains("viewaccessas") || text.contains("ViewAccessAs");
                    })
                    .map(Path::toString)
                    .sorted(Comparator.naturalOrder())
                    .toList();
            assertThat(offenders)
                    .as("View Access As is temporary QA tooling and must stay in test scope; "
                            + "moving it to src/main would make its removal a promise rather "
                            + "than a property of the build")
                    .isEmpty();
        }
    }

    private static Optional<Path> locateBootJar() throws IOException {
        Path target = Files.isDirectory(Path.of("target")) ? Path.of("target") : Path.of("api", "target");
        if (!Files.isDirectory(target)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(target)) {
            return files
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .filter(path -> !path.getFileName().toString().endsWith("-sources.jar"))
                    .filter(path -> !path.getFileName().toString().endsWith(".jar.original"))
                    .max(Comparator.comparingLong(path -> path.toFile().length()));
        }
    }
}
