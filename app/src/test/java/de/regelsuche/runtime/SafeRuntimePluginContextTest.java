package de.regelsuche.runtime;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.json.JsonReader;
import de.regelsuche.plugin.*;
import de.regelsuche.transform.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static de.regelsuche.runtime.SafeRuntimeAdapterTest.*;
import static org.junit.jupiter.api.Assertions.*;

public class SafeRuntimePluginContextTest {
    private static final String RULE_ID = "runtime_context_add_zero";

    @Test void explicitSchemaPluginKeepsItsRealVisitorContextAndVisibleCoverage(@TempDir Path directory) throws Exception {
        var config = pluginConfig(directory);
        try (var original = new PluginRuntime(config)) {
            assertTrue(original.loadedPlugins().stream().anyMatch(plugin -> plugin.id().equals("runtime-context-test")));
            assertEquals(List.of("x"), original.createTransformationEngine().transformForRuntime("x+0").stream()
                .filter(step -> step.rule().equals(RULE_ID)).map(Transformation::transformedExpression).toList());
        }
        try (var adapter = SafeRuntimeAdapter.open(config)) {
            Map<String, Object> direct = null;
            for (String profile : List.of("DIRECT_V1", "SAFE_PREPARATION_V3", "SAFE_PREPARATION_V4")) {
                var request = request(profile, "x+0", List.of());
                request.put("ruleIds", List.of(RULE_ID));
                String artifact = adapter.analyze(request);
                var result = evidence(artifact);
                assertEquals("SUCCESS", result.get("status"), artifact);
                assertEquals(List.of("x"), candidates(result).stream().map(step -> step.get("expression")).toList());
                assertEquals(artifact, adapter.replay(new JsonReader(artifact).readObject()));
                var visible = ((List<?>) object(result.get("inventory")).get("visible")).stream()
                    .map(SafeRuntimeAdapterTest::object).filter(entry -> entry.get("id").equals(RULE_ID)).findFirst().orElseThrow();
                assertEquals("EXPLICIT_CUSTOM_SCHEMA", visible.get("coverage"));
                assertFalse(((String) visible.get("schemaHash")).isBlank());
                assertEquals(false, visible.get("runtimePreparationEligible"));
                assertEquals("PLUGIN_CONTEXT_REQUIRES_ORIGINAL_EXECUTOR", visible.get("runtimeExclusionReason"));
                if (direct == null) direct = result;
                else {
                    assertEquals(direct.get("inventory"), result.get("inventory"));
                    assertEquals(candidates(direct), candidates(result));
                }
                request.put("preparationRuleIds", List.of(RULE_ID));
                assertThrows(IllegalArgumentException.class, () -> adapter.analyze(request));
            }
        }
    }

    private static PluginRuntimeConfig pluginConfig(Path directory) throws Exception {
        Path plugins = Files.createDirectory(directory.resolve("plugins"));
        // An actual external service descriptor activates the provider through PluginRuntime's
        // ServiceLoader. Its compiled test class is already in the parent loader.
        try (var jar = new JarOutputStream(Files.newOutputStream(plugins.resolve("context-plugin.jar")))) {
            jar.putNextEntry(new JarEntry("META-INF/services/" + RegelsuchePlugin.class.getName()));
            jar.write((ContextPlugin.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return new PluginRuntimeConfig(plugins, directory.resolve("rules"), false, Set.of(), Set.of());
    }

    public static final class ContextPlugin implements RegelsuchePlugin {
        public String id() { return "runtime-context-test"; }
        public String name() { return "Runtime context test"; }
        public String version() { return "1.0.0"; }
        public void registerTransformations(TransformationRegistry registry) { registry.register(new ContextRule()); }
        public void registerVisitors(AstVisitorRegistry registry) {
            registry.register(new AstVisitorPlugin() {
                public String id() { return "runtime-context-marker"; }
                public AstVisitorPhase phase() { return AstVisitorPhase.DURING_SEARCH; }
                public void visit(Expr root, AstVisitorContext context) { context.putMetadata(root, "enabled", true); }
            });
        }
    }

    private static final class ContextRule implements PatternTransformation {
        public String id() { return RULE_ID; }
        public boolean matches(Expr node, TransformationMatchContext context) {
            return Boolean.TRUE.equals(context.metadata().get("enabled")) && rule("ast_add_zero_right").matches(node);
        }
        public Expr transform(Expr node, TransformationContext context) { return rule("ast_add_zero_right").apply(node); }
        public Optional<RewriteApplicabilitySchema> explicitApplicabilitySchema() {
            return Optional.of(new RewriteApplicabilitySchema("runtime-context-test/v1", this,
                PatternExpr.op(BinaryOperator.ADD, PatternExpr.var("x"), PatternExpr.num(0)), RecognitionProfile.exact()));
        }
    }
}
