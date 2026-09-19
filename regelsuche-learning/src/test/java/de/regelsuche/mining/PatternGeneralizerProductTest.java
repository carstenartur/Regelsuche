package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.scoring.ExpressionScore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PatternGeneralizerProductTest {
    @Test
    void existingGeneralizerRetainsBothVaryingFactorsInACandidate() {
        var pattern = new PatternGeneralizer().generalize(List.of(
            observed("t15", "modpow(a,15,n)", "modpow(modpow(a,3,n),5,n)"),
            observed("t28", "modpow(b,28,m)", "modpow(modpow(b,4,m),7,m)"),
            observed("t66", "modpow(c,66,k)", "modpow(modpow(c,6,k),11,k)")
        )).orElseThrow();
        assertEquals("modpow(x,A*A2,B)", pattern.leftPattern());
        assertEquals("modpow(modpow(x,A,B),A2,B)", pattern.rightPattern());
        assertTrue(pattern.parameterRelations().containsAll(
            List.of("N1 = A*A2", "N2 = A", "N3 = A2")));
    }

    private static SuccessfulTransformationPath observed(String id, String source, String target) {
        // Synthetic hypothesis-formation inputs, explicitly NOT proof evidence.
        return new SuccessfulTransformationPath(id, source, target,
            List.of(source, target), List.of("synthetic-product-observation"),
            new ExpressionScore(100, 0, 0, 0, 0), new ExpressionScore(90, 0, 0, 0, 0),
            false, "synthetic fixture: independent verification required", Map.of(), List.of());
    }
}
