package transfer;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.bouncycastle.crypto.bls.Fp2Element;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(2)
@State(Scope.Thread)
public class BilinearBench {
    public enum Kernel { CONVOLUTION_256, CONVOLUTION_2048, CONVOLUTION_8192, FP2_381 }
    // Fp2Element.mul in bcprov-jdk18on 1.85.2 applies both reductions after the bilinear map.
    private static final BigInteger FIELD = new BigInteger(
        "1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eabfffeb153ffffb9feffffffffaaab", 16);

    @Param({"CONVOLUTION_256", "CONVOLUTION_2048", "CONVOLUTION_8192", "FP2_381"}) public Kernel kernel;
    private final BigInteger[][] left = new BigInteger[64][2];
    private final BigInteger[][] right = new BigInteger[64][2];
    private int index;

    @Setup public void setup() {
        var random = new Random(124738);
        for (int i = 0; i < left.length; i++) {
            for (int j = 0; j < 2; j++) {
                left[i][j] = signedOperand(random);
                right[i][j] = signedOperand(random);
            }
            var produced = discovered(left[i], right[i]);
            if (kernel == Kernel.FP2_381) {
                var x = Fp2Element.of(left[i][0], left[i][1]);
                var y = Fp2Element.of(right[i][0], right[i][1]);
                var actual = x.mul(y);
                if (!produced[0].equals(actual.c0()) || !produced[1].equals(actual.c1())) {
                    throw new AssertionError("Generated kernel differs from bcprov at case " + i);
                }
            }
            if (!Arrays.equals(direct(left[i], right[i]), produced)) {
                throw new AssertionError("Generated result differs from direct evaluation for case " + i);
            }
        }
    }

    private BigInteger signedOperand(Random random) {
        if (kernel == Kernel.FP2_381) {
            BigInteger element;
            do element = new BigInteger(FIELD.bitLength(), random); while (element.compareTo(FIELD) >= 0);
            return element;
        }
        int bits = switch (kernel) {
            case CONVOLUTION_256 -> 256;
            case CONVOLUTION_2048 -> 2048;
            case CONVOLUTION_8192 -> 8192;
            case FP2_381 -> throw new AssertionError(kernel);
        };
        BigInteger magnitude = new BigInteger(bits, random).setBit(bits - 1);
        return random.nextBoolean() ? magnitude : magnitude.negate();
    }

    private BigInteger[] direct(BigInteger[] x, BigInteger[] y) {
        var ac = x[0].multiply(y[0]);
        var middle = x[0].multiply(y[1]).add(x[1].multiply(y[0]));
        var bd = x[1].multiply(y[1]);
        return kernel == Kernel.FP2_381 ? new BigInteger[] {ac.subtract(bd).mod(FIELD), middle.mod(FIELD)}
            : new BigInteger[] {ac, middle, bd};
    }

    private BigInteger[] discovered(BigInteger[] x, BigInteger[] y) {
        if (kernel != Kernel.FP2_381) return GeneratedKernels.convolution(x, y);
        BigInteger[] value = GeneratedKernels.complex(x, y);
        value[0] = value[0].mod(FIELD);
        value[1] = value[1].mod(FIELD);
        return value;
    }

    @Benchmark public BigInteger[] direct() {
        int i = index++ & (left.length - 1);
        return direct(left[i], right[i]);
    }

    @Benchmark public BigInteger[] discovered() {
        int i = index++ & (left.length - 1);
        return discovered(left[i], right[i]);
    }
}
