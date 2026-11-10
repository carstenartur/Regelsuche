package de.regelsuche.assumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AssumptionTest {
    @Test
    void contextDedupesByExpression() {
        AssumptionContext context = new AssumptionContext();
        context.add(Assumption.nonZero("b"));
        context.add(Assumption.nonZero("b"));
        context.add(Assumption.nonZero("c"));
        assertEquals(2, context.snapshot().size());
        assertTrue(context.snapshot().stream().anyMatch(a -> a.expression().equals("b != 0")));
        assertTrue(context.snapshot().stream().anyMatch(a -> a.expression().equals("c != 0")));
    }

    @Test
    void positiveAndNonZeroAreDifferentExpressions() {
        AssumptionContext context = new AssumptionContext();
        context.add(Assumption.positive("x"));
        context.add(Assumption.nonZero("x"));
        assertEquals(2, context.snapshot().size());
        assertNotEquals(context.snapshot().get(0).expression(), context.snapshot().get(1).expression());
    }

    @Test
    void assumptionSignatureNormalizesAndDeduplicatesExpressions() {
        AssumptionSignature signature = AssumptionSignature.ofExpressions(
            java.util.List.of(" b   != 0 ", "0 != b", "b≠0", "x > 0")
        );
        assertEquals(java.util.List.of("b != 0", "x > 0"), signature.normalizedAssumptions());
        assertEquals("b != 0;x > 0", signature.fingerprint());
    }

    @Test
    void normalizationPreservesCompoundOperators() {
        // >  must not match inside >=, and < must not match inside <=.
        // Without a negative-lookahead guard, "x >= 0" would become "x > = 0".
        AssumptionSignature sig = AssumptionSignature.ofExpressions(
            java.util.List.of("x >= 0", "y<=1", "a>b", "c < d")
        );
        assertEquals(java.util.List.of("a > b", "c < d", "x >= 0", "y <= 1"),
            sig.normalizedAssumptions());
        assertEquals("a > b;c < d;x >= 0;y <= 1", sig.fingerprint());
    }

    @Test
    @SuppressWarnings("deprecation")
    void customPredicatesRequireIdentityEvenWithMatchingSymbols() {
        for (Assumption.Kind kind : java.util.List.of(Assumption.Kind.CUSTOM_PREDICATE, Assumption.Kind.CUSTOM)) {
            Assumption required = new Assumption(kind, "differentiable(f, x)", java.util.List.of("f", "x"));
            Assumption continuous = new Assumption(kind, "continuous(f, x)", java.util.List.of("f", "x"));
            assertEquals(AssumptionTruthValue.UNKNOWN, required.truthValueUnder(java.util.List.of(continuous)));
            assertEquals(AssumptionTruthValue.TRUE, required.truthValueUnder(java.util.List.of(required)));
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void domainFactsRequireTheSameExpressionInEitherDirection() {
        for (Assumption.Kind kind : List.of(Assumption.Kind.DOMAIN_MEMBERSHIP, Assumption.Kind.DOMAIN)) {
            for (List<String> symbols : List.of(List.of("x"), List.<String>of())) {
                Assumption integer = new Assumption(kind, "x ∈ Z", symbols);
                Assumption real = new Assumption(kind, "x ∈ R", symbols);
                assertEquals(AssumptionTruthValue.UNKNOWN, integer.truthValueUnder(List.of(real)),
                    () -> kind + " with symbols " + symbols + " must not infer integer from real");
                assertEquals(AssumptionTruthValue.UNKNOWN, real.truthValueUnder(List.of(integer)),
                    () -> kind + " with symbols " + symbols + " must not infer generic domain inclusion");
                assertEquals(AssumptionTruthValue.TRUE, integer.truthValueUnder(List.of(integer)));
                assertEquals(AssumptionTruthValue.TRUE, real.truthValueUnder(List.of(real)));
            }
        }
    }

    @Test
    void unknownKnowledgeCannotProveAnIdenticalExpression() {
        Assumption required = Assumption.domainMembership("x", "Z");
        Assumption unknown = new Assumption(Assumption.Kind.UNKNOWN, "x ∈ Z", List.of("x"));
        assertEquals(AssumptionTruthValue.UNKNOWN, required.truthValueUnder(List.of(unknown)));
        assertEquals(AssumptionTruthValue.TRUE, required.truthValueUnder(List.of(unknown, required)));
    }

    @Test
    void typedNumericAndSignImplicationsRemainAvailable() {
        assertEquals(AssumptionTruthValue.TRUE,
            Assumption.integer("n").truthValueUnder(List.of(Assumption.natural("n"))));
        assertEquals(AssumptionTruthValue.TRUE,
            Assumption.real("n").truthValueUnder(List.of(Assumption.rational("n"))));
        assertEquals(AssumptionTruthValue.TRUE,
            Assumption.nonZero("x").truthValueUnder(List.of(Assumption.positive("x"))));
        assertEquals(AssumptionTruthValue.UNKNOWN,
            Assumption.integer("n").truthValueUnder(List.of(Assumption.real("n"))));
        assertEquals(AssumptionTruthValue.UNKNOWN,
            Assumption.nonZero("y").truthValueUnder(List.of(Assumption.positive("x"))));
    }

    @Test
    void integerKnowledgeImpliesRationalButUnknownStaysUnknown() {
        assertEquals(AssumptionTruthValue.TRUE,
            Assumption.rational("n").truthValueUnder(java.util.List.of(Assumption.integer("n"))));
        assertEquals(AssumptionTruthValue.UNKNOWN,
            Assumption.real("x").truthValueUnder(java.util.List.of(Assumption.unknown("x"))));
    }
}
