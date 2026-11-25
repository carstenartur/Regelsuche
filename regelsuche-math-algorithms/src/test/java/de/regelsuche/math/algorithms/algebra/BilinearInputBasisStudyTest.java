package de.regelsuche.math.algorithms.algebra;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.algebra.BilinearProductLearning.InputBases;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Tensor;
import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Fixed acquisition/evaluation split with exact coordinates and two executable, search-free controls. */
class BilinearInputBasisStudyTest {
    private static final IterationLimits LIMITS = new IterationLimits(10_000_000, 30_000, 15_000);
    private static final ExactRational ZERO = ExactRational.ZERO;
    private static final ExactRational ONE = ExactRational.ONE;

    @Test void rationalShearsOnHeldOutTensorsIncludeAllAcquisitionAndFailedWork() {
        var acquired = new HashSet<Tensor>();
        var heldOut = new HashSet<Tensor>();
        long acquisition = 0, transport = 0, failed = 0, certificates = 0;
        var execution = meter();
        var coordinates = meter();
        var classical = meter();
        int samples = 0;
        for (int parameter : List.of(-1, 2)) {
            var d = ExactRational.integer(parameter);
            var original = new Tensor(2, 2, List.of(List.of(ONE,ZERO,ZERO,d), List.of(ZERO,ONE,ONE,ZERO)));
            assertTrue(acquired.add(original));
            var training = BilinearFormDiscovery.discover(original, 3, LIMITS, () -> false);
            acquisition += training.work();
            var learned = training.value().orElseThrow();
            // The old-basis tensors have a zero leading coefficient in their second slice;
            // every held-out tensor has a nonzero rational coefficient there.
            for (int n = 1; n <= 3; n++) {
                var h = fraction(n, n + 1);
                var k = fraction(-n - 1, n + 2);
                var target = new Tensor(2, 2, List.of(List.of(ONE,ZERO,h.add(d.multiply(k)),d),
                    List.of(k,ONE,ONE.add(h.multiply(k)),h)));
                assertTrue(heldOut.add(target));
                var change = new InputBases(List.of(List.of(ONE,h), List.of(ZERO,ONE)),
                    List.of(List.of(ONE,ZERO), List.of(k,ONE)));
                var transferred = BilinearProductLearning.applyInputBases(learned.request(), learned.witness().program(),
                    change, target, LIMITS, () -> false);
                transport += transferred.work();
                var proof = transferred.value().orElseThrow();

                var stronger = BilinearRankBound.computeRational(target, LIMITS, () -> false);
                var interval = BilinearRankBound.certifyRational(proof.target(), proof.witness().program(),
                    stronger.value().orElseThrow(), LIMITS, () -> false);
                certificates += stronger.work() + interval.work();
                assertEquals(new BilinearRankBound.Interval(3, 3), interval.value().orElseThrow());

                var wrong = new Tensor(2, 2, List.of(target.outputs().get(0), List.of(k,ONE,ZERO,h)));
                var rejected = BilinearProductLearning.applyInputBases(learned.request(), learned.witness().program(),
                    change, wrong, LIMITS, () -> false);
                assertEquals(BilinearProgram.Status.REJECTED, rejected.status());
                assertTrue(rejected.work() > 0);
                failed += rejected.work();

                for (int i = -2; i <= 2; i++) {
                    for (int j = -1; j <= 1; j++) {
                        var x = List.of(fraction(i, 3), ExactRational.integer(j - i));
                        var y = List.of(ExactRational.integer(i + j), fraction(j, 2));
                        var expected = direct(target, x, y, coordinates);
                        assertEquals(expected, threeProductControl(d, h, k, x, y, classical));
                        assertEquals(expected, execute(proof.witness().program(), x, y, execution));
                        samples++;
                    }
                }
            }
        }
        assertEquals(6, heldOut.size());
        assertTrue(java.util.Collections.disjoint(acquired, heldOut));
        assertEquals(90, samples);
        System.out.printf("bilinear-input-basis/v1 sources=%d targets=%d samples=%d acquisition=%d successful-transfer=%d "
                + "failed-transfer=%d rational-bounds=%d execution=%d coordinate-control=%d classical-control=%d%n",
            acquired.size(), heldOut.size(), samples, acquisition, transport, failed, certificates,
            execution.used(), coordinates.used(), classical.used());
    }

    private static ExactRational fraction(int n, int d) {
        return ExactRational.integer(n).divide(ExactRational.integer(d));
    }

    private static CertificateWork meter() { return new CertificateWork(LIMITS, () -> false); }

    private static List<ExactRational> execute(BilinearProgram program, List<ExactRational> x,
            List<ExactRational> y, CertificateWork work) {
        var products = new ArrayList<ExactRational>();
        for (int p = 0; p < program.u().size(); p++) {
            products.add(work.mul(BilinearProgramSchedule.dot(program.u().get(p), x, work),
                BilinearProgramSchedule.dot(program.v().get(p), y, work)));
        }
        return program.w().stream().map(w -> BilinearProgramSchedule.dot(w, products, work)).toList();
    }

    // Fixed coordinate products shared by all outputs, independently contracted with each target slice.
    private static List<ExactRational> direct(Tensor target, List<ExactRational> x, List<ExactRational> y,
            CertificateWork work) {
        var products = new ArrayList<ExactRational>();
        for (var left : x) for (var right : y) products.add(work.mul(left, right));
        return target.outputs().stream().map(w -> BilinearProgramSchedule.dot(w, products, work)).toList();
    }

    // Standard three-product multiplication in Q[t]/(t²-d), with exactly the supplied input maps.
    private static List<ExactRational> threeProductControl(ExactRational d, ExactRational h, ExactRational k,
            List<ExactRational> x, List<ExactRational> y, CertificateWork work) {
        var a = work.add(x.get(0), work.mul(h, x.get(1)));
        var b = x.get(1);
        var c = y.get(0);
        var e = work.add(y.get(1), work.mul(k, c));
        var p = work.mul(a, c);
        var q = work.mul(b, e);
        var r = work.mul(work.add(a, b), work.add(c, e));
        return List.of(work.add(p, work.mul(d, q)), work.sub(work.sub(r, p), q));
    }
}
