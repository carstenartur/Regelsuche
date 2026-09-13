package de.regelsuche.value;

import static de.regelsuche.value.ExprValueFactory.ValueOperator.ADD;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class CompactValueArenaReviewTest {
    @Test void nestedUnicodeOperatorDigestsPreserveRolesAndMultiplicityUnderForcedKeyCollisions() {
        var firstName = ExprValueFactory.ValueOperator.function("f\uD800", 2);
        var otherName = ExprValueFactory.ValueOperator.function("f\uD801", 2);
        try (var first = new CompactValueArena(CompactValueArena.Limits.DEFAULT, 0);
             var second = new CompactValueArena(CompactValueArena.Limits.DEFAULT, 0)) {
            var x = first.variable("x");
            var y = first.variable("y");
            var repeated = first.ordered(ADD, List.of(x, y, x));
            var value = first.ordered(firstName, List.of(repeated, y));
            var swapped = first.ordered(firstName, List.of(y, repeated));
            var renamed = first.ordered(otherName, List.of(repeated, y));
            var lessMultiplicity = first.ordered(firstName, List.of(first.ordered(ADD, List.of(x, y)), y));
            second.variable("unrelated");
            var otherY = second.variable("y");
            var otherX = second.variable("x");
            var equivalent = second.ordered(firstName,
                List.of(second.ordered(ADD, List.of(otherY, otherX, otherX)), otherY));
            var beforeForeign = second.metrics();
            assertThrows(IllegalArgumentException.class, () -> second.ordered(firstName, List.of(repeated, otherY)));
            assertEquals(beforeForeign, second.metrics(), "foreign child handles cannot mutate the target owner");
            assertEquals(0, first.metrics().digestComputations());
            assertNotEquals(value, equivalent, "local handles remain owner-bound even for equal structure");
            assertEquals(first.stableDigest(value), second.stableDigest(equivalent));
            assertNotEquals(first.stableDigest(value), first.stableDigest(swapped));
            assertNotEquals(first.stableDigest(value), first.stableDigest(renamed));
            assertNotEquals(first.stableDigest(value), first.stableDigest(lessMultiplicity));
            assertSame(value, first.ordered(firstName, List.of(repeated, y)));
        }
    }
}
