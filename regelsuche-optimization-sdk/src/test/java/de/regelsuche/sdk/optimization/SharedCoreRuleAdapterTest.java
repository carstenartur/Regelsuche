package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.knowledge.CoreRuleCatalog;
import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.search.program.JointComputationPlan;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.SignedAdditiveRewriteRule;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The existing representation adapter must consume the ordinary registered rule. */
class SharedCoreRuleAdapterTest {
    @Test void sharedRuleIsLiftedForEverySupportedIntegralDomain() {
        for (NumericKind kind : List.of(NumericKind.INT, NumericKind.LONG, NumericKind.BIG_INTEGER)) {
            var request = request(kind);
            var work = new VerificationWork(request, CancellationToken.NONE);
            var generated = new JavaAlgebraCandidates(work, 1_000_000).generate(request.plan(), 32);
            Expr expected = op(kind, NumericOperation.ABS,
                    op(kind, NumericOperation.SUBTRACT, new VariableExpr("b"), new VariableExpr("c")));
            assertTrue(generated.proposals().stream().anyMatch(proposal ->
                    proposal.rule().equals("core-algebra/" + SignedAdditiveRewriteRule.ID)
                    && request.plan().withExpression(proposal.expression()).outputExpressions().equals(List.of(expected))),
                    kind + ": " + generated);
            var target = request.plan().withOutputs(List.of(expected));
            var verification = new ComputationOptimizer().verify(request, target, CancellationToken.NONE);
            assertInstanceOf(VerificationResult.Verified.class, verification, kind + ": " + verification);
        }
    }

    @Test void sharedPackSelectionRemovesTheRuleFromBothInventories() {
        var selected = KnowledgePackSelection.CORE.disablePack(CoreRuleCatalog.TERM_COLLECTION);
        List<RewriteRule> rules = AstRewriteTransformationEngine.defaultRules(selected);
        assertTrue(rules.stream().noneMatch(rule -> rule.id().equals(SignedAdditiveRewriteRule.ID)));
        var request = request(NumericKind.INT);
        var work = new VerificationWork(request, CancellationToken.NONE);
        var generated = new JavaAlgebraCandidates(work, rules, 1_000_000).generate(request.plan(), 32);
        assertTrue(generated.proposals().stream().noneMatch(proposal ->
                proposal.rule().equals("core-algebra/" + SignedAdditiveRewriteRule.ID)));
        assertTrue(new JavaAlgebraCandidates(new VerificationWork(request, CancellationToken.NONE), List.of(), 1_000_000)
                .generate(request.plan(), 32).proposals().isEmpty());
    }

    @Test void sharedRealAlgebraIsNotSilentlyAppliedToFloatingPoint() {
        for (NumericKind kind : List.of(NumericKind.FLOAT, NumericKind.DOUBLE)) {
            var request = request(kind);
            var generated = new JavaAlgebraCandidates(new VerificationWork(request, CancellationToken.NONE), 1_000_000)
                    .generate(request.plan(), 32);
            assertTrue(generated.proposals().isEmpty(), kind + ": " + generated);
        }
    }

    @Test void bigIntegerWithoutEstablishedRangeAndValueContractsStaysUnsupported() {
        var request = request(NumericKind.BIG_INTEGER, Set.of());
        var unsupported = assertInstanceOf(VerificationResult.Unsupported.class,
                new ComputationOptimizer().verify(request, request.plan(), CancellationToken.NONE));
        assertEquals("BIG_INTEGER_MAGNITUDE_BOUND_REQUIRED", unsupported.diagnostic());
    }

    private static OptimizationRequest request(NumericKind kind) {
        var assumptions = new LinkedHashSet<SemanticAssumption>();
        if (kind == NumericKind.BIG_INTEGER) {
            for (String name : List.of("a", "b", "c")) {
                assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS,
                        name, "", "fixture supplies immutable BigInteger values"));
                assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND,
                        name, "256", "fixture input magnitude bound"));
            }
        }
        return request(kind, assumptions);
    }

    private static OptimizationRequest request(NumericKind kind, Set<SemanticAssumption> assumptions) {
        Expr a = new VariableExpr("a"), b = new VariableExpr("b"), c = new VariableExpr("c");
        Expr p = op(kind, NumericOperation.SUBTRACT, op(kind, NumericOperation.ADD, a, b), c);
        Expr expression = op(kind, NumericOperation.ABS, op(kind, NumericOperation.SUBTRACT, p, a));
        var plan = new JointComputationPlan(Map.of("a", kind.type(), "b", kind.type(), "c", kind.type()),
                Map.of(), List.of(new JointComputationPlan.Output("out", kind.type(), expression)));
        return new OptimizationRequest(plan, SourceEvaluationTrace.fromPlan(plan), Set.of(kind),
                ComputationOptimizer.SEMANTICS_REVISION, assumptions, SafetyProfile.PRESERVE_JAVA,
                OptimizationGoal.LOWER_ESTIMATED_RUNTIME, new OptimizationBudget(1_000_000, 20_000, 32, 60_000),
                CheckedPolicy.NONE);
    }
    private static Expr op(NumericKind kind, NumericOperation operation, Expr... arguments) {
        return JavaExpressions.operation(kind, operation, arguments);
    }
}
