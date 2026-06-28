package de.regelsuche.plugin.example;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.plugin.PluginDependency;
import de.regelsuche.plugin.RegelsuchePlugin;
import de.regelsuche.plugin.RuleRegistry;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteKind;
import java.util.List;
import java.util.Set;

public final class RationalFunctionsPlugin implements RegelsuchePlugin {
    private static final PatternExpr A = PatternExpr.var("A");
    private static final PatternExpr B = PatternExpr.var("B");

    @Override
    public String id() {
        return "rational-functions-pack";
    }

    @Override
    public String name() {
        return "Rational Functions Pack";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public String minimumCoreVersion() {
        return "1.0.0";
    }

    @Override
    public Set<String> capabilities() {
        return Set.of("rules");
    }

    @Override
    public List<PluginDependency> dependencies() {
        return List.of(
            new PluginDependency("algebra-core", ">=1.0.0", false),
            new PluginDependency("factorization-pack", ">=1.0.0", true)
        );
    }

    @Override
    public String provenance() {
        return "https://github.com/carstenartur/Regelsuche/tree/main/app/src/main/java/de/regelsuche/plugin/example";
    }

    @Override
    public String signature() {
        return "demo-classpath-signature";
    }

    @Override
    public void registerRules(RuleRegistry registry) {
        registry.register(new PatternRewriteRule(
            "rational_fraction_addition",
            PatternExpr.op(BinaryOperator.ADD,
                PatternExpr.op(BinaryOperator.DIV, A, B),
                PatternExpr.op(BinaryOperator.DIV, PatternExpr.var("C"), B)),
            PatternExpr.op(BinaryOperator.DIV,
                PatternExpr.op(BinaryOperator.ADD, A, PatternExpr.var("C")),
                B),
            RewriteKind.NORMALIZE,
            false,
            -3,
            true
        ), id(), "Bringt Brüche mit gleichem Nenner zusammen.", List.of("rational-functions", "normalize"));
    }
}
