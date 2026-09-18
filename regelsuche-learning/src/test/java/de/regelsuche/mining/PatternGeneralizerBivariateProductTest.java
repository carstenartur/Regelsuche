package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.scoring.ExpressionScore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PatternGeneralizerBivariateProductTest {

    @Test
    void generalizesIndependentProductFactorsFromVerifiedTrainingPaths() {
        List<SuccessfulTransformationPath> paths = List.of(
            path("t15", "modpow(a,15,n)", "modpow(modpow(a,3,n),5,n)"),
            path("t28", "modpow(b,28,m)", "modpow(modpow(b,4,m),7,m)"),
            path("t66", "modpow(c,66,k)", "modpow(modpow(c,6,k),11,k)")
        );

        GeneralizedPattern pattern =
            new PatternGeneralizer().generalize(paths).orElseThrow();

        assertEquals("modpow(x,A*A2,v1)", pattern.leftPattern());
        assertEquals("modpow(modpow(x,A,v1),A2,v1)", pattern.rightPattern());
        assertTrue(pattern.parameterRelations().contains("N1 = A*A2"));
        assertTrue(pattern.parameterRelations().contains("N2 = A"));
        assertTrue(pattern.parameterRelations().contains("N3 = A2"));
        assertFalse(pattern.leftPattern().contains("15"));
        assertFalse(pattern.leftPattern().contains("28"));
        assertFalse(pattern.leftPattern().contains("66"));
    }

    private static SuccessfulTransformationPath path(
        String id,
        String source,
        String target
    ) {
        return new SuccessfulTransformationPath(
            id,
            source,
            target,
            List.of(source, target),
            List.of("verified-modpow-composition"),
            new ExpressionScore(100, 0, 0, 0, 0),
            new ExpressionScore(90, 0, 0, 0, 0),
            true,
            "independent-modpow-composition-proof",
            Map.of(),
            List.of(
                "base integer",
                "modulus integer",
                "modulus > 0",
                "all exponents integer",
                "all exponents >= 0")
        );
    }
}
