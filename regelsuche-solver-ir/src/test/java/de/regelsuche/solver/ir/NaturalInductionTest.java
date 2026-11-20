package de.regelsuche.solver.ir;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.solver.ir.SolverIr.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class NaturalInductionTest {
    static final Symbol p = new Symbol("invariant");
    static final Symbol q = new Symbol("other");
    static final Expression n = new Bound(0);
    static Call app(String name, Expression... terms) { return new Call(name, List.of(terms)); }
    static Expression at(Expression predicate, Expression i) { return app("at", predicate, i); }
    static Binder all(Expression body) { return new Binder(BinderKind.FORALL, Sort.NATURAL, body); }
    static final Predicate base = Predicate.holds("start", at(p, app("nat", new Literal("0"))));
    static final Predicate step = Predicate.holds("iterate", all(app("implies", at(p,n), at(p,app("succ", n)))));
    static final Goal goal = Goal.holds(all(at(p, n)));

    static Obligation request(Goal endpoint, Predicate... premises) {
        return Obligation.create("induction", List.of(new SymbolDeclaration(p.name(), Sort.NAT_PREDICATE),
            new SymbolDeclaration(q.name(), Sort.NAT_PREDICATE)), List.of(Theory.SCOPED_ANALYSIS),
            List.of(premises), endpoint, RequestedEvidence.FORMAL_PROOF,
            new SourceProvenance("test", "generic-induction", SolverIr.sha256("generic-induction")));
    }

    @Test void formationUsesOnlyTheContextAndBindsTwoDifferentProofLeaves() {
        var proof = NaturalInduction.discover(request(Goal.holds(all(at(q, n))), base, step)).getFirst();
        var expected = request(goal, base, step);
        assertTrue(NaturalInduction.matches(expected, proof));
        assertEquals(expected, proof.conclusion());
        assertEquals("start", proof.base().id());
        assertEquals("iterate", proof.step().id());
        assertFalse(NaturalInduction.matches(request(Goal.holds(all(at(q,n))), base,step), proof));
        assertFalse(NaturalInduction.matches(expected, new NaturalInduction.Plan(expected, p.name(), step, base)));
        assertFalse(NaturalInduction.matches(request(goal, step), proof));
    }

    @Test void noInferenceFromChangedBasePredicateWrongIndexOrWrongStepDirection() {
        assertTrue(NaturalInduction.discover(request(goal, Predicate.holds("start", at(q, app("nat", new Literal("0")))), step)).isEmpty());
        assertTrue(NaturalInduction.discover(request(goal, Predicate.holds("start", at(p, app("nat", new Literal("1")))), step)).isEmpty());
        for (var bad : List.of(all(app("implies", at(p,app("succ",n)), at(p,n))),
                all(app("implies", at(p,n), at(q,app("succ",n)))), all(app("implies", at(p,n),at(p,n))))) {
            assertTrue(NaturalInduction.discover(request(goal, base, Predicate.holds("iterate", bad))).isEmpty());
        }
    }
}
