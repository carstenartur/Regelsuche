package de.regelsuche.plugin.example;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.plugin.PatternBasedTransformation;
import de.regelsuche.plugin.PluginDependency;
import de.regelsuche.plugin.RegelsuchePlugin;
import de.regelsuche.plugin.TransformationRegistry;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.RewriteKind;
import java.util.List;
import java.util.Set;

public final class FactorizationPlugin implements RegelsuchePlugin {
    private static final PatternExpr A = PatternExpr.var("A");
    private static final PatternExpr B = PatternExpr.var("B");
    private static final PatternExpr C = PatternExpr.var("C");

    @Override
    public String id() {
        return "factorization-pack";
    }

    @Override
    public String name() {
        return "Factorization Pack";
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
        return Set.of("transformations");
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
    public void registerTransformations(TransformationRegistry registry) {
        registry.register(new PatternBasedTransformation(
            "factorization_common_factor",
            PatternExpr.op(BinaryOperator.ADD,
                PatternExpr.op(BinaryOperator.MUL, A, B),
                PatternExpr.op(BinaryOperator.MUL, A, C)),
            PatternExpr.op(BinaryOperator.MUL, A, PatternExpr.op(BinaryOperator.ADD, B, C)),
            RewriteKind.FACTOR,
            false,
            -4,
            true,
            "Klammert gemeinsamen Faktor aus."
        ), id(), "Faktorisierung gemeinsamer Faktoren.", List.of("factorization", "algebra"));
    }
}
