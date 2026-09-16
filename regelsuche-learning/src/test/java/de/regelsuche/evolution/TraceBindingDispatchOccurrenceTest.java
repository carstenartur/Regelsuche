package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.search.program.RewriteCandidate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class TraceBindingDispatchOccurrenceTest {
    @Test void validPrimitiveWorkAtAnotherOccurrenceIsNotTheLearnedContinuation() {
        var historical = TraceStrategyDispatchExample.train();
        var model = TraceBindingDispatch.learn(historical.formation(), historical.trials().getFirst().observations(),
            new TraceStrategyDispatchLearner.BindingLimits(32, 100_000, 20_000));
        var flat = TraceStrategyDispatchLearner.engine(historical, TraceStrategyDispatchLearner.Profile.FLAT_EXHAUSTIVE);
        String source = "(x+y)*(x-y)+y*y+(w-z^2+z*z)^2";
        var first = flat.transformMeasured(source).transformations().stream()
            .filter(step -> step.rule().endsWith("difference-product")).findFirst().orElseThrow();
        // These are REAL applicable rewrites, but inside the bound residual rather than on y*y.
        var second = flat.transformMeasured(first.transformedExpression()).transformations().stream()
            .filter(step -> step.rule().endsWith("square-product") && step.transformedExpression().contains("y * y"))
            .findFirst().orElseThrow();
        var third = flat.transformMeasured(second.transformedExpression()).transformations().stream()
            .filter(step -> step.rule().endsWith("cancel-addend")).findFirst().orElseThrow();
        var suffix = new RewriteCandidate("other-occurrence", first.transformedExpression(), third.transformedExpression(),
            List.of(second, third)).toTransformation();
        new ExactPolynomialAnalysis().requireEquivalent(source, third.transformedExpression());
        var bridge = new TraceBindingDispatch(model);
        var attempt = bridge.begin(List.of("difference-product", "square-product", "cancel-addend"), source, first);
        assertTrue(attempt.eligible(), "the two-state prefix fits the learned template");
        assertEquals(2, suffix.primitiveStepCount());
        assertFalse(attempt.accepts(suffix), "full checking must reject the altered residual binding");
        assertTrue(bridge.workUnits() > 0);
    }
}
