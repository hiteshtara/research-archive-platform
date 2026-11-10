package edu.bu.archive.application.authorization.viewaccessas.demo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import edu.bu.archive.application.authorization.RecordAccessEvaluator;
import edu.bu.archive.application.authorization.RecordAuthorizationService;
import edu.bu.archive.application.authorization.RecordScopeSqlBuilder;
import edu.bu.archive.application.authorization.viewaccessas.SyntheticProfile;

/**
 * Serves the View Access As demonstration on localhost.
 *
 * <p>TEST SCOPE ONLY, and structurally so. This is a JDK
 * {@link HttpServer} with a {@code main} method — not a Spring
 * {@code @RestController}, not a {@code @Component}, not a route on the
 * application. There is no bean for Spring to find and no mapping for
 * {@code RequestMappingHandlerMapping} to register, so the production
 * application cannot serve this even by accident. That is deliberate: the
 * approved design for this harness refused an interactive record search
 * precisely because it would have forced simulator code into the production
 * image, and this is how the interactive experience is provided without doing
 * that.
 *
 * <p>Run it:
 *
 * <pre>{@code
 * cd api
 * mvn -o test-compile
 * mvn -o exec:java -Dexec.classpathScope=test \
 *     -Dexec.mainClass=edu.bu.archive.application.authorization.viewaccessas.demo.ViewAccessAsDemoServer
 * }</pre>
 *
 * <p>Then open the URL it prints. It binds the loopback address only, so
 * nothing outside this machine can reach it.
 *
 * <h2>What the browser can and cannot do</h2>
 *
 * Every response is computed from {@link DemoEvaluator}, which composes the
 * real engine. The page holds no rule and no expected answer: it renders what
 * the server returns. Nothing here reads an archive row, issues a grant,
 * writes to {@code authz}, mints a token, or makes an AWS call — there is no
 * datasource and no AWS client on this object's path at all.
 */
public final class ViewAccessAsDemoServer {

    /** Loopback only. A demonstration that bound 0.0.0.0 would be a service. */
    private static final String HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 8750;

    private static final String PAGE_RESOURCE = "/viewaccessas-demo/index.html";

    /**
     * The only origins allowed to read this harness, for the QA route served
     * by the Vite dev server.
     *
     * <p>An explicit loopback allowlist, never {@code *} and never the
     * request's origin echoed back unchecked. The harness answers authorization
     * questions about synthetic records, which is uninteresting to steal and
     * impossible to use - but a simulator that accepted any origin is the shape
     * of a thing that should not exist, and the list costs one line.
     */
    private static final java.util.Set<String> ALLOWED_ORIGINS = java.util.Set.of(
            "http://localhost:5173",
            "http://127.0.0.1:5173");

    private final DemoEvaluator evaluator = new DemoEvaluator();
    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;

    public ViewAccessAsDemoServer(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(HOST, port), 0);
        server.createContext("/api/model", this::handleModel);
        server.createContext("/api/evaluate", this::handleEvaluate);
        server.createContext("/", this::handlePage);
        server.setExecutor(null);
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        ViewAccessAsDemoServer demo = new ViewAccessAsDemoServer(port);
        demo.start();
        System.out.println();
        System.out.println("  View Access As — interactive QA demonstration");
        System.out.println("  " + demo.url());
        System.out.println();
        System.out.println("  Test scope only. No archive row is read, no grant is issued,");
        System.out.println("  no token is minted, enforcement in DEV is untouched.");
        System.out.println("  Ctrl-C to stop.");
        System.out.println();
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String url() {
        return "http://" + HOST + ":" + port() + "/";
    }

    // ── handlers ────────────────────────────────────────────────────────

    private void handleModel(HttpExchange exchange) throws IOException {
        Map<String, Object> model = new LinkedHashMap<>();

        List<Map<String, Object>> profiles = new ArrayList<>();
        for (SyntheticProfile profile : DemoModel.profiles()) {
            profiles.add(Map.of("name", profile.name(), "description", profile.description()));
        }
        model.put("profiles", profiles);

        List<Map<String, Object>> families = new ArrayList<>();
        for (DemoModel.Family family : DemoModel.families()) {
            families.add(Map.of(
                    "familyKey", family.familyKey(),
                    "label", family.label(),
                    "leadUnit", family.leadUnit(),
                    "versionKeys", family.versions().stream().map(v -> v.versionKey()).toList()));
        }
        model.put("families", families);
        model.put("versionScopes", List.of("PER_VERSION", "FAMILY_WIDE"));
        model.put("departmentMatches", List.of("EXACT_LEAD_UNIT", "LEAD_UNIT_WITH_DESCENDANTS"));
        model.put("engine", engineProvenance());
        respond(exchange, 200, "application/json", json.writeValueAsBytes(model));
    }

    private void handleEvaluate(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI());
        var settings = new DemoEvaluator.Settings(
                query.getOrDefault("versionScope", "PER_VERSION"),
                query.getOrDefault("departmentMatch", "EXACT_LEAD_UNIT"));

        List<String> requested = new ArrayList<>();
        if (query.containsKey("profileA")) {
            requested.add(query.get("profileA"));
        }
        if (query.containsKey("profileB") && !query.get("profileB").isBlank()) {
            requested.add(query.get("profileB"));
        }
        if (requested.isEmpty()) {
            requested.add(DemoModel.profiles().get(0).name());
        }

        try {
            List<DemoEvaluator.ProfileResult> results = new ArrayList<>();
            for (String name : requested) {
                SyntheticProfile profile = DemoModel.profile(name).orElseThrow(
                        () -> new IllegalArgumentException("no such profile: " + name));
                results.add(evaluator.evaluate(profile, settings));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("settings", settings);
            body.put("evaluatedAt", DemoEvaluator.EVALUATED_AT.toString());
            body.put("engine", engineProvenance());
            body.put("results", results);
            respond(exchange, 200, "application/json", json.writeValueAsBytes(body));
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, "application/json",
                    json.writeValueAsBytes(Map.of("error", String.valueOf(e.getMessage()))));
        }
    }

    private void handlePage(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!path.equals("/") && !path.equals("/index.html")) {
            respond(exchange, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream page = ViewAccessAsDemoServer.class.getResourceAsStream(PAGE_RESOURCE)) {
            Objects.requireNonNull(page, "missing test resource " + PAGE_RESOURCE);
            respond(exchange, 200, "text/html; charset=utf-8", page.readAllBytes());
        }
    }

    /**
     * Where the authorization classes actually came from.
     *
     * <p>This is the evidence that the verdicts are the production engine's:
     * the names are read from the loaded classes, and {@code loadedFrom} is
     * their code source, which is the compiled main classes of this
     * repository. A harness that had quietly reimplemented a rule could not
     * produce this.
     */
    private static Map<String, Object> engineProvenance() {
        Map<String, Object> engine = new LinkedHashMap<>();
        engine.put("evaluator", RecordAccessEvaluator.class.getName());
        engine.put("service", RecordAuthorizationService.class.getName());
        engine.put("sqlBuilder", RecordScopeSqlBuilder.class.getName());
        engine.put("loadedFrom", codeSourceOf(RecordAccessEvaluator.class));
        engine.put("serviceLoadedFrom", codeSourceOf(RecordAuthorizationService.class));
        return engine;
    }

    private static String codeSourceOf(Class<?> type) {
        try {
            return type.getProtectionDomain().getCodeSource().getLocation().toString();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    // ── plumbing ────────────────────────────────────────────────────────

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> parsed = new LinkedHashMap<>();
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return parsed;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                parsed.put(
                        java.net.URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }
        return parsed;
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && ALLOWED_ORIGINS.contains(origin)) {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", origin);
            exchange.getResponseHeaders().add("Vary", "Origin");
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
