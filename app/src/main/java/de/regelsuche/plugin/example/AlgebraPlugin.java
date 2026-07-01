package de.regelsuche.plugin.example;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.plugin.PatternBasedTransformation;
import de.regelsuche.plugin.RegelsuchePlugin;
import de.regelsuche.plugin.RuleRegistry;
import de.regelsuche.plugin.TransformationRegistry;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteKind;
import java.util.List;
import java.util.Set;

public final class AlgebraPlugin implements RegelsuchePlugin {
    private static final PatternExpr A = PatternExpr.var("A");

    @Override
    public String id() {
        return "algebra-core";
    }

    @Override
    public String name() {
        return "Algebra Core";
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
        return Set.of("rules", "transformations");
    }

    @Override
    public String provenance() {
        return "https://github.com/carstenartur/Regelsuche/tree/main/app/src/main/java/de/regelsuche/plugin/example";
    }

    @Override
    public void registerRules(RuleRegistry registry) {
        registry.register(new PatternRewriteRule(
            "algebra_additive_identity",
            PatternExpr.op(BinaryOperator.ADD, A, PatternExpr.num(0)),
            A,
            RewriteKind.SIMPLIFY,
            false,
            -2,
            true
        ), id(), "Entfernt neutrale Addition.", List.of("algebra", "simplify"));
    }

    @Override
    public void registerTransformations(TransformationRegistry registry) {
        registry.register(new PatternBasedTransformation(
            "algebra_multiplicative_identity",
            PatternExpr.op(BinaryOperator.MUL, A, PatternExpr.num(1)),
            A,
            RewriteKind.SIMPLIFY,
            false,
            -2,
            true,
            "Entfernt neutrale Multiplikation."
        ), id(), "Entfernt Faktor 1.", List.of("algebra", "simplify"));
    }
}
