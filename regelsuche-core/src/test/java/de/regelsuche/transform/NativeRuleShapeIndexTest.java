package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativeRuleShapeIndexTest {
    @Test void compilationAndCachedRootChildFilteringNeverProjectNumericValues() {
        var exact = ExactRational.integer(new ProjectionProbe("42"));
        var expression = new BinaryExpr(new VariableExpr("x"), BinaryOperator.ADD, new NumberExpr(exact));
        assertThrows(AssertionError.class, () -> ExpressionFormatter.format(expression), "the actual formatter must trigger this probe");
        ProjectionProbe.calls = 0;
        var rule = new PatternRewriteRule("numeric", PatternExpr.op(BinaryOperator.ADD,
            PatternExpr.var("A"), new PatternExpr.LiteralNumber(exact)), PatternExpr.var("A"));
        var work = new EnumMap<TransformationCursor.Operation, Long>(TransformationCursor.Operation.class);
        var index = new NativeRuleShapeIndex(List.of(rule), (operation, units) -> work.merge(operation, units, Math::addExact));
        var features = index.features(expression);
        var candidates = index.candidates(features);
        assertEquals(0, candidates.nextRuleIndex());
        assertTrue(candidates.currentShapeAdmits(features));
        assertEquals(0, ProjectionProbe.calls);
        assertEquals(2L, work.get(TransformationCursor.Operation.SHAPE_FEATURE_READ));
        assertEquals(3L, work.get(TransformationCursor.Operation.SHAPE_PATTERN_VISIT));
    }

    private static final class ProjectionProbe extends BigInteger {
        static int calls;
        ProjectionProbe(String value) { super(value); }
        ProjectionProbe(byte[] value) { super(value); }
        @Override public BigInteger divide(BigInteger divisor) { return new ProjectionProbe(super.divide(divisor).toByteArray()); }
        @Override public String toString() { calls++; throw new AssertionError("numeric display projection entered shape selection"); }
    }
}
