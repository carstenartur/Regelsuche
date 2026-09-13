package de.regelsuche.web;

import de.regelsuche.cli.CliRouter;
import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.json.JsonReader;
import de.regelsuche.ast.Expr;
import de.regelsuche.plugin.RegelsuchePlugin;
import de.regelsuche.plugin.PluginRuntimeConfig;
import de.regelsuche.plugin.RuleRegistry;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

public class SafeRuntimeSurfaceContractTest {
    @Test void realProductEndpointsDistinguishExecutorFailureUnsupportedNoMatchAndBudget(@TempDir Path directory) throws Exception {
        Path services = Files.createDirectories(directory.resolve("META-INF/services"));
        Files.writeString(services.resolve(RegelsuchePlugin.class.getName()), ThrowingRuntimePlugin.class.getName() + "\n");
        Path plugins = Files.createDirectory(directory.resolve("plugins"));
        try (var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(plugins.resolve("failure-plugin.jar")))) {
            jar.putNextEntry(new java.util.jar.JarEntry("META-INF/services/" + RegelsuchePlugin.class.getName()));
            jar.write((ThrowingRuntimePlugin.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (var loader = new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()}, previous)) {
            Thread.currentThread().setContextClassLoader(loader);
            var server = new WebWorkbenchServer("127.0.0.1", 0, new InMemoryExpressionGraphStore(),
                new InMemoryRuleInventoryRepository(), new DefaultTransformationExportService(), WebSecurityConfig.none(),
                new PluginRuntimeConfig(plugins, directory.resolve("rules"), true, java.util.Set.of(), java.util.Set.of()));
            server.start();
            try {
                String request = """
                    {"schema":"regelsuche.safe-runtime-request/v1","profile":"%s","source":"z",
                     "ruleIds":["%s"],"includeSymPy":false}
                    """;
                var failed = assertParity(server, directory, request.formatted("SAFE_PREPARATION_V4", "runtime_technical_failure"));
                assertEquals("TECHNICAL_FAILURE", failed.get("status"));
                assertTrue(failed.toString().contains("DIRECT_EXECUTOR_FAILURE:java.lang.IllegalStateException"));
                assertEquals("NO_MATCH", assertParity(server, directory, request.formatted("DIRECT_V1", "ast_double_term")).get("status"));
                assertEquals("UNSUPPORTED", assertParity(server, directory, request.formatted("SAFE_PREPARATION_V4", "ast_double_term")).get("status"));
                assertEquals("BUDGET_INCONCLUSIVE", assertParity(server, directory, request.formatted("SAFE_PREPARATION_V4", "ast_double_term")
                    .replace("\"includeSymPy\":false", "\"includeSymPy\":false,\"maxWorkUnits\":0")).get("status"));
            } finally { server.stop(); }
        } finally { Thread.currentThread().setContextClassLoader(previous); }
    }

    public static final class ThrowingRuntimePlugin implements RegelsuchePlugin {
        public String id() { return "runtime-failure-product-test"; }
        public String name() { return "Runtime failure product test"; }
        public String version() { return "1.0.0"; }
        public void registerRules(RuleRegistry registry) {
            registry.register(new RewriteRule() {
                public String id() { return "runtime_technical_failure"; }
                public RewriteKind kind() { return RewriteKind.NORMALIZE; }
                public boolean mayIncreaseComplexity() { return false; }
                public int estimatedCostDelta() { return 0; }
                public boolean isEquivalencePreservingByConstruction() { return true; }
                public boolean matches(Expr expression) { throw new IllegalStateException("executor failed"); }
                public Expr apply(Expr expression) { throw new AssertionError("matching failed"); }
            });
        }
    }

    @Test void successorPreparesTheGuardedNestedOccurrenceAndReplaysThroughBothProducts(@TempDir Path directory) throws Exception {
        var server = new WebWorkbenchServer("127.0.0.1", 0, new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(), new DefaultTransformationExportService());
        server.start();
        try {
            String request = """
                {"schema":"regelsuche.safe-runtime-request/v1","profile":"%s","source":"1+((a/b)+0)*(c/d)",
                 "assumptions":["b != 0","d != 0"],"ruleIds":["rational_multiply_fractions","ast_add_zero_right"],
                 "preparationRuleIds":["ast_add_zero_right"],"includeSymPy":false}
                """;
            var direct = assertParity(server, directory, request.formatted("DIRECT_V1"));
            var historical = assertParity(server, directory, request.formatted("SAFE_PREPARATION_V3"));
            assertFalse(hasGuardedCandidate(direct));
            assertFalse(hasGuardedCandidate(historical), "the historical V3 semantics stay frozen");
            var prepared = assertParity(server, directory, request.formatted("SAFE_PREPARATION_V4"));
            assertTrue(hasGuardedCandidate(prepared), prepared.toString());
            assertEquals(direct.get("inventory"), prepared.get("inventory"));
            assertTrue(prepared.toString().contains("retainedAssumptions=[b != 0, d != 0]"));
            assertTrue(prepared.toString().contains("primitiveRuleIds=[ast_add_zero_right, rational_multiply_fractions]"));
            String artifact = Files.readString(directory.resolve("surface-artifact.json"));
            assertTrue(artifact.contains("regelsuche.unified-safe-rule-preparation-coordinator/v4"));
            for (String tampered : List.of(artifact.replace("SAFE_PREPARATION_V4", "SAFE_PREPARATION_V3"),
                    artifact.replace("ast_add_zero_right", "invented-preparation"),
                    artifact.replace("\"maxWorkUnits\":200000", "\"maxWorkUnits\":1"))) {
                assertEquals(409, post(server, "{\"runtimeArtifact\":" + tampered + "}").statusCode());
            }
            var rejected = assertParity(server, directory, request.formatted("SAFE_PREPARATION_V4")
                .replace("[\"b != 0\",\"d != 0\"]", "[\"b != 0\"]"));
            assertFalse(hasGuardedCandidate(rejected));
        } finally { server.stop(); }
    }

    @SuppressWarnings("unchecked")
    private static boolean hasGuardedCandidate(Map<String, Object> result) {
        return ((List<Map<String, Object>>) result.get("outcomes")).stream()
            .anyMatch(outcome -> outcome.get("id").equals("rational_multiply_fractions") && outcome.containsKey("candidate"));
    }

    @Test void realProfilesShareNativePreparationAndNestedGuardBehavior(@TempDir Path directory) throws Exception {
        var server = new WebWorkbenchServer("127.0.0.1", 0, new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(), new DefaultTransformationExportService());
        server.start();
        try {
            for (String profile : List.of("DIRECT_V1", "SAFE_PREPARATION_V3", "SAFE_PREPARATION_V4")) {
                String square = """
                    {"schema":"regelsuche.safe-runtime-request/v1","profile":"%s","source":"4*x^2-y^2",
                     "ruleIds":["ast_square_difference_factor"],"includeSymPy":false}
                    """.formatted(profile);
                var squareResult = assertParity(server, directory, square);
                assertEquals(profile.equals("DIRECT_V1") ? "NO_MATCH" : "SUCCESS", squareResult.get("status"));
                String guarded = """
                    {"schema":"regelsuche.safe-runtime-request/v1","profile":"%s","source":"1+(a/b)*(c/d)",
                     "assumptions":["b != 0","d != 0"],"ruleIds":["rational_multiply_fractions"],"includeSymPy":false}
                    """.formatted(profile);
                var accepted = assertParity(server, directory, guarded);
                assertEquals("SUCCESS", accepted.get("status"));
                assertTrue(accepted.toString().contains("occurrencePath=$R"));
                assertTrue(accepted.toString().contains("retainedAssumptions=[b != 0, d != 0]"));
                var rejected = assertParity(server, directory, guarded.replace(
                    "[\"b != 0\",\"d != 0\"]", "[\"b != 0\"]"));
                assertEquals("UNSUPPORTED", rejected.get("status"));
            }
        } finally { server.stop(); }
    }

    @Test void opaqueDirectExecutorDoesNotEraseItsEmittedAssumption(@TempDir Path directory) throws Exception {
        var server = new WebWorkbenchServer("127.0.0.1", 0, new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(), new DefaultTransformationExportService());
        server.start();
        try {
            var result = assertParity(server, directory, """
                {"schema":"regelsuche.safe-runtime-request/v1","profile":"DIRECT_V1","source":"1+(x*y)/x",
                 "ruleIds":["ast_cancel_division_factor"],"includeSymPy":false}
                """);
            assertTrue(result.toString().contains("retainedAssumptions=[x != 0]"), result.toString());
        } finally { server.stop(); }
    }

    @Test void typedSystemAndMutatedImportsReachTheSameRuntimeBoundary(@TempDir Path directory) throws Exception {
        var server = new WebWorkbenchServer("127.0.0.1", 0, new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(), new DefaultTransformationExportService());
        server.start();
        try {
            var result = assertParity(server, directory, """
                {"schema":"regelsuche.safe-runtime-request/v1","profile":"SAFE_PREPARATION_V4","includeSymPy":false,
                 "representation":{"schema":"regelsuche.matrix-preparation-request/v1",
                 "equations":"x+(2*x+y)=4; y+(x+3*y)=5","unknowns":["x","y"]}}
                """);
            assertTrue(result.toString().contains("SOLUTION_SET_EQUIVALENCE"));
            String artifact = Files.readString(directory.resolve("surface-artifact.json"));
            for (String tampered : List.of(artifact.replace("SOLUTION_SET_EQUIVALENCE", "EXACT_EXPRESSION_EQUALITY"),
                    artifact.replace("SAFE_PREPARATION_V4", "DIRECT_V1"),
                    artifact.replace("\"maxWorkUnits\":200000", "\"maxWorkUnits\":199999"))) {
                assertEquals(409, post(server, "{\"runtimeArtifact\":" + tampered + "}").statusCode());
                var output = new ByteArrayOutputStream();
                Files.writeString(directory.resolve("tampered.json"), tampered);
                assertEquals(1, router(output).run(new String[]{"transform", "--runtime-replay", directory.resolve("tampered.json").toString()}));
            }
            assertEquals(400, post(server, "{\"runtimeRequest\":{},\"goal\":\"FACTORIZE\"}").statusCode());
        } finally { server.stop(); }
    }

    @Test void cliAndWorkbenchShareTheOptInArtifactAndConcreteReplay(@TempDir Path directory) throws Exception {
        String request = """
            {"schema":"regelsuche.safe-runtime-request/v1","profile":"DIRECT_V1",
             "source":"x+0","assumptions":[],"ruleIds":["ast_add_zero_right"],"includeSymPy":false}
            """;
        var server = new WebWorkbenchServer("127.0.0.1", 0, new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(), new DefaultTransformationExportService());
        server.start();
        try {
            var response = post(server, "{\"runtimeRequest\":" + request + "}");
            assertEquals(200, response.statusCode(), response.body());
            var artifact = new JsonReader(response.body()).readObject();
            assertEquals("regelsuche.safe-runtime-artifact/v1", artifact.get("schema"));
            assertTrue(response.body().contains("DIRECT_MATCH_AVAILABLE"), response.body());
            Path input = directory.resolve("request.json");
            Files.writeString(input, request);
            var output = new ByteArrayOutputStream();
            var cli = new CliRouter(new PrintStream(output, true, StandardCharsets.UTF_8),
                new InMemoryExpressionGraphStore(), new InMemoryRuleInventoryRepository(),
                new DefaultTransformationExportService(), false);
            assertEquals(0, cli.run(new String[]{"transform", "--runtime-request", input.toString()}));
            assertEquals(response.body(), output.toString(StandardCharsets.UTF_8));
            Path retained = directory.resolve("artifact.json");
            Files.writeString(retained, response.body());
            output.reset();
            assertEquals(0, cli.run(new String[]{"transform", "--runtime-replay", retained.toString()}));
            assertEquals(response.body(), output.toString(StandardCharsets.UTF_8));
            var replay = post(server, "{\"runtimeArtifact\":" + response.body() + "}");
            assertEquals(200, replay.statusCode(), replay.body());
            assertEquals(response.body(), replay.body());
        } finally {
            server.stop();
        }
    }

    private static HttpResponse<String> post(WebWorkbenchServer server, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + server.boundPort() + "/api/search"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> assertParity(WebWorkbenchServer server, Path directory, String request) throws Exception {
        var response = post(server, "{\"runtimeRequest\":" + request + "}");
        assertEquals(200, response.statusCode(), response.body());
        Files.writeString(directory.resolve("surface-request.json"), request);
        var output = new ByteArrayOutputStream();
        assertEquals(0, router(output).run(new String[]{"transform", "--runtime-request", directory.resolve("surface-request.json").toString()}));
        assertEquals(response.body(), output.toString(StandardCharsets.UTF_8));
        Files.writeString(directory.resolve("surface-artifact.json"), response.body());
        output.reset();
        assertEquals(0, router(output).run(new String[]{"transform", "--runtime-replay", directory.resolve("surface-artifact.json").toString()}));
        assertEquals(response.body(), output.toString(StandardCharsets.UTF_8));
        var replay = post(server, "{\"runtimeArtifact\":" + response.body() + "}");
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals(response.body(), replay.body());
        return (Map<String, Object>) new JsonReader(response.body()).readObject().get("evidence");
    }

    private static CliRouter router(ByteArrayOutputStream output) {
        return new CliRouter(new PrintStream(output, true, StandardCharsets.UTF_8), new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(), new DefaultTransformationExportService(), false);
    }
}
