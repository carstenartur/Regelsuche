package de.regelsuche.sdk.optimization.iteration;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Emits a standalone Java method for a checked plan over finite, non-null integer rows. */
public final class PairReductionJava {
    private PairReductionJava() {}

    public static String emit(PairReductionVerifier.Verified checked) {
        Objects.requireNonNull(checked);
        var plan = checked.plan();
        var code = new StringBuilder("""
            import java.math.BigInteger;
            import java.util.List;

            public final class PairReductionProgram {
                public static BigInteger reduce(Iterable<? extends List<BigInteger>> rows) {
            """);
        var count = new PairReductionPlan.Moment(List.of());
        var names = new LinkedHashMap<PairReductionPlan.Moment, String>();
        if (plan.terms().stream().anyMatch(term -> term.factors().contains(count))) {
            names.put(count, "n");
            code.append("        BigInteger n = BigInteger.ZERO;\n");
        }
        var moments = plan.accumulators();
        for (int i = 0; i < moments.size(); i++) {
            String name = "s" + (i + 1);
            names.put(moments.get(i), name);
            code.append("        BigInteger ").append(name).append(" = BigInteger.ZERO;\n");
        }
        code.append("        for (var row : rows) {\n")
            .append("            if (row.size() != ").append(checked.source().columns().size()).append(") {\n")
            .append("                throw new IllegalArgumentException(\"wrong row width\");\n")
            .append("            }\n");
        if (names.containsKey(count)) {
            code.append("            n = n.add(BigInteger.ONE);\n");
        }
        moments.stream().flatMap(moment -> moment.columns().stream()).distinct().sorted().forEach(column ->
            code.append("            var x").append(column).append(" = row.get(").append(column).append(");\n"));
        for (var moment : moments) {
            String name = names.get(moment);
            var factors = moment.columns().stream().map(column -> "x" + column).toList();
            code.append("            ").append(name).append(" = ").append(name).append(".add(")
                .append(product(factors)).append(");\n");
        }
        code.append("        }\n        BigInteger result = BigInteger.ZERO;\n");
        for (var term : plan.terms()) {
            var coefficient = term.coefficient().abs();
            String value = product(term.factors().stream().map(names::get).toList());
            if (!coefficient.equals(BigInteger.ONE)) {
                value += ".multiply(new BigInteger(\"" + coefficient + "\"))";
            }
            code.append(term.coefficient().signum() > 0 ? "        result = result.add(" : "        result = result.subtract(")
                .append(value).append(");\n");
        }
        code.append(plan.divisor() == 1 ? "        return result;\n" : "        return result.divide(BigInteger.TWO);\n");
        return code.append("    }\n}\n").toString();
    }

    private static String product(List<String> factors) {
        return factors.getFirst() + factors.stream().skip(1)
            .map(factor -> ".multiply(" + factor + ")").collect(Collectors.joining());
    }
}
