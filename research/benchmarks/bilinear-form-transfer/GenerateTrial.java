package transfer;

import de.regelsuche.math.algorithms.algebra.BilinearProgram;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import de.regelsuche.sdk.optimization.algebra.BilinearProgramSdk;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;
import java.util.function.IntFunction;

/** Test-only BigInteger backend. The trial compiles and runs the verified program returned by the SDK. */
public final class GenerateTrial {
    private static final OptimizationBudget BUDGET = new OptimizationBudget(25_000_000L, 100_000, 128, 60_000);

    private GenerateTrial() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Expected generated source path");
        var source = new StringBuilder("package transfer;\nimport java.math.BigInteger;\nfinal class GeneratedKernels {\n");
        appendDiscovered(source, "convolution", new BilinearProgram.Tensor(2, 2,
            List.of(row(1, 0, 0, 0), row(0, 1, 1, 0), row(0, 0, 0, 1))), 3);
        // Real and imaginary parts of a Gaussian integer product.
        appendDiscovered(source, "complex", new BilinearProgram.Tensor(2, 2,
            List.of(row(1, 0, 0, -1), row(0, 1, 1, 0))), 3);
        var path = Path.of(args[0]);
        Files.writeString(path, source.append("}\n"), StandardCharsets.UTF_8);
    }

    private static List<ExactRational> row(long... values) {
        return Arrays.stream(values).mapToObj(ExactRational::integer).toList();
    }

    private static void appendDiscovered(StringBuilder source, String name, BilinearProgram.Tensor tensor, int limit) {
        var search = BilinearProgramSdk.discover(tensor, limit, BUDGET, CancellationToken.NONE);
        var discovery = search.value().orElseThrow(() -> new IllegalStateException(name + ": " + search.reason()));
        var program = discovery.witness().program();
        System.out.println(name + " searchWork=" + search.work() + " " + discovery.witness().cost()
            + " lowerBound=" + discovery.lowerBound().lowerBound());
        source.append("    static BigInteger[] ").append(name).append("(BigInteger[] x, BigInteger[] y) {\n");
        for (int i = 0; i < program.u().size(); i++) {
            String left = dot(program.u().get(i), k -> "x[" + k + "]");
            String right = dot(program.v().get(i), k -> "y[" + k + "]");
            source.append("        var p").append(i).append(" = ").append(left).append(".multiply(")
                .append(right).append(");\n");
        }
        var outputs = new StringJoiner(", ", "        return new BigInteger[] {", "};\n    }\n");
        for (var row : program.w()) outputs.add(dot(row, k -> "p" + k));
        source.append(outputs);
    }

    private static String dot(List<ExactRational> row, IntFunction<String> name) {
        String result = null;
        for (int i = 0; i < row.size(); i++) {
            var coefficient = row.get(i);
            if (!coefficient.denominator().equals(BigInteger.ONE)) {
                throw new IllegalArgumentException("Fractional program outside this integer backend: " + coefficient);
            }
            var numerator = coefficient.numerator();
            if (numerator.signum() == 0) continue;
            String term = name.apply(i);
            if (!numerator.abs().equals(BigInteger.ONE)) {
                term += ".multiply(new BigInteger(\"" + numerator.abs() + "\"))";
            }
            if (result == null) result = numerator.signum() < 0 ? term + ".negate()" : term;
            else result += (numerator.signum() < 0 ? ".subtract(" : ".add(") + term + ")";
        }
        return result == null ? "BigInteger.ZERO" : result;
    }
}
