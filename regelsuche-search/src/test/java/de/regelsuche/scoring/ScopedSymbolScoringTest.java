package de.regelsuche.scoring;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.cost.StructuralCostModel;
import de.regelsuche.scoring.cost.TransformationGoal;
import de.regelsuche.symbol.SymbolId;
import de.regelsuche.symbol.SymbolScope;
import de.regelsuche.symbol.SymbolicExpression;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;

class ScopedSymbolScoringTest {
    private static final UUID NAMESPACE = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
    private final ExpressionScorer scorer = new ExpressionScorer();

    @ParameterizedTest
    @ValueSource(strings = {"x", "x + 0", "x*x+x", "(x+3)^2", "(x+3)*(x-3)", "x^2-9", "sin(x)+12*x"})
    void identityTransportDoesNotInflateAnyScoreComponent(String source) {
        String identity = source.replace("x", new SymbolId(NAMESPACE, Long.MAX_VALUE).identifier());
        assertEquals(scorer.score(source), scorer.score(identity));
    }

    @Test void sourceLabelsAndAllocationOrderDoNotAffectTheMathematicalScore() {
        String small = new SymbolId(NAMESPACE, 1).identifier();
        String large = new SymbolId(NAMESPACE, Long.MAX_VALUE).identifier();
        String elsewhere = new SymbolId(UUID.fromString("abcdefab-cdef-abcd-efab-cdefabcdefab"), 9999).identifier();
        assertEquals(scorer.score(small + "+" + large), scorer.score(elsewhere + "+" + small));
        var document = SymbolicExpression.parse("longVariable + 0", new SymbolScope());
        var renamed = document.withDisplayName(document.sourceBindings().get("longVariable"), "q");
        assertEquals(scorer.score("x+0"), scorer.score(document.identityText()));
        assertEquals(scorer.score(document.identityText()), scorer.score(renamed.identityText()));
    }

    @Test void patternBonusesStillDistinguishDifferentIds() {
        String first = new SymbolId(NAMESPACE, 1).identifier();
        String second = new SymbolId(NAMESPACE, 2).identifier();
        assertEquals(scorer.score("(x+3)*(x-3)"), scorer.score("(" + first + "+3)*(" + first + "-3)"));
        assertEquals(scorer.score("(x+3)*(y-3)"), scorer.score("(" + first + "+3)*(" + second + "-3)"));
        assertTrue(scorer.score("(" + first + "+3)*(" + first + "-3)").recognizedPatternBonus() >
            scorer.score("(" + first + "+3)*(" + second + "-3)").recognizedPatternBonus());
    }

    @Test void symmetricCostIsIndependentOfOrdinalWidthButStillSeesIdentity() {
        String first = new SymbolId(NAMESPACE, 1).identifier();
        String second = new SymbolId(NAMESPACE, Long.MAX_VALUE).identifier();
        String third = new SymbolId(NAMESPACE, 12345).identifier();
        assertEquals(symmetryCost("a+b+a"), symmetryCost(first + "+" + second + "+" + first));
        assertEquals(symmetryCost("a+b+c"), symmetryCost(first + "+" + second + "+" + third));
        assertTrue(symmetryCost(first + "+" + second + "+" + first) <
            symmetryCost(first + "+" + second + "+" + third));
    }

    @Test void nonReservedIdentifiersAndFunctionNamesKeepTheirExistingTextCosts() {
        assertEquals(new ExpressionScore(4, 4, 1, 0, 0, ScoreRevision.CURRENT), scorer.score("x+12"));
        assertEquals(new ExpressionScore(7, 7, 1, 0, 0, ScoreRevision.CURRENT), scorer.score("longx+0"));
        assertEquals(new ExpressionScore(6, 4, 0, 1, 0, ScoreRevision.CURRENT), scorer.score("sin(x)"));
        String name = "prefix_" + new SymbolId(NAMESPACE, 2).identifier();
        assertEquals(name.length(), scorer.score(name).stringLength());
        String function = new SymbolId(NAMESPACE, 2).identifier();
        assertEquals(function.length() + 3, scorer.score(function + "(x)").stringLength());
    }

    @ParameterizedTest
    @ValueSource(strings = {"rsym_bad", "rsym_0123456789abcdef0123456789abcdef_0",
        "rsym_0123456789abcdef0123456789abcdef_01", "rsym_0123456789abcdef0123456789abcdef_9223372036854775808"})
    void malformedReservedIdsCannotBeDiscountedAsOrdinaryVariables(String invalid) {
        assertThrows(IllegalArgumentException.class, () -> scorer.score(invalid + "+1"));
        assertThrows(IllegalArgumentException.class, () -> ExpressionScorer.identityIndependentLength(invalid));
    }

    @ParameterizedTest
    @EnumSource(TransformationGoal.class)
    void everyBuiltInGoalKeepsItsCostWhenOnlySymbolTransportChanges(TransformationGoal goal) {
        var document = SymbolicExpression.parse("a+b+a", new SymbolScope());
        var model = goal.defaultCostModel();
        String identity = document.identityText();
        byte[] encodedBefore = new de.regelsuche.symbol.SymbolicExpressionCodec().encode(document);
        assertEquals(model.cost("a+b+a", new ExpressionParser().parseTerm("a+b+a"), scorer.score("a+b+a")),
            model.cost(identity, document.expression(), scorer.score(identity)));
        assertArrayEquals(encodedBefore, new de.regelsuche.symbol.SymbolicExpressionCodec().encode(document));
    }

    private int symmetryCost(String source) {
        return StructuralCostModel.SYMMETRY.cost(source, new ExpressionParser().parseTerm(source), scorer.score(source));
    }
}
