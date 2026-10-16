package de.regelsuche.search.program;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.*;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.AstRewriteTransport;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Source-only joint search through ordinary core rules; no optimization SDK dependency. */
@Timeout(30)
class SharedAdditiveCoreSearchTest {
    private static final ComputationBackend.Type INTEGER = new ComputationBackend.Type("integer", BigInteger.class);

    @Test void ordinarySearchCombinesCostNeutralStepsIntoSharedOutputs() {
        var parser = new ExpressionParser();
        var source = new JointComputationPlan(Map.of("a", INTEGER, "b", INTEGER, "c", INTEGER), Map.of(), List.of(
                new JointComputationPlan.Output("first", INTEGER, parser.parseTerm("((a+b)-c)-a")),
                new JointComputationPlan.Output("second", INTEGER, parser.parseTerm("((a+b)-c)-b")),
                new JointComputationPlan.Output("third", INTEGER, parser.parseTerm("((a+b)-c)-c"))));
        var backend = new IntegerArithmetic();
        var domain = new CoreDomain();
        var search = new JointPlanSearch(backend, domain, new JointPlanSearch.Weights(10, 1, 1, 1), 32);
        var result = search.optimize(source, new MoveSearch.Budget(8, 8, 5_000_000, 5_000, 5_000_000, 64));
        assertEquals(5, source.prepare(backend).cost().operationCount());
        assertTrue(result.prepared().cost().operationCount() <= 3, result.toString());
        assertTrue(result.withinBudget(), result.toString());
        assertTrue(domain.signedSteps > 0, "The ordinary registered rule must actually be used");
        assertTrue(domain.verify(source, result.plan()).accepted());
        var input = Map.of("a", BigInteger.ONE.shiftLeft(100), "b", BigInteger.valueOf(-7), "c", BigInteger.valueOf(11));
        assertEquals(source.prepare(backend).execute(input), result.prepared().execute(input));
    }

    private static final class CoreDomain implements JointPlanSearch.Domain {
        private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
        private int signedSteps;
        @Override public String revision() { return "shared-additive-core-search-test/v1"; }
        @Override public JointPlanSearch.Generation generate(JointComputationPlan plan, int maximum) {
            var work = new AtomicLong();
            List<JointPlanSearch.Proposal> proposals;
            boolean complete;
            try (var observation = RetainedOperation.open(new RetainedOperation.Sink() {
                @Override public void executionWork(long units) { work.addAndGet(units); }
                @Override public void validationWork(long units) { work.addAndGet(units); }
                @Override public void checkpoint() { }
                @Override public long observedWork() { return work.get(); }
                @Override public void retainedReferences(RetainedGraph.Visitor visitor) { }
            })) {
                var engine = new AstRewriteTransport(AstRewriteTransformationEngine.defaultRules(), 12, maximum + 1);
                var steps = engine.generate(plan.expression());
                proposals = steps.stream().limit(maximum).map(step -> {
                    if (step.rule().equals("ast_signed_additive_normalize")) signedSteps++;
                    return new JointPlanSearch.Proposal(step.rule(), step.target());
                }).toList();
                complete = steps.size() <= maximum;
            }
            return new JointPlanSearch.Generation(proposals, Math.max(1, work.get()), complete);
        }
        @Override public JointPlanSearch.Verification verify(JointComputationPlan source, JointComputationPlan target) {
            var left = source.outputExpressions();
            var right = target.outputExpressions();
            if (left.size() != right.size()) return new JointPlanSearch.Verification(false, 1);
            long work = 1;
            for (int i = 0; i < left.size(); i++) {
                String a = ExpressionFormatter.format(left.get(i)), b = ExpressionFormatter.format(right.get(i));
                work += a.length() + b.length();
                if (!canonicalizer.canonicalize(a).equals(canonicalizer.canonicalize(b)))
                    return new JointPlanSearch.Verification(false, work);
            }
            return new JointPlanSearch.Verification(true, work);
        }
    }

    private static final class IntegerArithmetic implements ComputationBackend {
        @Override public Type literalType(NumberExpr literal) { return INTEGER; }
        @Override public Object literal(NumberExpr literal) { return literal.value().numerator(); }
        @Override public Operation operation(Expr expression) {
            var operator = ((BinaryExpr) expression).operator();
            return new Operation(operator.name(), List.of(INTEGER, INTEGER), INTEGER, 1, 1);
        }
        @Override public Object apply(Operation operation, List<Object> arguments) {
            var a = (BigInteger) arguments.getFirst();
            var b = (BigInteger) arguments.get(1);
            return switch (operation.id()) {
                case "ADD" -> a.add(b);
                case "SUB" -> a.subtract(b);
                case "MUL" -> a.multiply(b);
                case "POW" -> a.pow(b.intValueExact());
                default -> throw new IllegalArgumentException("Unsupported exact integer operation");
            };
        }
    }
}
