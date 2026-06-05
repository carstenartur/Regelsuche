package de.regelsuche.moves.hypothesis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Expr;
import java.util.List;
import org.junit.jupiter.api.Test;

class TermOccurrenceIndexTest {

    @Test
    void indexCountsRepeatedSubtrees() {
        Expr root = HypothesisExpressions.parseTerm("(a + b)^2 + 6*(a + b) + 5").orElseThrow();
        TermOccurrenceIndex index = TermOccurrenceIndex.forExpression(root);
        assertEquals(2, index.occurrenceCount("a + b"));
        assertTrue(index.repeatedComposites().stream().anyMatch(o -> o.canonicalValue().equals("a + b")));
    }

    @Test
    void indexIsDeterministic() {
        Expr root = HypothesisExpressions.parseTerm("x^2 + 6*x + 5").orElseThrow();
        List<TermOccurrence> first = TermOccurrenceIndex.forExpression(root).occurrences();
        List<TermOccurrence> second = TermOccurrenceIndex.forExpression(root).occurrences();
        assertEquals(first, second);
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
