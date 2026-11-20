package de.regelsuche.math.algorithms.positivity;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static de.regelsuche.math.algorithms.positivity.BernsteinPositivityTest.*;
import static de.regelsuche.math.algorithms.positivity.GramPositivity.Classification.*;
import static org.junit.jupiter.api.Assertions.*;

class GramPositivityTest {
    private static List<List<ExactRational>> symmetric(String a, String b, String c) {
        return List.of(List.of(q(a),q(b)),List.of(q(b),q(c)));
    }

    @Test void exhaustiveThreeByThreeSymmetricMatricesAgreeWithAllPrincipalMinors() {
        // This oracle uses every principal minor, including non-leading 1x1 and 2x2 minors.
        for (int a=-1; a<=1; a++) for (int b=-1; b<=1; b++) for (int c=-1; c<=1; c++)
            for (int d=-1; d<=1; d++) for (int e=-1; e<=1; e++) for (int f=-1; f<=1; f++) {
                assertAgreesWithPrincipalMinors(a, b, c, d, e, f);
            }
    }

    private static void assertAgreesWithPrincipalMinors(int a, int b, int c, int d, int e, int f) {
        var m = List.of(List.of(ExactRational.integer(a),ExactRational.integer(b),ExactRational.integer(c)),
            List.of(ExactRational.integer(b),ExactRational.integer(d),ExactRational.integer(e)),
            List.of(ExactRational.integer(c),ExactRational.integer(e),ExactRational.integer(f)));
        int det = a*d*f + 2*b*c*e - a*e*e - d*c*c - f*b*b;
        boolean psd = a>=0 && d>=0 && f>=0 && a*d>=b*b && a*f>=c*c && d*f>=e*e && det>=0;
        var expected = !psd ? NOT_PSD : det==0 ? POSITIVE_SEMIDEFINITE : POSITIVE_DEFINITE;
        assertEquals(expected, GramPositivity.checkMatrix(m, LIMITS, () -> false).classification(), m.toString());
    }

    @Test void rationalMatrixSymmetryAndSingularSchurPivots() {
        assertEquals(POSITIVE_SEMIDEFINITE, GramPositivity.checkMatrix(symmetric("4/9","2/3","1"), LIMITS, () -> false).classification());
        assertEquals(POSITIVE_DEFINITE, GramPositivity.checkMatrix(symmetric("4/9","2/3","2"), LIMITS, () -> false).classification());
        assertEquals(NOT_PSD, GramPositivity.checkMatrix(symmetric("0","1/3","1"), LIMITS, () -> false).classification());
        var asymmetric = List.of(List.of(q("1"),q("4")),List.of(q("0"),q("1")));
        assertEquals(INVALID, GramPositivity.checkMatrix(asymmetric, LIMITS, () -> false).classification());
        assertEquals(INVALID, GramPositivity.checkMatrix(List.of(List.of(q("1"),q("2"))), LIMITS, () -> false).classification());
    }

    @Test void polynomialRequiresExactReconstructionAndSemidefiniteGram() {
        var source = new SparsePolynomial(List.of(2), Map.of(e(2,0),q("1"), e(1,0),q("4/3"), e(0,0),q("4/9")));
        var basis = List.of(e(0,0),e(1,0));
        var valid = new GramPositivity.Certificate(source, basis, symmetric("4/9","2/3","1"));
        var checked = GramPositivity.verifyPolynomial(source, valid, LIMITS, () -> false);
        assertEquals(BernsteinPositivity.Claim.NONNEGATIVE, checked.claim().orElseThrow());
        var other = square("1/4").polynomial();
        assertFalse(GramPositivity.verifyPolynomial(other, valid, LIMITS, () -> false).valid());
        var forged = new GramPositivity.Certificate(other, basis, valid.matrix());
        assertFalse(GramPositivity.verifyPolynomial(other, forged, LIMITS, () -> false).valid());
        var alteredBasis = new GramPositivity.Certificate(source, List.of(e(0,0),e(2,0)), valid.matrix());
        assertFalse(GramPositivity.verifyPolynomial(source, alteredBasis, LIMITS, () -> false).valid());
        var indefinitePoly = new SparsePolynomial(List.of(2),Map.of(e(2,0),q("-1"),e(0,0),q("1")));
        var indefinite = new GramPositivity.Certificate(indefinitePoly, basis, symmetric("1","0","-1"));
        assertFalse(GramPositivity.verifyPolynomial(indefinitePoly, indefinite, LIMITS, () -> false).valid());
        var x2 = new SparsePolynomial(List.of(2),Map.of(e(2,0),q("1")));
        var positiveMatrix = new GramPositivity.Certificate(x2,List.of(e(1,0)),List.of(List.of(q("1"))));
        assertEquals(BernsteinPositivity.Claim.NONNEGATIVE,
            GramPositivity.verifyPolynomial(x2, positiveMatrix, LIMITS, () -> false).claim().orElseThrow());
        for (var limits : List.of(LIMITS.withMaximumWork(1), new IterationLimits(10000,2,30000))) {
            assertTrue(GramPositivity.verifyPolynomial(source, valid, limits, () -> false).claim().isEmpty());
            assertEquals(UNAVAILABLE, GramPositivity.checkMatrix(valid.matrix(), limits, () -> false).classification());
        }
        assertTrue(GramPositivity.verifyPolynomial(source, valid, LIMITS, () -> true).claim().isEmpty());
    }
}
