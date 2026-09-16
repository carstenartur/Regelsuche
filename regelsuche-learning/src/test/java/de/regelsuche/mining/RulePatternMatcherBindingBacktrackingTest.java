package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.parse.ExpressionFormatter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real matching controls; no learned policy or frozen study is changed. */
@Timeout(10)
class RulePatternMatcherBindingBacktrackingTest {
    private final RulePatternMatcher matcher = new RulePatternMatcher();

    @Test
    void revisitsAnAdditionBindingWhenItsSiblingRequiresTheOtherOrder() {
        var bindings = matcher.match("(A+B)*(A-B)", "(y+x)*(x-y)");
        assertTrue(bindings.isPresent(), "the left addition must backtrack after the subtraction rejects its first bindings");
        assertEquals("x", ExpressionFormatter.format(bindings.orElseThrow().get("A")));
        assertEquals("y", ExpressionFormatter.format(bindings.orElseThrow().get("B")));
    }

    @Test
    void revisitsBindingsAcrossFunctionArguments() {
        var bindings = matcher.match("f(A+B,A-B)", "f(y+x,x-y)");
        assertTrue(bindings.isPresent(), "a later argument must be allowed to reject an earlier binding choice");
        assertEquals("x", ExpressionFormatter.format(bindings.orElseThrow().get("A")));
        assertEquals("y", ExpressionFormatter.format(bindings.orElseThrow().get("B")));
    }

    @Test
    void preservesRepeatedCompoundBindingsWhileBacktracking() {
        var bindings = matcher.match("(A+B)*(A-B)+B^2", "((u+v)+x)*(x-(u+v))+(u+v)^2");
        assertTrue(bindings.isPresent());
        assertEquals("x", ExpressionFormatter.format(bindings.orElseThrow().get("A")));
        assertEquals("u + v", ExpressionFormatter.format(bindings.orElseThrow().get("B")));
    }

    @Test
    void doesNotTurnAFalseResidualIntoAConsistentBinding() {
        assertTrue(matcher.match("(A+B)*(A-B)+B^2", "(y+x)*(x-y)+z^2").isEmpty());
        assertTrue(matcher.match("f(A+B,A-B)", "f(y+x,x-z)").isEmpty());
    }

    @Test
    void retainsDirectFirstSelectionWhenBothBindingsAreValid() {
        var bindings = matcher.match("A+B", "x+y").orElseThrow();
        assertEquals("x", ExpressionFormatter.format(bindings.get("A")));
        assertEquals("y", ExpressionFormatter.format(bindings.get("B")));
    }

    @Test
    void failedAttemptsCannotAffectTheNextCall() {
        assertTrue(matcher.match("(A+B)*(A-B)", "(y+x)*(x-z)").isEmpty());
        var bindings = matcher.match("(A+B)*(A-B)", "(x+y)*(x-y)").orElseThrow();
        assertEquals("x", ExpressionFormatter.format(bindings.get("A")));
        assertEquals("y", ExpressionFormatter.format(bindings.get("B")));
    }
}
