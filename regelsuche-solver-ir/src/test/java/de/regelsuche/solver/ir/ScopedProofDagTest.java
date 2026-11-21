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
}
