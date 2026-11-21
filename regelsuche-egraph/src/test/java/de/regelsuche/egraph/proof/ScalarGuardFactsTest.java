package de.regelsuche.egraph.proof;

import static de.regelsuche.egraph.proof.ScalarGuardFacts.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.assumption.Assumption;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScalarGuardFactsTest {
    @Test void recognizesOnlyExactPremisesForTheRequestedSymbol() {
        var unrestricted = new ScalarGuardFacts(ALL, 3, true);
        var cases = Map.ofEntries(
            Map.entry(Assumption.positive("x"), new ScalarGuardFacts(POS, 3, true)),
            Map.entry(Assumption.nonZero("x"), new ScalarGuardFacts(NEG | POS, 3, true)),
            Map.entry(Assumption.invertible("x"), new ScalarGuardFacts(NEG | POS, 3, true)),
            Map.entry(Assumption.nonNegative("x"), new ScalarGuardFacts(ZERO | POS, 3, true)),
            Map.entry(Assumption.natural("x"), new ScalarGuardFacts(ZERO | POS, 1, true)),
            Map.entry(Assumption.domainMembership("x", "N"), new ScalarGuardFacts(ZERO | POS, 1, true)),
            Map.entry(Assumption.integer("x"), new ScalarGuardFacts(ALL, 1, true)),
            Map.entry(Assumption.domainMembership("x", "Z"), new ScalarGuardFacts(ALL, 1, true)),
            Map.entry(Assumption.customPredicate("x = 0", List.of("x")), new ScalarGuardFacts(ZERO, 1, true)),
            Map.entry(Assumption.customPredicate("x == 0", List.of("x")), new ScalarGuardFacts(ZERO, 1, true)),
            Map.entry(Assumption.customPredicate("x < 0", List.of("x")), new ScalarGuardFacts(NEG, 3, true)));
        for (var entry : cases.entrySet()) {
            var premise = entry.getKey();
            assertEquals(entry.getValue(), variable("x", List.of(premise)), premise.toString());
            assertEquals(unrestricted, variable("y", List.of(premise)));
            var forged = new Assumption(premise.kind(), premise.expression() + " && opaque", premise.symbols());
            assertEquals(unrestricted, variable("x", List.of(forged)));
            var wrongScope = new Assumption(premise.kind(), premise.expression(), List.of("x", "y"));
            assertEquals(unrestricted, variable("x", List.of(wrongScope)));
        }
    }

    @Test void mergesIndependentRefinementsAndKeepsContradictionsInEitherOrder() {
        var positiveInteger = List.of(Assumption.positive("x"), Assumption.integer("x"));
        assertEquals(new ScalarGuardFacts(POS, 1, true), variable("x", positiveInteger));
        assertEquals(variable("x", positiveInteger), variable("x", positiveInteger.reversed()));
        var contradictory = List.of(Assumption.nonZero("x"), Assumption.customPredicate("x = 0", List.of("x")));
        for (var order : List.of(contradictory, contradictory.reversed())) {
            var facts = variable("x", order);
            for (var predicate : Predicate.values()) assertEquals(Truth.CONFLICT, facts.evaluate(predicate));
        }
    }

    @Test void ignoresUnsupportedStatementsWithoutLosingScalarDefinedness() {
        for (var premise : List.of(Assumption.domainMembership("x", "opaque"), Assumption.unknown("x"),
                Assumption.customPredicate("x > 1", List.of("x")))) {
            var facts = variable("x", List.of(premise));
            assertEquals(Truth.UNKNOWN, facts.positive());
            assertEquals(Truth.UNKNOWN, facts.integer());
            assertEquals(Truth.TRUE, facts.evaluate(Predicate.EXACT_RATIONAL));
        }
    }
}
