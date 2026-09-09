package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginApiContractTest {
    private static PatternRewriteRule rule() {
        var a = PatternExpr.var("a");
        return new PatternRewriteRule("add-zero", PatternExpr.op(BinaryOperator.ADD, a, PatternExpr.num(0)), a);
    }

    @Test void ruleAndMacroRegistrationsAreIndependentImmutableSnapshots() {
        var rules = new RuleRegistry();
        rules.register(rule());
        var snapshot = rules.registrations();
        assertTrue(snapshot.getFirst().enabled());
        assertEquals("plugin", snapshot.getFirst().source());
        assertThrows(IllegalArgumentException.class, () -> rules.register(rule()));
        rules.disable("missing");
        rules.disable("add-zero");
        assertTrue(snapshot.getFirst().enabled());
        assertFalse(rules.registrations().getFirst().enabled());
        assertTrue(rules.enabledRules().isEmpty());
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        var explicit = new RuleRegistry();
        explicit.register(rule(), "external", null, List.of("exact"));
        assertEquals("", explicit.registrations().getFirst().explanation());
        assertEquals(1, explicit.enabledRules().size());
        var macro = new RuleMacro("identity", "x+0", "x", "zero", List.of("exact"));
        var macros = new MacroRegistry();
        macros.register(macro);
        assertEquals("unspecified", macro.difficulty());
        assertEquals(1, macros.enabledMacros().size());
        assertThrows(IllegalArgumentException.class, () -> macros.register(macro));
        macros.disable("missing");
        macros.disable("identity");
        assertTrue(macros.enabledMacros().isEmpty());
        assertFalse(macros.registrations().getFirst().enabled());
    }

    @Test void typedTransformationUsesTheSameExactPatternAsTheRule() {
        var original = rule();
        var transformation = new PatternBasedTransformation("zero", original.source(), original.target(),
            RewriteKind.NORMALIZE, false, -1, true, "remove zero");
        var registry = new TransformationRegistry();
        registry.register(transformation);
        assertThrows(IllegalArgumentException.class, () -> registry.register(transformation));
        Expr input = new ExpressionParser().parseTerm("x+0");
        assertTrue(transformation.matches(input));
        assertEquals(new VariableExpr("x"), transformation.apply(input));
        assertEquals("remove zero", transformation.explain(input, transformation.apply(input)));
        assertFalse(transformation.matches(new ExpressionParser().parseTerm("x+1")));
        assertEquals(original.source(), transformation.source());
        assertEquals(original.target(), transformation.target());
        assertEquals(RewriteKind.NORMALIZE, transformation.kind());
        assertFalse(transformation.mayIncreaseComplexity());
        assertEquals(-1, transformation.estimatedCostDelta());
        assertTrue(transformation.isEquivalencePreservingByConstruction());
        assertEquals(1, registry.enabledTransformations().size());
        registry.disable("missing");
        registry.disable("zero");
        assertTrue(registry.enabledTransformations().isEmpty());
        assertFalse(registry.registrations().getFirst().enabled());
    }

    @Test void visitorMetadataTracksNodeIdentityAndProducesSnapshots() {
        Expr left = new VariableExpr("x");
        Expr right = new VariableExpr("x");
        var context = new AstVisitorContext();
        context.putMetadata(left, "visited", true);
        context.setLastRuleId("rule-1");
        context.report("visitor", "observed");
        context.mark("exact");
        assertEquals(Map.of("visited", true), context.metadata(left));
        assertTrue(context.metadata(right).isEmpty());
        assertEquals("rule-1", context.diagnostics().getFirst().ruleId());
        assertEquals(List.of("exact"), context.markers());
        assertEquals(context.metadata(left), TransformationContext.from(context, left).metadata());
        assertEquals(context.metadata(left), TransformationMatchContext.from(context, left).metadata());
        var registry = new AstVisitorRegistry();
        var phase = AstVisitorPhase.values()[0];
        AstVisitorPlugin visitor = new AstVisitorPlugin() {
            public String id() { return "count"; }
            public AstVisitorPhase phase() { return phase; }
            public void visit(Expr node, AstVisitorContext target) { target.mark("visited"); }
        };
        registry.register(visitor);
        assertThrows(IllegalArgumentException.class, () -> registry.register(visitor));
        assertSame(context, registry.execute(phase, left, context));
        assertTrue(context.markers().contains("visited"));
        registry.disable("missing");
        registry.disable("count");
        assertFalse(registry.registrations().getFirst().enabled());
    }

    @Test void everyExtensionRegistryHonorsTheCommonPublicContract() throws Exception {
        for (Class<?> type : List.of(CostFunctionRegistry.class, HeuristicRegistry.class,
                SearchStrategyRegistry.class, RendererRegistry.class, ParserExtensionRegistry.class,
                ExplanationRegistry.class, ExampleRegistry.class)) {
            @SuppressWarnings("unchecked")
            var registry = (PluginExtensionRegistry<PluginExtension>) type.getConstructor().newInstance();
            PluginExtension extension = () -> "external-extension";
            registry.register(extension);
            assertEquals("external-extension", extension.name());
            assertTrue(extension.tags().isEmpty());
            assertEquals(List.of(extension), registry.enabledExtensions());
            var snapshot = registry.registrations();
            assertThrows(IllegalArgumentException.class, () -> registry.register(extension));
            registry.disable("missing");
            registry.disable(extension.id());
            assertTrue(registry.enabledExtensions().isEmpty());
            assertFalse(registry.registrations().getFirst().enabled());
            assertTrue(snapshot.getFirst().enabled());
        }
        var examples = new ExamplePackage("examples", "Examples",
            List.of(new ExamplePackage.ExampleEntry("zero", "x+0", "x")), List.of("exact"));
        assertThrows(UnsupportedOperationException.class, () -> examples.examples().clear());
        assertEquals("Examples", examples.name());
        assertTrue(new SearchStrategy() { public String id() { return "empty"; } }.search(null).isEmpty());
        assertEquals("", new SearchStrategy() { public String id() { return "empty"; } }.description());
    }

    @Test void minimalPluginHasNoImplicitExtensionsOrDependencies() {
        RegelsuchePlugin plugin = new RegelsuchePlugin() {
            public String id() { return "minimal"; }
            public String name() { return "Minimal"; }
            public String version() { return "1.0.0"; }
        };
        assertEquals("1", plugin.apiVersion());
        assertEquals("0.0.0", plugin.minimumCoreVersion());
        assertEquals("", plugin.provenance());
        assertEquals("", plugin.signature());
        assertTrue(plugin.capabilities().isEmpty());
        assertTrue(plugin.dependencies().isEmpty());
        plugin.registerRules(new RuleRegistry());
        plugin.registerTransformations(new TransformationRegistry());
        plugin.registerVisitors(new AstVisitorRegistry());
        plugin.registerMacros(new MacroRegistry());
        plugin.registerSearchStrategies(new SearchStrategyRegistry());
        plugin.registerHeuristics(new HeuristicRegistry());
        plugin.registerCostFunctions(new CostFunctionRegistry());
        plugin.registerRenderers(new RendererRegistry());
        plugin.registerExplanations(new ExplanationRegistry());
        plugin.registerParserExtensions(new ParserExtensionRegistry());
        plugin.registerExamples(new ExampleRegistry());
        assertEquals("any", new PluginDependency(" EXTERNAL ", null, false).versionConstraint());
        assertEquals("external", new PluginDependency(" EXTERNAL ", "1", true).pluginId());
        assertThrows(IllegalArgumentException.class, () -> new PluginDependency("", "1", false));
    }

    @Test void ruleDslParsesWithoutApplicationRuntime(@TempDir Path temp) throws Exception {
        var path = Files.writeString(temp.resolve("example.regelsuche"), """
            # complete portable rule, macro and profile
            rule remove_zero:
              pattern: A + 0
              replace: A
              direction: forward
              tags:
                - exact
              conditions:
                - A: expression
              explanation: "Remove zero"
            macro one_step:
              input: A + 0
              output: A
              priority: 2
              difficulty: simple
            profile student:
              enable_tags:
                - exact
              whitelist:
                - remove_zero
            """);
        var parsed = new RuleFileParser().parse(path);
        assertEquals(3, parsed.entries().size());
        assertTrue(parsed.diagnostics().isEmpty(), parsed.diagnostics().toString());
        assertThrows(IllegalArgumentException.class, () -> new RuleFileParser().parse(temp.resolve("absent")));
    }

    @Test void invalidDslReportsDiagnosticsInsteadOfInventingValidRules(@TempDir Path temp) throws Exception {
        for (String input : List.of("invalid header", "rule incomplete:",
                "rule invalid:\n  pattern: x\n  replace: y\n  direction: sideways",
                "rule invalid:\n  pattern: x\n  replace: y\n  conditions:\n    - bad condition",
                "macro invalid:\n  input: x", "profile invalid:\n  whitelist:\n    - x\n  blacklist:\n    - x")) {
            var path = Files.writeString(temp.resolve("invalid.regelsuche"), input);
            assertFalse(new RuleFileParser().parse(path).diagnostics().isEmpty(), input);
        }
    }
}
