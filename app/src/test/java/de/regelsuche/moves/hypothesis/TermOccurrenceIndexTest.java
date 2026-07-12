package de.regelsuche.moves.hypothesis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.moves.RewriteMoveKind;
import de.regelsuche.moves.apply.LocalRewriteApplier;
import de.regelsuche.moves.enumerate.Depth1MoveEnumerator.CandidateMove;
import de.regelsuche.moves.enumerate.TreeLocalMoveEnumerator;
import de.regelsuche.moves.hypothesis.TermOccurrenceIndex.ExpressionOccurrence;
import de.regelsuche.moves.hypothesis.TermOccurrenceIndex.OccurrenceId;
import de.regelsuche.value.ExprValueFactory;
import de.regelsuche.value.ExprValueFactory.AssociativeCommutativeValue;
import de.regelsuche.value.ExprValueFactory.ExprValue;
import de.regelsuche.value.ExprValueFactory.NumberValue;
import de.regelsuche.value.ExprValueFactory.OrderedValue;
import de.regelsuche.value.ExprValueFactory.ValueKey;
import de.regelsuche.value.ExprValueFactory.VariableValue;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class TermOccurrenceIndexTest {

    @Test
    void indexCountsRepeatedSubtrees() {
        Expr root = HypothesisExpressions.parseTerm("(a + b)^2 + 6*(a + b) + 5").orElseThrow();
        try (TermOccurrenceIndex index = TermOccurrenceIndex.forExpression(root)) {
            assertEquals(2, index.occurrenceCount("a + b"));
            assertTrue(index.repeatedComposites().stream()
                    .anyMatch(occurrence -> occurrence.canonicalValue().equals("a + b")));
        }
    }

    @Test
    void indexIsDeterministic() {
        Expr root = HypothesisExpressions.parseTerm("x^2 + 6*x + 5").orElseThrow();
        List<TermOccurrence> first;
        List<TermOccurrence> second;
        try (TermOccurrenceIndex firstIndex = TermOccurrenceIndex.forExpression(root);
                TermOccurrenceIndex secondIndex = TermOccurrenceIndex.forExpression(root)) {
            first = firstIndex.occurrences();
            second = secondIndex.occurrences();
        }
        assertEquals(first, second);
    }

    @Test
    void valueFactoryDefinesAcIdentityMultiplicityAndBoundedScope() {
        Expr expression = HypothesisExpressions.parseTerm("c + (a + b) + a").orElseThrow();
        try (ExprValueFactory factory = new ExprValueFactory()) {
            assertSame(factory.variable("a"), factory.variable("a"));

            ExprValue leftGrouped = factory.fromExpr(
                    HypothesisExpressions.parseTerm("(a + b) + c").orElseThrow());
            ExprValue rightGrouped = factory.fromExpr(
                    HypothesisExpressions.parseTerm("a + (b + c)").orElseThrow());
            ExprValue permuted = factory.fromExpr(
                    HypothesisExpressions.parseTerm("c + a + b").orElseThrow());
            assertSame(leftGrouped, rightGrouped);
            assertSame(leftGrouped, permuted);

            ExprValue repeated = factory.fromExpr(
                    HypothesisExpressions.parseTerm("a + a + b").orElseThrow());
            AssociativeCommutativeValue sum =
                    assertInstanceOf(AssociativeCommutativeValue.class, repeated);
            assertEquals(3, sum.operandCount());
            assertEquals(2, sum.multiplicityOf(factory.variable("a")));
            assertNotEquals(repeated, factory.fromExpr(
                    HypothesisExpressions.parseTerm("a + b").orElseThrow()));
            assertNotEquals(
                    factory.fromExpr(HypothesisExpressions.parseTerm("a - b").orElseThrow()),
                    factory.fromExpr(HypothesisExpressions.parseTerm("b - a").orElseThrow()));

            ValueKey key = factory.keyOf(expression);
            assertEquals(key, new ValueKey(key.encoded()));
            assertTrue(key.encoded().startsWith(ValueKey.FORMAT_VERSION));
        }

        try (ExprValueFactory first = new ExprValueFactory();
                ExprValueFactory second = new ExprValueFactory()) {
            ExprValue firstValue = first.fromExpr(expression);
            ExprValue secondValue = second.fromExpr(expression);
            assertNotSame(firstValue, secondValue);
            assertEquals(firstValue, secondValue);
            assertEquals(firstValue.key(), secondValue.key());
        }

        ExprValueFactory bounded = new ExprValueFactory(1);
        bounded.variable("a");
        assertThrows(IllegalStateException.class, () -> bounded.variable("b"));
        bounded.close();
        assertThrows(IllegalStateException.class, bounded::size);
    }

    @Test
    void sharedValuesExposeNoOccurrenceOrSourceMetadata() {
        for (Class<?> type : new Class<?>[] {
                ExprValue.class,
                VariableValue.class,
                NumberValue.class,
                OrderedValue.class,
                AssociativeCommutativeValue.class
        }) {
            assertFalse(Arrays.stream(type.getMethods())
                    .map(Method::getName)
                    .anyMatch(name -> name.equals("parent")
                            || name.equals("source")
                            || name.equals("position")
                            || name.equals("metadata")),
                    () -> type.getSimpleName() + " must remain occurrence-independent");
        }
    }

    @Test
    void repeatedSyntaxUsesRetainOccurrenceIdentityAndShareOneValue() {
        Expr root = HypothesisExpressions.parseTerm("a + a + b").orElseThrow();
        try (TermOccurrenceIndex index = TermOccurrenceIndex.forExpression(root)) {
            List<ExpressionOccurrence> uses = index.valueOccurrences().stream()
                    .filter(occurrence -> occurrence.syntax() instanceof VariableExpr variable
                            && variable.name().equals("a"))
                    .toList();

            assertEquals(2, uses.size());
            assertNotEquals(uses.get(0).id(), uses.get(1).id());
            assertNotEquals(uses.get(0).position(), uses.get(1).position());
            assertSame(uses.get(0).value(), uses.get(1).value());
            assertEquals(2, new HashSet<>(uses).size(),
                    "a Set of occurrences must retain both uses of one value");
            assertEquals(2, index.occurrenceCount(uses.getFirst().valueKey()));
            assertTrue(index.contains(uses.getFirst().valueKey()));
            assertEquals(uses, index.occurrencesOf(uses.getFirst().value()));
            assertEquals(uses.getFirst(), index.occurrence(uses.getFirst().id()).orElseThrow());
        }
    }

    @Test
    void acEquivalentSubtreesShareValueWithoutLosingTheirPaths() {
        Expr root = HypothesisExpressions.parseTerm("(a + b) * (b + a)").orElseThrow();
        try (TermOccurrenceIndex index = TermOccurrenceIndex.forExpression(root)) {
            List<ExpressionOccurrence> factors = index.valueOccurrences().stream()
                    .filter(occurrence -> occurrence.position().path().size() == 1)
                    .filter(occurrence -> occurrence.syntax() instanceof BinaryExpr)
                    .toList();

            assertEquals(2, factors.size());
            assertSame(factors.get(0).value(), factors.get(1).value());
            assertNotEquals(factors.get(0).id(), factors.get(1).id());
            assertEquals(2, index.occurrencesOf(factors.getFirst().value()).size());
        }
    }

    @Test
    void equationSidesShareOneValueScopeButKeepSeparateOccurrenceRoots() {
        var equation = HypothesisExpressions.parseEquation("a + b = b + a").orElseThrow();
        try (TermOccurrenceIndex index = TermOccurrenceIndex.forEquation(equation)) {
            ExpressionOccurrence left = index.occurrence(
                    OccurrenceId.equationSide("L", List.of())).orElseThrow();
            ExpressionOccurrence right = index.occurrence(
                    OccurrenceId.equationSide("R", List.of())).orElseThrow();

            assertSame(left.value(), right.value());
            assertNotEquals(left.id(), right.id());
            assertEquals(2, index.occurrencesOf(left.value()).size());
        }
    }

    @Test
    void localRewriteTargetsOneOccurrenceOfASharedValue() {
        String expression = "(x^2 + 6*x + 5) + (x^2 + 6*x + 5)";
        Expr root = HypothesisExpressions.parseTerm(expression).orElseThrow();
        ValueKey originalValueKey;
        ExpressionOccurrence left;
        try (TermOccurrenceIndex index = TermOccurrenceIndex.forExpression(root)) {
            left = index.occurrence(OccurrenceId.expression(List.of(0))).orElseThrow();
            ExpressionOccurrence right = index.occurrence(
                    OccurrenceId.expression(List.of(1))).orElseThrow();
            assertSame(left.value(), right.value());
            originalValueKey = left.valueKey();
        }

        TreeLocalMoveEnumerator enumerator = new TreeLocalMoveEnumerator();
        List<CandidateMove> candidates = enumerator.enumerate(expression).stream()
                .filter(candidate -> candidate.position().equals(left.position()))
                .filter(candidate -> candidate.move().kind() == RewriteMoveKind.COMPLETE_SQUARE)
                .map(TreeLocalMoveEnumerator.LocalCandidateMove::move)
                .toList();
        assertFalse(candidates.isEmpty(), "no local COMPLETE_SQUARE move for the left occurrence");

        LocalRewriteApplier.LocalRewriteResult result =
                new LocalRewriteApplier().apply(root, left.position(), candidates);
        assertTrue(result.success(), result.failureReason());

        Expr rewritten = HypothesisExpressions.parseTerm(result.expressionAfter()).orElseThrow();
        try (TermOccurrenceIndex rewrittenIndex = TermOccurrenceIndex.forExpression(rewritten)) {
            assertEquals(1, rewrittenIndex.occurrenceCount(originalValueKey),
                    "rewriting the left occurrence must leave the right use unchanged");
        }
    }

    @Test
    void skeletonAbstractsComplexAtom() {
        Expr root = HypothesisExpressions.parseTerm("(a + b)^2 + 6*(a + b) + 5").orElseThrow();
        Expr atom = HypothesisExpressions.parseTerm("a + b").orElseThrow();
        TermSkeleton skeleton = TermSkeleton.forAtom(root, atom, "A");
        assertEquals("A ^ 2 + 6 * A + 5", skeleton.skeletonText());
        assertEquals("a + b", skeleton.atomCanonical());
    }
}
