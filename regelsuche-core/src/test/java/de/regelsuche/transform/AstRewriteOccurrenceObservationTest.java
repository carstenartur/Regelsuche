package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.input.*;
import de.regelsuche.parse.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AstRewriteOccurrenceObservationTest {
    @Test void observesTheActualRepeatedOccurrencesWithoutChangingReturnedTransformations() {
        var engine = new AstRewriteTransformationEngine();
        String source = "(x + 0) * (x + 0)";
        var observed = new ArrayList<AstRewriteTransformationEngine.GeneratedTransformation>();
        var actual = engine.transformWithOccurrences(source, observed::add);
        assertEquals(engine.transform(source), actual);
        assertEquals(actual.size(), observed.size());
        for (int i = 0; i < actual.size(); i++) assertSame(actual.get(i), observed.get(i).transformation());
        var repeated = observed.stream().filter(item -> item.transformation().rule().equals("ast_add_zero_right")).toList();
        assertEquals(List.of(List.of(0), List.of(1)), repeated.stream().map(item -> item.position().path()).toList());
        assertEquals(1, repeated.stream().map(item -> item.transformation().applicationKey()).distinct().count());
        var parser = new ExpressionParser();
        var root = parser.parse(new InputRequest(InputType.TERM, source)).terms().getFirst();
        for (var item : repeated) {
            assertEquals(source, item.sourceExpression());
            assertEquals(item.sourceOccurrenceExpression(), ExpressionFormatter.format(item.position().subtreeAt(root).orElseThrow()));
            var replacement = parser.parse(new InputRequest(InputType.TERM, item.transformedOccurrenceExpression())).terms().getFirst();
            assertEquals(item.transformation().transformedExpression(), ExpressionFormatter.format(item.position().replaceAt(root, replacement).rewrittenRoot().orElseThrow()));
        }
    }
}
