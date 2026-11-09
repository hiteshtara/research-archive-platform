package edu.bu.archive.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import edu.bu.archive.adapter.in.web.DashboardController;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * SEC-009, HTTP half: no route exists through which archived research data
 * could be created, updated or deleted.
 *
 * <p>Scans every {@code @RestController} in the application and collects every
 * handler that answers anything other than {@code GET}. The result must match
 * {@link #EXPECTED_NON_GET_HANDLERS} exactly — an equality assertion, not a
 * search for known-bad names, so a route added later fails this test on
 * arrival and has to be justified by editing that list.
 *
 * <p>Eleven handlers are expected, in two groups:
 *
 * <ul>
 *   <li>eight on {@code AdminAuthorizationController}, which writes
 *       authorization records in {@code authz} — grants, identity links,
 *       suspensions, administrators. Managing who may read the archive is not
 *       a change to the archive, and these are the endpoints that make the
 *       distinction real;
 *   <li>two AI handlers and one evidence search, which are {@code POST} for
 *       the mundane reason that their request bodies do not belong in a query
 *       string. They read.
 * </ul>
 *
 * <p>A handler whose mapping names no HTTP method at all answers every verb,
 * {@code DELETE} included, and counts as non-GET here for that reason.
 */
class ArchiveHttpWriteSurfaceTest {

    private static final String BASE_PACKAGE = "edu.bu.archive";

    /**
     * Every non-GET handler the application is allowed to expose, as
     * {@code SimpleClassName#method -> VERBS}.
     *
     * <p>Adding a line here is a deliberate act. Before doing it, check which
     * group above the new route belongs to: if it writes archived research
     * data it belongs to neither, and SEC-009 is the reason.
     */
    private static final Set<String> EXPECTED_NON_GET_HANDLERS = new TreeSet<>(Set.of(
            // Authorization administration - writes authz, never archive.
            "AdminAuthorizationController#link -> [POST]",
            "AdminAuthorizationController#unlink -> [DELETE]",
            "AdminAuthorizationController#setSuspension -> [PUT]",
            "AdminAuthorizationController#grant -> [POST]",
            "AdminAuthorizationController#modifyGrant -> [PATCH]",
            "AdminAuthorizationController#revokeGrant -> [DELETE]",
            "AdminAuthorizationController#assignAdministrator -> [POST]",
            "AdminAuthorizationController#revokeAdministrator -> [DELETE]",
            // Reads that POST because they carry a request body.
            "AwardAiController#summarize -> [POST]",
            "AwardAiQuestionController#answer -> [POST]",
            "AwardEvidenceSearchController#evidenceSearch -> [POST]"));

    @Test
    @DisplayName("the only non-GET routes are authorization administration and body-carrying reads")
    void theOnlyNonGetRoutesAreAdministrativeOrBodyCarryingReads() {
        List<Class<?>> controllers = restControllers();
        assertThat(controllers)
                .as("@RestController classes found - an empty scan must not pass")
                .hasSizeGreaterThanOrEqualTo(20);

        Set<String> actual = new TreeSet<>();
        for (Class<?> controller : controllers) {
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping =
                        AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                RequestMethod[] verbs = mapping.method();
                boolean answersOnlyGet = verbs.length == 1 && verbs[0] == RequestMethod.GET;
                if (answersOnlyGet) {
                    continue;
                }
                actual.add(controller.getSimpleName() + "#" + method.getName() + " -> "
                        + (verbs.length == 0 ? "[ANY VERB]" : Arrays.toString(verbs)));
            }
        }

        assertThat(actual)
                .as("non-GET HTTP surface. A new entry here must be checked against SEC-009: "
                        + "archived research data is read-only, so no route may create, update "
                        + "or delete it")
                .isEqualTo(EXPECTED_NON_GET_HANDLERS);
    }

    @Test
    @DisplayName("no archive-domain controller exposes a non-GET route")
    void noArchiveDomainControllerExposesANonGetRoute() {
        /*
         * The equality assertion above is the real guard. This one states the
         * same thing the way the requirement does, so a failure reads as
         * "AwardV1Controller gained a DELETE" rather than as a set diff.
         */
        List<String> offenders = new ArrayList<>();
        for (Class<?> controller : restControllers()) {
            String name = controller.getSimpleName();
            if (name.startsWith("Admin")) {
                continue;
            }
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping =
                        AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                Set<RequestMethod> verbs = new TreeSet<>(Arrays.asList(mapping.method()));
                verbs.remove(RequestMethod.GET);
                verbs.remove(RequestMethod.POST); // justified case-by-case in the test above
                if (mapping.method().length == 0 || !verbs.isEmpty()) {
                    offenders.add(name + "#" + method.getName() + " -> "
                            + (mapping.method().length == 0
                                    ? "[ANY VERB]" : Arrays.toString(mapping.method())));
                }
            }
        }
        assertThat(offenders)
                .as("no controller serving archived research data may expose PUT, PATCH, "
                        + "DELETE or an unrestricted verb")
                .isEmpty();
    }

    /**
     * Every {@code @RestController} in the compiled main classes.
     *
     * <p>Deliberately NOT Spring's {@code ClassPathScanningCandidateComponentProvider}.
     * That scanner evaluates {@code @Conditional} metadata, and four
     * controllers here are gated by {@code @ConditionalOnProperty} —
     * {@code AwardAiController}, {@code AwardAiQuestionController},
     * {@code AwardEvidenceSearchController} and {@code ExplorerController}.
     * With no gating properties set it silently returned 16 of the 20
     * controllers and reported nothing missing. A security sweep that skips
     * feature-flagged code is worse than none: a flag is exactly where an
     * unreviewed route would hide. Walking {@code target/classes} and reading
     * the annotation directly evaluates no condition and skips nothing.
     *
     * <p>{@code @RestControllerAdvice} is not {@code @RestController}, so the
     * two exception handlers are correctly not included — a text search for
     * "@RestController" finds 22 files and would have counted them.
     */
    private static List<Class<?>> restControllers() {
        Path classes = compiledMainClasses();
        List<Class<?>> found = new ArrayList<>();
        List<String> unloadable = new ArrayList<>();

        List<Path> classFiles;
        try (Stream<Path> files = Files.walk(classes)) {
            classFiles = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".class"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot walk " + classes.toAbsolutePath(), e);
        }

        for (Path file : classFiles) {
            String binaryName = classes.relativize(file).toString()
                    .replace(".class", "")
                    .replace('/', '.');
            try {
                Class<?> candidate = Class.forName(
                        binaryName, false, ArchiveHttpWriteSurfaceTest.class.getClassLoader());
                if (candidate.isAnnotationPresent(RestController.class)) {
                    found.add(candidate);
                }
            } catch (Throwable t) {
                unloadable.add(binaryName + ": " + t.getClass().getSimpleName());
            }
        }

        assertThat(unloadable)
                .as("classes that would not load - a controller hidden behind one of these "
                        + "would not be inspected, so this cannot be ignored")
                .isEmpty();
        return found;
    }

    /** The directory that becomes {@code BOOT-INF/classes}, located from a known class. */
    private static Path compiledMainClasses() {
        try {
            Path fromCodeSource = Path.of(DashboardController.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (Files.isDirectory(fromCodeSource)) {
                return fromCodeSource;
            }
        } catch (Exception ignored) {
            // fall through to the conventional locations
        }
        Path local = Path.of("target", "classes");
        return Files.isDirectory(local) ? local : Path.of("api", "target", "classes");
    }
}
