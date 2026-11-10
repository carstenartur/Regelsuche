package de.regelsuche.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.mining.RuleStatus;
import de.regelsuche.validation.CandidateProofStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class InventoryRuleRevalidationTest {
    @Test
    void storedFormalLabelCannotAuthorizeAFalseIdentity() {
        var provider = provider(rule("false", "A + 0", "A + 1", List.of(), List.of()));

        assertTrue(provider.activatedRules().isEmpty());
        assertTrue(provider.lastDecisions().getFirst().reason().contains("NOT_EQUIVALENT"));
    }

    @Test
    void assumptionsAndParameterRelationsCannotBeDropped() {
        var provider = provider(
            rule("assumed", "A / A", "1", List.of(), List.of("A != 0")),
            rule("related", "A + B", "2 * A", List.of("A = B"), List.of()));

        assertTrue(provider.activatedRules().isEmpty());
        assertEquals(2, provider.lastDecisions().size());
        assertTrue(provider.lastDecisions().stream().allMatch(d ->
            d.reason().contains("condition-aware")));
    }

    @Test
    void unsupportedAndMalformedRulesDoNotPreventLaterVerifiedRules() {
        var provider = provider(
            rule("unsupported", "sin(A)^2 + cos(A)^2", "1", List.of(), List.of()),
            rule("malformed", "A + (", "A", List.of(), List.of()),
            rule("valid", "A + 0", "A", List.of(), List.of()));

        var activated = provider.activatedRules();

        assertEquals(1, activated.size());
        assertEquals("inventory_valid", activated.getFirst().id());
        assertEquals(3, provider.lastDecisions().size());
        assertTrue(provider.lastDecisions().stream().anyMatch(d ->
            d.rule().id().equals("unsupported") && d.reason().contains("UNSUPPORTED")));
        assertTrue(provider.lastDecisions().stream().anyMatch(d ->
            d.rule().id().equals("malformed") && d.reason().contains("Invalid pattern")));
        assertTrue(activated.getFirst().isEquivalencePreservingByConstruction());
    }

    @Test
    void rejectedConditionalDuplicateDoesNotHideAnUnconditionalIdentity() {
        var provider = provider(
            rule("conditional", "A + 0", "A", List.of(), List.of("A != 0")),
            rule("unconditional", "A + 0", "A", List.of(), List.of()));

        assertEquals("inventory_unconditional", provider.activatedRules().getFirst().id());
    }

    @Test
    void unboundTargetPlaceholderIsRejectedWithoutBlockingTheFollowingValidRule() {
        var provider = provider(
            rule("a-unbound", "A + 0", "A + B - B", List.of(), List.of()),
            rule("b-valid", "A + 0", "A", List.of(), List.of()));

        var activated = provider.activatedRules();

        assertEquals(1, activated.size());
        assertEquals("inventory_b-valid", activated.getFirst().id());
        assertEquals(2, provider.lastDecisions().size());
        var rejected = provider.lastDecisions().getFirst();
        assertTrue(!rejected.activated());
        assertTrue(rejected.reason().contains("Unbound target placeholders"));
        assertTrue(rejected.reason().contains("B"));
        var parser = new de.regelsuche.parse.ExpressionParser();
        var before = parser.parseTerm("x + 0");
        assertTrue(activated.getFirst().matches(before));
        assertEquals(parser.parseTerm("x"), activated.getFirst().apply(before));
        assertTrue(!new de.regelsuche.transform.AstRewriteTransformationEngine(activated)
            .transform("x + 0").isEmpty());
    }

    @Test
    void placeholdersMayOccurOnlyOnTheLeftWhenTheIdentityEliminatesThem() {
        var provider = provider(rule("eliminate", "A + B - B", "A", List.of(), List.of()));
        var activated = provider.activatedRules();
        assertEquals(1, activated.size());
        var parser = new de.regelsuche.parse.ExpressionParser();
        var before = parser.parseTerm("x + y - y");
        assertTrue(activated.getFirst().matches(before));
        assertEquals(parser.parseTerm("x"), activated.getFirst().apply(before));
    }

    @Test
    void polynomialRingProofCannotAuthorizeDomainSensitiveZeroPowers() {
        var provider = provider(
            rule("a-zero", "A^0", "1", List.of(), List.of()),
            rule("b-zero-zero", "0^0", "1", List.of(), List.of()),
            rule("c-nested", "A^0 + B", "1 + B", List.of(), List.of()),
            rule("d-target", "A", "A + 0*(A^0)", List.of(), List.of()),
            rule("e-safe", "2^0", "1", List.of(), List.of()));

        var activated = provider.activatedRules();
        assertEquals(1, activated.size());
        assertEquals("inventory_e-safe", activated.getFirst().id());
        assertEquals(4, provider.lastDecisions().stream().filter(d -> !d.activated()).count());
        assertTrue(provider.lastDecisions().stream().filter(d -> !d.activated())
            .allMatch(d -> d.reason().contains("zero power")));
        var parser = new de.regelsuche.parse.ExpressionParser();
        assertEquals(parser.parseTerm("1"), activated.getFirst().apply(parser.parseTerm("2^0")));
    }

    @Test
    void eliminatedBindingsMustNotDiscardAPartialExpressionsDomain() {
        var activated = provider(rule("eliminate", "A + B - B", "A", List.of(), List.of()))
            .activatedRules();
        var rewrite = activated.getFirst();
        var parser = new de.regelsuche.parse.ExpressionParser();
        for (String partial : List.of("1/y", "y^0", "log(y)", "sqrt(y)")) {
            var before = parser.parseTerm("x + (" + partial + ") - (" + partial + ")");
            assertTrue(!rewrite.matches(before), partial);
            assertThrows(IllegalArgumentException.class, () -> rewrite.apply(before));
            var graph = new de.regelsuche.egraph.EGraph();
            var root = graph.addExpression(before);
            var result = new de.regelsuche.egraph.EqualitySaturation(activated)
                .saturate(graph, root, node -> 1);
            assertEquals(before, result.expression(), partial);
        }
        var total = parser.parseTerm("x + sin(y) - sin(y)");
        assertTrue(rewrite.matches(total));
        assertEquals(parser.parseTerm("x"), rewrite.apply(total));
        assertTrue(de.regelsuche.retention.RetainedGraph.measure(rewrite).work() > 0);
    }

    @Test
    void retainingAPartialBindingDoesNotNeedAnElisionGuard() {
        var rewrite = provider(rule("keep", "A + 0", "A", List.of(), List.of()))
            .activatedRules().getFirst();
        var parser = new de.regelsuche.parse.ExpressionParser();
        var before = parser.parseTerm("(1/x) + 0");
        assertTrue(rewrite.matches(before));
        assertEquals(parser.parseTerm("1/x"), rewrite.apply(before));
    }

    @Test
    void eliminatedBindingInspectionFailsClosedAtItsWorkBound() {
        var rewrite = provider(rule("eliminate", "A + B - B", "A", List.of(), List.of()))
            .activatedRules().getFirst();
        de.regelsuche.ast.Expr eliminated = new de.regelsuche.ast.VariableExpr("y");
        for (int i = 0; i < 600; i++) {
            eliminated = new de.regelsuche.ast.BinaryExpr(eliminated,
                de.regelsuche.ast.BinaryOperator.ADD, new de.regelsuche.ast.NumberExpr(1));
        }
        var before = new de.regelsuche.ast.BinaryExpr(
            new de.regelsuche.ast.BinaryExpr(new de.regelsuche.ast.VariableExpr("x"),
                de.regelsuche.ast.BinaryOperator.ADD, eliminated),
            de.regelsuche.ast.BinaryOperator.SUB, eliminated);
        assertTrue(!rewrite.matches(before));
        assertThrows(IllegalArgumentException.class, () -> rewrite.apply(before));
    }

    @Test
    void nonUnaryElisionGuardRejectsWithoutTraversingArbitraryArity() {
        assertEquals(nonUnaryElisionWork(2), nonUnaryElisionWork(4_096));
    }

    private static long nonUnaryElisionWork(int arity) {
        var rewrite = provider(rule("eliminate", "A + B - B", "A", List.of(), List.of()))
            .activatedRules().getFirst();
        var eliminated = new de.regelsuche.ast.FunctionExpr("unknown",
            java.util.Collections.nCopies(arity, new de.regelsuche.ast.VariableExpr("y")));
        var before = new de.regelsuche.ast.BinaryExpr(
            new de.regelsuche.ast.BinaryExpr(new de.regelsuche.ast.VariableExpr("x"),
                de.regelsuche.ast.BinaryOperator.ADD, eliminated),
            de.regelsuche.ast.BinaryOperator.SUB, eliminated);
        long[] work = {0};
        var sink = new de.regelsuche.retention.RetainedOperation.Sink() {
            @Override public void executionWork(long units) { work[0] += units; }
            @Override public void validationWork(long units) { work[0] += units; }
            @Override public void checkpoint() { }
            @Override public void retainedReferences(de.regelsuche.retention.RetainedGraph.Visitor visitor) {
                visitor.reference(work);
            }
        };
        try (var observation = de.regelsuche.retention.RetainedOperation.open(sink)) {
            assertTrue(!rewrite.matches(before));
        }
        return work[0];
    }

    private static InventoryBackedRewriteRuleProvider provider(ReusableRule... rules) {
        var repository = new InMemoryRuleInventoryRepository();
        for (var rule : rules) repository.save(rule);
        return new InventoryBackedRewriteRuleProvider(
            repository, RuleInventoryConfiguration.enabledDefaults(), List.of());
    }

    private static ReusableRule rule(String id, String left, String right,
                                     List<String> relations, List<String> assumptions) {
        return new ReusableRule(id, left, right, relations,
            CandidateProofStatus.FORMALLY_PROVED, RuleStatus.NEW, 3, 4.0,
            Instant.EPOCH, id, null, 0, 0, List.of(), 1.0, assumptions);
    }
}
