package de.regelsuche.solver.ir;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.solver.ir.ExistentialIntroduction.*;
import de.regelsuche.solver.ir.SolverIr.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExistentialIntroductionTest {
    private static final CoreExpressionIrAdapter TERMS = new CoreExpressionIrAdapter();
    private static final SourceProvenance SOURCE = new SourceProvenance(
        "control", "existential-witness", SolverIr.sha256("public algebra control"));
    private static final Predicate H = equality("h", "x", "y");

    @Test void replaysFactoredWitnessAgainstIndependentlySuppliedStatement() {
        var target = statement("w", "w + w", "2 * (x + 1)");
        var witness = term("1 + x");
        var leaf = new NormalForm(target.instantiate(witness));
        var proof = new Proof(target, witness, leaf);
        assertEquals(term("(1 + x) + (1 + x)"), leaf.instance().goal().left());
        assertFalse(leaf.instance().declarations().contains(real("w")));
        assertTrue(ExistentialIntroduction.replay(statement("w", "w + w", "2 * (x + 1)"), proof));
        assertFalse(ExistentialIntroduction.replay(target, new Proof(target, term("2 + x"), leaf)));
        var forged = new NormalForm(target.instantiate(term("2 + x")));
        assertFalse(ExistentialIntroduction.replay(target, new Proof(target, term("2 + x"), forged)));
    }

    @Test void detachedReplayAcceptsAlphaRenamingOnlyForTheBoundDeclaration() {
        var source = statement("w", "w + x", "y + x");
        var proof = new Proof(source, term("y"), new NormalForm(source.instantiate(term("y"))));
        assertTrue(ExistentialIntroduction.replay(statement("z", "z + x", "y + x"), proof));
        assertFalse(ExistentialIntroduction.replay(statement("z", "z + y", "x + y"), proof));
        assertFalse(ExistentialIntroduction.replay(new Statement(source.body(), "x"), proof));
    }

    @Test void instantiationPreservesOuterSymbols() {
        for (String binder : List.of("w", "z")) {
            var target = statement(binder, binder + " + x", "y + x");
            var witness = term("y");
            var instance = target.instantiate(witness);
            assertEquals(new Goal(Relation.EQUALS, term("y + x"), term("y + x")), instance.goal());
            assertTrue(ExistentialIntroduction.replay(target, new Proof(target, witness, new NormalForm(instance))));
            assertThrows(IllegalArgumentException.class, () -> target.instantiate(term(binder + " + 1")));
            assertThrows(IllegalArgumentException.class, () -> target.instantiate(term("u")));
        }
        assertThrows(IllegalArgumentException.class, () -> statement("x", "x", "y"));
        assertThrows(IllegalArgumentException.class, () -> statement("w", "w", "y", equality("h", "w", "y")));
    }

    @Test void hypothesisUseRetainsReceiverContextAndExactGoal() {
        var target = statement("w", "w", "y", H);
        var witness = term("x");
        var instance = target.instantiate(witness);
        assertEquals(List.of(H), instance.assumptions());
        assertTrue(ExistentialIntroduction.replay(target, new Proof(target, witness, new Hypothesis(instance, "h"))));
        assertFalse(ExistentialIntroduction.replay(target, new Proof(target, witness, new Hypothesis(instance, "absent"))));
        assertFalse(ExistentialIntroduction.replay(target, new Proof(target, term("y + 1"),
            new Hypothesis(target.instantiate(term("y + 1")), "h"))));
        var noPremise = statement("w", "w", "y");
        assertFalse(ExistentialIntroduction.replay(noPremise,
            new Proof(noPremise, witness, new Hypothesis(instance, "h"))));
        var changed = statement("w", "w", "y", equality("h", "x", "y + 1"));
        assertFalse(ExistentialIntroduction.replay(changed,
            new Proof(changed, witness, new Hypothesis(changed.instantiate(witness), "h"))));
    }

    @Test void rejectsBorrowedRootEvenWhenInstantiatedLeafIsIdentical() {
        var target = statement("w", "w", "x");
        var different = statement("w", "x", "w");
        var witness = term("x");
        assertEquals(target.instantiate(witness), different.instantiate(witness));
        var proof = new Proof(different, witness, new NormalForm(different.instantiate(witness)));
        assertTrue(ExistentialIntroduction.replay(different, proof));
        assertFalse(ExistentialIntroduction.replay(target, proof));
    }

    @Test void rejectsPartialOrOutOfScopeWitnessEvenForVacuousBody() {
        var target = statement("w", "x", "x");
        var leaf = new NormalForm(target.instantiate(term("0")));
        assertTrue(ExistentialIntroduction.replay(target, new Proof(target, term("0"), leaf)));
        for (var witness : List.of(term("1 / 0"), term("w"), term("u"), term("log(x)"))) {
            assertFalse(ExistentialIntroduction.replay(target, new Proof(target, witness, leaf)));
        }
        var typed = Obligation.create("witness-goal", List.of(real("w"), new SymbolDeclaration("x", Sort.BOOLEAN)),
            List.of(Theory.REAL_ARITHMETIC), List.of(), new Goal(Relation.EQUALS, term("w"), term("x")),
            RequestedEvidence.SYMBOLIC_CERTIFICATE, SOURCE);
        assertThrows(IllegalArgumentException.class, () -> new Statement(typed, "w"));
        assertThrows(IllegalArgumentException.class, () -> new Statement(typed, "absent"));
        assertThrows(IllegalArgumentException.class, () -> statement("w", "w / w", "1"));
    }

    @Test void preflightsTheExpandedInstanceAndTheWitnessOfAVacuousGoal() {
        var target = statement("w", "w * w * w * w", "x");
        var small = term("x + y");
        assertDoesNotThrow(() -> target.instantiate(small));
        var large = term("x + y + x + y + x");
        assertThrows(IllegalArgumentException.class, () -> target.instantiate(large));
        var vacuous = statement("w", "x", "x");
        var leaf = new NormalForm(vacuous.instantiate(term("0")));
        Expression deep = term("x");
        for (int i = 0; i < 32; i++) deep = new Binary(BinaryOperator.ADD, deep, term("0"));
        assertFalse(ExistentialIntroduction.replay(vacuous, new Proof(vacuous, deep, leaf)));
        assertFalse(ExistentialIntroduction.replay(vacuous,
            new Proof(vacuous, new Literal("1".repeat(65)), leaf)));
    }

    @Test void searchesForAWitnessMissingFromTheGoalAndChecksItInTheRecipientScope() {
        var target = statement("w", "w + x", "y");
        var run = ExistentialWitnessSearch.search(target, 256);
        assertEquals(ExistentialWitnessSearch.Status.FOUND, run.status());
        var proof = run.proof().orElseThrow();
        assertEquals(term("y - x"), proof.witness());
        assertTrue(ExistentialIntroduction.replay(statement("z", "z + x", "y"), proof));
        assertFalse(ExistentialIntroduction.replay(statement("z", "z + y", "x"), proof));
        assertTrue(run.examined() > 1);
        assertEquals(ExistentialWitnessSearch.Status.BUDGET_EXHAUSTED,
            ExistentialWitnessSearch.search(target, run.examined() - 1).status());
        assertEquals(run, ExistentialWitnessSearch.search(target, run.examined()));
    }

    @Test void finiteExhaustionAndPremiseUseDoNotAssertNonexistence() {
        var premise = equality("h", "x + x", "y");
        var target = statement("w", "w + w", "y", premise);
        var run = ExistentialWitnessSearch.search(target, 256);
        var proof = run.proof().orElseThrow();
        assertEquals(term("x"), proof.witness());
        assertInstanceOf(Hypothesis.class, proof.premise());
        var noPremise = statement("w", "w + w", "y");
        assertFalse(ExistentialIntroduction.replay(noPremise, proof));
        var exhausted = ExistentialWitnessSearch.search(noPremise, 256);
        assertEquals(ExistentialWitnessSearch.Status.GRAMMAR_EXHAUSTED, exhausted.status());
        assertTrue(exhausted.proof().isEmpty());
        assertEquals(exhausted, ExistentialWitnessSearch.search(noPremise, exhausted.examined()));
        var empty = ExistentialWitnessSearch.search(target, 0);
        assertEquals(0, empty.examined());
        assertEquals(ExistentialWitnessSearch.Status.BUDGET_EXHAUSTED, empty.status());
        assertThrows(IllegalArgumentException.class, () -> ExistentialWitnessSearch.search(target, -1));
    }

    private static Statement statement(String binder, String left, String right, Predicate... assumptions) {
        var body = Obligation.create("witness-goal", List.of(real("x"), real("y"), real(binder)),
            List.of(Theory.REAL_ARITHMETIC), List.of(assumptions), new Goal(Relation.EQUALS, term(left), term(right)),
            RequestedEvidence.SYMBOLIC_CERTIFICATE, SOURCE);
        return new Statement(body, binder);
    }

    private static SymbolDeclaration real(String name) { return new SymbolDeclaration(name, Sort.REAL); }
    private static Expression term(String text) { return TERMS.parse(text); }
    private static Predicate equality(String id, String a, String b) {
        return new Predicate(id, Relation.EQUALS, term(a), term(b));
    }
}
