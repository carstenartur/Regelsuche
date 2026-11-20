package de.regelsuche.sdk.optimization.noncommutative;

import de.regelsuche.math.algorithms.noncommutative.NoncommutativeFormula.Variable;
import de.regelsuche.math.algorithms.noncommutative.NoncommutativeIdentity.Limits;
import de.regelsuche.math.algorithms.noncommutative.NoncommutativeIdentity.Status;
import de.regelsuche.sdk.optimization.CancellationToken;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class NoncommutativeIdentitySdkTest {
    @Test void comparesOrderedFormulasAndForwardsCancellation() {
        var x = new Variable(1);
        assertEquals(Status.IDENTICAL, NoncommutativeIdentitySdk.compare(x, x, List.of(1),
            Limits.defaults(), CancellationToken.NONE).status());
        assertEquals(Status.CANCELLED, NoncommutativeIdentitySdk.compare(x, x, List.of(1),
            Limits.defaults(), () -> true).status());
    }
}
