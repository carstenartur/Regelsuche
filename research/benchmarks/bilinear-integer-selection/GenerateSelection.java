package transfer;

import de.regelsuche.math.algorithms.algebra.BilinearIntegerSchedule;
import de.regelsuche.math.algorithms.algebra.BilinearProgram;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import de.regelsuche.sdk.optimization.algebra.BilinearProgramSdk;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.StringJoiner;

/** Research-only renderer. Slot numbering, schedule, costs and candidate choice come from the production SDK. */
public final class GenerateSelection {
    private static final OptimizationBudget BUDGET = new OptimizationBudget(100_000_000, 100_000, 1024, 60_000);

    private GenerateSelection() {}

    public static void main(String[] args) throws Exception {
        var convolution = new BilinearProgram.Tensor(2, 2,
            List.of(row(1, 0, 0, 0), row(0, 1, 1, 0), row(0, 0, 0, 1)));
        var complex = new BilinearProgram.Tensor(2, 2,
            List.of(row(1, 0, 0, -1), row(0, 1, 1, 0)));
        var source = new StringBuilder("package transfer;\nimport java.math.BigInteger;\nfinal class Selected {\n");
        for (int bits : List.of(256, 2048, 8192)) {
            append(source, "convolution" + bits, bits, convolution);
        }
        append(source, "fp2", 381, complex);
        Files.writeString(Path.of(args[0]), source.append("}\n"), StandardCharsets.UTF_8);
    }

    private static List<ExactRational> row(long... values) {
        return Arrays.stream(values).mapToObj(ExactRational::integer).toList();
    }

    private static void append(StringBuilder source, String name, int bits, BilinearProgram.Tensor tensor) {
        var sizes = List.of(bits, bits);
        var profile = new BilinearIntegerSchedule.Profile(sizes, sizes);
        var result = BilinearProgramSdk.selectInteger(tensor, 4, 5000, profile, BUDGET, CancellationToken.NONE);
        var selection = result.value().orElseThrow(() -> new IllegalStateException(name + ": " + result.reason()));
        var plan = selection.selected().schedule();
        System.out.printf("%s work=%d complete=%s subsets=%d verified=%d%nselected=%s%ndirect=%s%n",
            name, result.work(), selection.complete(), selection.examinedSubsets(), selection.verifiedCandidates(),
            plan.cost(), selection.direct().map(candidate -> candidate.schedule().cost()));
        var guarded = selection.preferredWithoutAllocationIncrease().orElseThrow().schedule();
        var direct = selection.direct().orElseThrow().schedule();
        boolean guardMatchesDirect = guarded.steps().equals(direct.steps()) && guarded.outputs().equals(direct.outputs());
        System.out.println("guarded=" + guarded.cost() + " guardedSameStepsAndSlotsAsDirect=" + guardMatchesDirect);
        var constants = new LinkedHashMap<BigInteger, String>();
        for (var step : plan.steps()) {
            if (step.op() != BilinearIntegerSchedule.Op.SCALE || constants.containsKey(step.coefficient())) continue;
            String symbol = name + "Factor" + constants.size();
            constants.put(step.coefficient(), symbol);
            source.append("    private static final BigInteger ").append(symbol)
                .append(" = new BigInteger(\"").append(step.coefficient()).append("\");\n");
        }
        source.append("    static BigInteger[] ").append(name).append("(BigInteger[] x, BigInteger[] y) {\n");
        int index = plan.zeroSlot() + 1;
        for (var step : plan.steps()) {
            String left = slot(step.left(), tensor);
            String expression = switch (step.op()) {
                case ADD -> left + ".add(" + slot(step.right(), tensor) + ")";
                case SUBTRACT -> left + ".subtract(" + slot(step.right(), tensor) + ")";
                case MULTIPLY -> left + ".multiply(" + slot(step.right(), tensor) + ")";
                case NEGATE -> left + ".negate()";
                case SCALE -> left + ".multiply(" + constants.get(step.coefficient()) + ")";
            };
            source.append("        var v").append(index++).append(" = ").append(expression).append(";\n");
        }
        var outputs = new StringJoiner(", ", "        return new BigInteger[] {", "};\n    }\n");
        for (int output : plan.outputs()) outputs.add(slot(output, tensor));
        source.append(outputs);
    }

    private static String slot(int i, BilinearProgram.Tensor tensor) {
        if (i < tensor.leftSize()) return "x[" + i + "]";
        int total = tensor.leftSize() + tensor.rightSize();
        if (i < total) return "y[" + (i - tensor.leftSize()) + "]";
        return i == total ? "BigInteger.ZERO" : "v" + i;
    }
}
