package de.regelsuche.runtime;

import de.regelsuche.json.JsonReader;
import de.regelsuche.plugin.PluginRuntime;
import de.regelsuche.plugin.PluginRuntimeConfig;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static de.regelsuche.runtime.SafeRuntimeAdapterTest.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeVisitorCodeIdentityTest {
    private static final String PROVIDER = "external.runtimeidentity.VisitorOnlyPlugin";
    private static final String VISITOR_CLASS =
        "external/runtimeidentity/VisitorOnlyPlugin$Contribution.class";

    @Test void activeVisitorCodeChangesInvalidateOtherwiseIdenticalReplay(@TempDir Path root) throws Exception {
        var first = compileVisitor(root.resolve("first"), "A", false);
        var second = compileVisitor(root.resolve("second"), "B", false);
        assertFalse(Arrays.equals(Files.readAllBytes(first.classes().resolve(VISITOR_CLASS)),
            Files.readAllBytes(second.classes().resolve(VISITOR_CLASS))), "the actual visitor bytecode must differ");
        assertEquals(RuntimeCodeIdentity.fingerprint(List.of(first.classes())),
            RuntimeCodeIdentity.fingerprint(List.of(first.jar())), "delivering the same visitor classes in a JAR preserves v1");
        verifyVisitorExecution(first, true);
        verifyVisitorExecution(second, true);

        try (var a = SafeRuntimeAdapter.open(first.config()); var b = SafeRuntimeAdapter.open(second.config())) {
            var request = request("DIRECT_V1", "x+0", List.of());
            request.put("ruleIds", List.of("ast_add_zero_right"));
            String original = a.analyze(request);
            String changed = b.analyze(request);
            assertEquals(candidates(evidence(original)), candidates(evidence(changed)),
                "this change must reach the implementation identity boundary with the same mathematical result");
            assertEquals(original, a.replay(new JsonReader(original).readObject()));
            assertAll("active visitor code is part of the delivered runtime authority",
                () -> assertNotEquals(evidence(original).get("implementation"), evidence(changed).get("implementation")),
                () -> assertThrows(IllegalArgumentException.class,
                    () -> b.replay(new JsonReader(original).readObject())));
        }
    }

    @Test void disabledVisitorCodeDoesNotExpandTheExecutingRuntimeIdentity(@TempDir Path root) throws Exception {
        var first = compileVisitor(root.resolve("first"), "A", true);
        var second = compileVisitor(root.resolve("second"), "B", true);
        verifyVisitorExecution(first, false);
        verifyVisitorExecution(second, false);
        try (var a = SafeRuntimeAdapter.open(first.config()); var b = SafeRuntimeAdapter.open(second.config())) {
            var request = request("DIRECT_V1", "x+0", List.of());
            request.put("ruleIds", List.of("ast_add_zero_right"));
            String original = a.analyze(request);
            assertEquals(original, b.analyze(request));
            assertEquals(original, b.replay(new JsonReader(original).readObject()));
        }
    }

    private static void verifyVisitorExecution(PluginArtifact artifact, boolean enabled) throws Exception {
        try (var runtime = new PluginRuntime(artifact.config())) {
            assertTrue(runtime.loadedPlugins().stream().anyMatch(plugin -> plugin.id().equals("runtime-visitor-identity")));
            var registration = runtime.astVisitorRegistry().registrations().stream()
                .filter(visitor -> visitor.id().equals("runtime-identity-contribution")).findFirst().orElseThrow();
            assertEquals(enabled, registration.enabled());
            assertEquals(artifact.jar(), RuntimeCodeIdentity.source(registration.visitor().getClass()));
            assertTrue(runtime.createTransformationEngine().transformForRuntime("x+0").stream()
                .anyMatch(step -> step.transformedExpression().equals("x")));
            int visits = registration.visitor().getClass().getField("visits").getInt(registration.visitor());
            assertEquals(enabled, visits > 0, "the real executor must respect visitor enablement");
        }
    }

    private static PluginArtifact compileVisitor(Path root, String variant, boolean disabled) throws Exception {
        Path classes = Files.createDirectories(root.resolve("classes"));
        Path source = root.resolve("VisitorOnlyPlugin.java");
        Files.writeString(source, """
            package external.runtimeidentity;
            import de.regelsuche.ast.Expr;
            import de.regelsuche.plugin.*;
            public final class VisitorOnlyPlugin implements RegelsuchePlugin {
                public String id() { return "runtime-visitor-identity"; }
                public String name() { return "Runtime visitor identity"; }
                public String version() { return "1.0.0"; }
                public void registerVisitors(AstVisitorRegistry registry) {
                    registry.register(new Contribution());
                    if (%s) registry.disable("runtime-identity-contribution");
                }
                public static final class Contribution implements AstVisitorPlugin {
                    public int visits;
                    public String id() { return "runtime-identity-contribution"; }
                    public AstVisitorPhase phase() { return AstVisitorPhase.DURING_SEARCH; }
                    public void visit(Expr node, AstVisitorContext context) {
                        visits++;
                        context.putMetadata(node, "runtime-identity-variant", "%s");
                    }
                }
            }
            """.formatted(disabled, variant), StandardCharsets.UTF_8);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "tests require the supported full JDK");
        var diagnostics = new ByteArrayOutputStream();
        int compiled = compiler.run(null, diagnostics, diagnostics,
            "--release", "25", "-encoding", "UTF-8", "-classpath", System.getProperty("java.class.path"),
            "-d", classes.toString(), source.toString());
        assertEquals(0, compiled, diagnostics.toString(StandardCharsets.UTF_8));

        Path plugins = Files.createDirectories(root.resolve("plugins"));
        Path jar = plugins.resolve("visitor-only.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                var entry = new JarEntry(classes.relativize(file).toString().replace('\\', '/'));
                entry.setTime(0L);
                output.putNextEntry(entry);
                output.write(Files.readAllBytes(file));
                output.closeEntry();
            }
            var service = new JarEntry("META-INF/services/de.regelsuche.plugin.RegelsuchePlugin");
            service.setTime(0L);
            output.putNextEntry(service);
            output.write((PROVIDER + "\n").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        var config = new PluginRuntimeConfig(plugins, root.resolve("rules"), false, Set.of(), Set.of());
        return new PluginArtifact(config, classes, jar);
    }

    private record PluginArtifact(PluginRuntimeConfig config, Path classes, Path jar) { }
}
