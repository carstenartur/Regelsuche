package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.mining.TypedOutputPattern;
import de.regelsuche.mining.TypedPatternGeneralizer;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.RecognitionProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Development transfer controls: not the separately frozen #1026 experiment. */
class TypedModPowAcTransferIntegrationTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final RecognitionProfile ac = RecognitionProfile.arithmeticAc();

    @ParameterizedTest
    @ValueSource(strings = {
        "program(modpow(b,s*t,m),modpow(b,t,m))",
        "program(modpow(b,t*s,m),modpow(b,t,m))",
        "program(modpow(b,t,m),modpow(b,t*s,m))",
        "program(11,modpow(b,t,m),modpow(b,t*s,m))"
    })
    void learnedWitnessTransfersAcrossBothFactorOrdersAndOutputContexts(String text) {
        var model = learnedFromTrain();
        var input = program(text);
        var result = new TypedOutputPattern(model, ac).find(input, 64);
        assertTrue(result.complete());
        assertEquals(1, result.applications().size());
        var application = result.applications().getFirst();
        assertSame(input, application.source());
        assertEquals(ac, application.recognitionProfile());
        assertThrows(UnsupportedOperationException.class, () -> application.recognitionTrace().add("invented"));
        int rewritten = application.positions().getFirst();
        int retained = application.positions().get(1);
        assertSame(input.arguments().get(retained), application.target().arguments().get(retained),
            "even a matched unchanged output must retain its original Expr object");
        for (int i = 0; i < input.arguments().size(); i++) {
            if (i != rewritten) assertSame(input.arguments().get(i), application.target().arguments().get(i));
        }
        assertEquals(parser.parseTerm("modpow(modpow(b,t,m),s,m)"), application.target().arguments().get(rewritten));
        var checked = ModPowCompositionReplay.verify(input, application.target(), premises("b", "s", "t", "m")).orElseThrow();
        assertSame(input, checked.source());
        assertSame(application.target(), checked.target());
        assertEquals(text.contains("s*t") ? ModPowDagRediscoveryStudy.RULE_RIGHT : ModPowDagRediscoveryStudy.RULE_LEFT,
            checked.primitiveRule());
    }

    @Test
    void defaultRecognitionStillRefusesReversedFactorsRatherThanAssumingArithmeticLaws() {
        var exact = new TypedOutputPattern(learnedFromTrain());
        var reversed = exact.find(program("program(modpow(b,t*s,m),modpow(b,t,m))"), 64);
        assertTrue(reversed.complete());
        assertTrue(reversed.applications().isEmpty());
        var ordinary = exact.find(program("program(modpow(b,s*t,m),modpow(b,t,m))"), 64);
        assertEquals(1, ordinary.applications().size());
        assertEquals(RecognitionProfile.exact(), ordinary.applications().getFirst().recognitionProfile());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "program(modpow(b,t*s,m),modpow(b,r,m))",
        "program(modpow(b,t*s,m),modpow(c,t,m))",
        "program(modpow(b,t*s,m),modpow(b,t,n))"
    })
    void algebraicRecognitionCannotInventAMissingSharedBinding(String text) {
        var result = new TypedOutputPattern(learnedFromTrain(), ac).find(program(text), 64);
        assertTrue(result.complete());
        assertTrue(result.applications().isEmpty());
    }

    @Test
    void recognitionDoesNotAuthorizeMissingOrStaleArithmeticPremises() {
        var input = program("program(modpow(b,t*s,m),modpow(b,t,m))");
        var application = new TypedOutputPattern(learnedFromTrain(), ac).find(input, 64)
            .applications().getFirst();
        assertTrue(ModPowCompositionReplay.verify(input, application.target(), List.of()).isEmpty());
        assertTrue(ModPowCompositionReplay.verify(input, application.target(), premises("a0", "q0", "e0", "n0")).isEmpty());
    }

    @Test
    void assignmentBudgetDoesNotTurnUnsearchedOrdersIntoProvenAbsence() {
        var input = program("program(modpow(b,t*s,m),modpow(b,t,m))");
        var result = new TypedOutputPattern(learnedFromTrain(), ac).find(input, 1);
        assertEquals(1, result.assignmentAttempts());
        assertFalse(result.complete());
        assertEquals(1, result.applications().size());
        assertTrue(result.matcherSteps() > 0);
    }

    @Test
    void unsupportedRecognitionModesFailExplicitly() {
        var model = learnedFromTrain();
        assertThrows(NullPointerException.class, () -> new TypedOutputPattern(model, null));
        assertThrows(IllegalArgumentException.class, () -> new TypedOutputPattern(model, RecognitionProfile.algebraicAc()));
        assertThrows(IllegalArgumentException.class, () -> new TypedOutputPattern(model,
            RecognitionProfile.exact().withRecognitionRules(Set.of("external"), 1)));
        assertThrows(IllegalArgumentException.class, () -> new TypedOutputPattern(model,
            new RecognitionProfile(Set.of(BinaryOperator.SUB), Set.of(BinaryOperator.SUB))));
    }

    private TypedPatternGeneralizer.Candidate learnedFromTrain() {
        Expr source = parser.parseTerm("program(modpow(a,q*e,n),modpow(a,e,n))");
        var examples = new ArrayList<TypedPatternGeneralizer.Example>();
        for (int i = 0; i < ModPowDagRediscoveryStudy.FORMATION.size(); i++) {
            var run = ModPowDagRediscoveryStudy.runCase(ModPowDagRediscoveryStudy.FORMATION.get(i), false);
            assertTrue(run.completeBoundedRelation());
            var names = Map.of("a", "a" + i, "q", "q" + i, "e", "e" + i, "n", "n" + i);
            Expr observedSource = rename(source, names);
            Expr observedTarget = rename(run.selectedProgram(), names);
            var assumptions = premises(names.get("a"), names.get("q"), names.get("e"), names.get("n"));
            assertTrue(ModPowCompositionReplay.verify(observedSource, observedTarget, assumptions).isPresent());
            examples.add(new TypedPatternGeneralizer.Example(observedSource, observedTarget, assumptions));
        }
        return new TypedPatternGeneralizer().generalize(examples).orElseThrow();
    }

    private FunctionExpr program(String text) { return (FunctionExpr) parser.parseTerm(text); }

    private static List<String> premises(String base, String first, String second, String modulus) {
        return List.of(base + " integer", first + " integer", first + " >= 0", second + " integer",
            second + " >= 0", modulus + " integer", modulus + " > 0");
    }

    private static Expr rename(Expr expression, Map<String, String> names) {
        if (expression instanceof VariableExpr variable) return new VariableExpr(names.getOrDefault(variable.name(), variable.name()));
        if (expression instanceof BinaryExpr binary) return new BinaryExpr(rename(binary.left(), names), binary.operator(), rename(binary.right(), names));
        if (expression instanceof FunctionExpr function) return new FunctionExpr(function.name(), function.arguments().stream().map(child -> rename(child, names)).toList());
        return expression;
    }
}
