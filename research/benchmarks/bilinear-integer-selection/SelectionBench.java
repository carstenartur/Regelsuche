package transfer;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.bouncycastle.crypto.bls.Fp2Element;
import org.openjdk.jmh.annotations.*;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(3)
public class SelectionBench {
    public enum Kernel {
        CONVOLUTION_256(256), CONVOLUTION_2048(2048), CONVOLUTION_8192(8192), FP2_381(381);
        final int bits;
        Kernel(int bits) { this.bits = bits; }
    }

    @Param({"CONVOLUTION_256", "CONVOLUTION_2048", "CONVOLUTION_8192", "FP2_381"}) public Kernel kernel;
    private final BigInteger[][] x = new BigInteger[64][2], y = new BigInteger[64][2];
    private int index;

    @Setup public void setup() {
        var random = new Random(124738);
        for (int i = 0; i < x.length; i++) {
            for (int j = 0; j < 2; j++) {
                x[i][j] = operand(random);
                y[i][j] = operand(random);
            }
            check(x[i], y[i]);
        }
        BigInteger[] edge = kernel == Kernel.FP2_381
            ? new BigInteger[] {BigInteger.ZERO, BigInteger.ONE, Fp2Element.P.subtract(BigInteger.ONE)}
            : new BigInteger[] {BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE.negate()};
        for (var a : edge) for (var b : edge) for (var c : edge) for (var d : edge) {
            check(new BigInteger[] {a, b}, new BigInteger[] {c, d});
        }
    }

    private BigInteger operand(Random random) {
        if (kernel == Kernel.FP2_381) {
            BigInteger n;
            do { n = new BigInteger(kernel.bits, random); } while (n.compareTo(Fp2Element.P) >= 0);
            return n;
        }
        var n = new BigInteger(kernel.bits, random).setBit(kernel.bits - 1);
        return random.nextBoolean() ? n : n.negate();
    }

    private void check(BigInteger[] a, BigInteger[] b) {
        var expected = direct(a, b);
        var old = firstFit(a, b);
        var plan = selected(a, b);
        if (!Arrays.equals(expected, old) || !Arrays.equals(expected, plan)) {
            throw new AssertionError("Raw BigInteger disagreement for " + kernel);
        }
        if (kernel == Kernel.FP2_381) {
            var field = Fp2Element.of(a[0], a[1]).mul(Fp2Element.of(b[0], b[1]));
            var expectedField = new BigInteger[] {field.c0(), field.c1()};
            for (var values : new BigInteger[][] {expected, old, plan}) {
                if (!Arrays.equals(expectedField, finish(values))) throw new AssertionError("bcprov mismatch");
            }
        }
    }

    private BigInteger[] direct(BigInteger[] a, BigInteger[] b) {
        var ac = a[0].multiply(b[0]);
        var middle = a[0].multiply(b[1]).add(a[1].multiply(b[0]));
        var bd = a[1].multiply(b[1]);
        return kernel == Kernel.FP2_381 ? new BigInteger[] {ac.subtract(bd), middle}
            : new BigInteger[] {ac, middle, bd};
    }

    private BigInteger[] firstFit(BigInteger[] a, BigInteger[] b) {
        return kernel == Kernel.FP2_381 ? GeneratedKernels.complex(a, b) : GeneratedKernels.convolution(a, b);
    }

    private BigInteger[] selected(BigInteger[] a, BigInteger[] b) {
        return switch (kernel) {
            case CONVOLUTION_256 -> Selected.convolution256(a, b);
            case CONVOLUTION_2048 -> Selected.convolution2048(a, b);
            case CONVOLUTION_8192 -> Selected.convolution8192(a, b);
            case FP2_381 -> Selected.fp2(a, b);
        };
    }

    private BigInteger[] finish(BigInteger[] result) {
        if (kernel == Kernel.FP2_381) {
            result[0] = result[0].mod(Fp2Element.P);
            result[1] = result[1].mod(Fp2Element.P);
        }
        return result;
    }

    @Benchmark public BigInteger[] direct() {
        int i = index++ & 63;
        return finish(direct(x[i], y[i]));
    }
    @Benchmark public BigInteger[] firstFit() {
        int i = index++ & 63;
        return finish(firstFit(x[i], y[i]));
    }
    @Benchmark public BigInteger[] selected() {
        int i = index++ & 63;
        return finish(selected(x[i], y[i]));
    }
}
