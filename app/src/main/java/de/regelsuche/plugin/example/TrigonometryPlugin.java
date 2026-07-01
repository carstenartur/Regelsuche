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

public final class TrigonometryPlugin implements RegelsuchePlugin {
    private static final PatternExpr A = PatternExpr.var("A");

    @Override
    public String id() {
        return "trigonometry-pack";
    }

    @Override
    public String name() {
        return "Trigonometry Pack";
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
        return List.of(new PluginDependency("algebra-core", ">=1.0.0", false));
    }

    @Override
    public String provenance() {
        return "https://github.com/carstenartur/Regelsuche/tree/main/app/src/main/java/de/regelsuche/plugin/example";
    }

    @Override
    public void registerRules(RuleRegistry registry) {
        registry.register(new PatternRewriteRule(
            "trig_pythagorean_identity",
            PatternExpr.op(BinaryOperator.ADD,
                PatternExpr.op(BinaryOperator.POW, PatternExpr.fn("sin", A), PatternExpr.num(2)),
                PatternExpr.op(BinaryOperator.POW, PatternExpr.fn("cos", A), PatternExpr.num(2))),
            PatternExpr.num(1),
            RewriteKind.SIMPLIFY,
            false,
            -5,
            true
        ), id(), "Nutzen von sin²(x)+cos²(x)=1.", List.of("trigonometry", "identity"));
    }
}
