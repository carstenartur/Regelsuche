package de.regelsuche.solver.ir;

import static org.junit.jupiter.api.Assertions.*;
import static de.regelsuche.solver.ir.ScopedProofDag.Rule.*;
import de.regelsuche.solver.ir.ScopedProofDag.Node;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScopedProofDagTest {
    @Test void localReferencesAreLexicalAndEveryNodeMustHaveAPurpose() {
        var request = NaturalInductionTest.request(NaturalInductionTest.goal);
        var p = ScopedProofDag.create(request, List.of(Node.local(0), Node.of(INTRO,0)));
        assertEquals(p, ScopedProofDag.read(request,p.canonical()));
        assertThrows(IllegalArgumentException.class, () -> ScopedProofDag.create(request, List.of(Node.local(0))));
        assertThrows(IllegalArgumentException.class, () -> ScopedProofDag.create(request, List.of(Node.local(0), Node.of(NORMALIZE))));
        assertThrows(IllegalArgumentException.class, () -> ScopedProofDag.create(request, List.of(Node.of(APPLY,1,1), Node.of(NORMALIZE))));
        assertThrows(IllegalArgumentException.class, () -> ScopedProofDag.read(request, p.canonical()+"\n"));
        var doubled = new java.util.ArrayList<Node>();
        doubled.add(Node.of(NORMALIZE));
        for (int i=0;i<10;i++) doubled.add(Node.of(PAIR,i,i));
        assertThrows(IllegalArgumentException.class, () -> ScopedProofDag.create(request,doubled));
        var newContext = NaturalInductionTest.request(NaturalInductionTest.goal,NaturalInductionTest.base);
        assertThrows(IllegalArgumentException.class, () -> ScopedProofDag.read(newContext,p.canonical()));
    }

    @Test void wellFoundedCallsRequireAnInScopeInductionHypothesisAndExplicitDecreaseChild() {
        var request = NaturalInductionTest.request(NaturalInductionTest.goal);
        var decrease = Node.of(DECREASE,0,1);
        assertThrows(IllegalArgumentException.class, () -> ScopedProofDag.create(request, List.of(Node.of(ZERO), Node.of(NORMALIZE), decrease)));
        assertThrows(IllegalArgumentException.class, () -> ScopedProofDag.create(request, List.of(Node.local(0), Node.of(STRONG_INDUCTION,0))));
        var dag = ScopedProofDag.create(request, List.of(Node.of(ZERO), Node.of(NORMALIZE), decrease, Node.of(STRONG_INDUCTION,2)));
        assertEquals(dag, ScopedProofDag.read(request, dag.canonical()));
    }

    @Test void existentialLocalsAreOnlyAvailableInTheContinuation() {
        var request = NaturalInductionTest.request(NaturalInductionTest.goal, NaturalInductionTest.base);
        var valid = ScopedProofDag.create(request, List.of(Node.premise(0), Node.local(1), Node.local(0),
            Node.of(APPLY, 1, 2), Node.of(EXISTS_ELIM, 0, 3)));
        assertEquals(valid, ScopedProofDag.read(request, valid.canonical()));
        assertThrows(IllegalArgumentException.class, () -> ScopedProofDag.create(request,
            List.of(Node.local(0), Node.local(1), Node.of(EXISTS_ELIM, 0, 1))));
        assertThrows(IllegalArgumentException.class, () -> ScopedProofDag.create(request,
            List.of(Node.premise(0), Node.local(2), Node.of(EXISTS_ELIM, 0, 1))));
    }

    @Test void malformedScopedProofDocumentsFailClosed() {
        var request = NaturalInductionTest.request(NaturalInductionTest.goal, NaturalInductionTest.base);
        var dag = ScopedProofDag.create(request, List.of(Node.premise(0), Node.of(AND_LEFT, 0)));
        // Structural admissibility is not a mathematical certificate.
        assertEquals(dag, ScopedProofDag.read(request, dag.canonical()));
        assertEquals(dag.hashCode(), ScopedProofDag.read(request, dag.canonical()).hashCode());
        assertNotEquals(dag, ScopedProofDag.create(request, List.of(Node.premise(0), Node.of(AND_RIGHT, 0))));
        assertNotEquals(dag, null);
        var header = ScopedProofDag.SCHEMA + "\n" + request.contentHash() + "\n";
        for (String invalid : List.of("", header, header + "PREMISE\n", header + "PREMISE -1\n",
                header + "PREMISE 1\n", header + "PREMISE 0\nAND_LEFT -1 -1\n",
                header + "PREMISE 0\nAND_RIGHT -1 1\n", header + "PREMISE 0\nAND_LEFT 0 0\n",
                header + "PREMISE 0\nAND_LEFT -1\n", header + " ".repeat(16_384))) {
            assertThrows(IllegalArgumentException.class, () -> ScopedProofDag.read(request, invalid), invalid.substring(0, Math.min(100, invalid.length())));
        }
        assertThrows(IllegalArgumentException.class, () -> ScopedProofDag.create(request, List.of()));
        assertThrows(IllegalArgumentException.class, () -> ScopedProofDag.create(request, List.of(Node.indexed(DECLARATION, 2))));
        assertThrows(IllegalArgumentException.class, () -> ScopedProofDag.create(request, List.of(Node.indexed(UNFOLD, 0, 0))));
    }

    @Test void lexicalDefinitionsAndCaseBranchesHaveSeparateScopes() {
        var request = NaturalInductionTest.request(NaturalInductionTest.goal, NaturalInductionTest.base);
        var let = ScopedProofDag.create(request, List.of(Node.of(ZERO), Node.local(0),
            Node.indexed(UNFOLD, 0, 1), Node.of(LET, 0, 2)));
        assertEquals(List.of(ScopedProofDag.Local.DEFINITION),
            let.fold((node, scope, children) -> node.rule() == LET ? children.getLast() : scope));
        assertThrows(IllegalArgumentException.class, () -> ScopedProofDag.create(request,
            List.of(Node.local(0), Node.of(ZERO), Node.of(LET, 0, 1))));
        assertThrows(IllegalArgumentException.class, () -> ScopedProofDag.create(request,
            List.of(Node.local(0), Node.indexed(UNFOLD, 0, 0), Node.of(INTRO, 1))));
        var cases = ScopedProofDag.create(request, List.of(Node.premise(0), Node.local(0),
            Node.of(CASES, 0, 1, 1)));
        assertEquals(List.of(0, 1, 1), cases.fold((node, scope, children) ->
            node.rule() == CASES ? children.stream().map(List::getFirst).toList() : List.of(scope.size())));
        assertThrows(IllegalArgumentException.class, () -> ScopedProofDag.create(request,
            List.of(Node.local(0), Node.of(ZERO), Node.of(CASES, 0, 1, 1))));
    }

    @Test void sourceFormationProjectsBothSidesAndRejectsWitnessDependentConclusions() {
        var p = NaturalInductionTest.p;
        var q = NaturalInductionTest.q;
        var n = NaturalInductionTest.n;
        var pn = NaturalInductionTest.at(p, n);
        var qn = NaturalInductionTest.at(q, n);
        var independent = NaturalInductionTest.all(qn);
        var exists = new SolverIr.Binder(SolverIr.BinderKind.EXISTS, SolverIr.Sort.NATURAL, pn);
        var dependentRule = NaturalInductionTest.all(NaturalInductionTest.app("implies", pn, qn));
        var independentRule = NaturalInductionTest.all(NaturalInductionTest.app("implies", pn, independent));
        var conjunction = NaturalInductionTest.app("and", exists, independentRule);
        var request = NaturalInductionTest.request(NaturalInductionTest.goal,
            SolverIr.Predicate.holds("pair", conjunction), SolverIr.Predicate.holds("dependent", dependentRule));
        var report = ScopedProofSearch.discover(request, 2048);
        assertFalse(report.exhausted());
        assertTrue(report.proposals().stream().anyMatch(x -> x.dag().obligation().goal().left().equals(exists)
            && x.dag().nodes().getLast().rule() == AND_LEFT));
        assertTrue(report.proposals().stream().anyMatch(x -> x.dag().obligation().goal().left().equals(independentRule)
            && x.dag().nodes().getLast().rule() == AND_RIGHT));
        var conclusion = report.proposals().stream()
            .filter(x -> x.dag().obligation().goal().left().equals(independent))
            .findFirst().orElseThrow().dag();
        assertEquals(EXISTS_ELIM, conclusion.nodes().getLast().rule());
        assertEquals(conclusion, ScopedProofDag.read(conclusion.obligation(), conclusion.canonical()));
        assertFalse(report.proposals().stream().anyMatch(x -> x.dag().obligation().goal().left().equals(qn)));
        assertTrue(ScopedProofSearch.discover(request, 0).exhausted());
        assertThrows(IllegalArgumentException.class, () -> ScopedProofSearch.discover(request, -1));
        assertThrows(IllegalArgumentException.class, () -> ScopedProofSearch.discover(request, 65_537));
    }
}
