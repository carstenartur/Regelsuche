package de.regelsuche.api.searchgraph.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.api.searchgraph.SearchGraphEdgeDto;
import de.regelsuche.transform.RewriteKind;
import java.util.List;
import org.junit.jupiter.api.Test;

class RewriteSignalClassifierTest {

    private final RewriteSignalClassifier classifier = new RewriteSignalClassifier();

    @Test
    void classifiesCommutativityAsLowSignal() {
        SearchGraphEdgeDto edge = new SearchGraphEdgeDto("a+b", "b+a", "commutativity", RewriteKind.NORMALIZE, 0,
            List.of(), List.of(), true);
        assertEquals(RewriteSignal.LOW_SIGNAL, classifier.classify(edge));
    }

    @Test
    void classifiesSimplificationAsHighSignal() {
        SearchGraphEdgeDto edge = new SearchGraphEdgeDto("a+0", "a", "remove_zero", RewriteKind.SIMPLIFY, 3,
            List.of(), List.of(), true);
        assertEquals(RewriteSignal.HIGH_SIGNAL, classifier.classify(edge));
    }
}
