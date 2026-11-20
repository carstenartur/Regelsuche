package de.regelsuche.web;

import de.regelsuche.ast.iteration.PairReduction;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.math.algorithms.combinatorics.BipartiteMatching;
import de.regelsuche.math.algorithms.combinatorics.BipartiteMatching.Edge;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.iteration.PairReductionSearch;
import de.regelsuche.math.algorithms.modular.MontgomeryReductionPlanner;
import de.regelsuche.math.algorithms.modular.MontgomeryReductionPlanner.Product;
import de.regelsuche.math.algorithms.modular.MontgomerySchedule;
import de.regelsuche.math.algorithms.modular.MontgomerySchedule.Input;
import de.regelsuche.math.algorithms.modular.MontgomerySchedule.Interval;
import de.regelsuche.math.algorithms.modular.MontgomerySchedule.Observation;
import de.regelsuche.math.algorithms.modular.MontgomerySchedule.Operation;
import de.regelsuche.math.algorithms.modular.MontgomerySchedule.Parameters;
import de.regelsuche.math.algorithms.modular.MontgomerySchedule.Schedule;
import de.regelsuche.math.algorithms.modular.MontgomerySchedule.Step;
import de.regelsuche.math.algorithms.modular.MontgomerySchedule.Word;
import de.regelsuche.symbol.SymbolScope;
import de.regelsuche.symbol.SymbolicExpression;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

/** Fixed inputs for live, source-bound checks in the Workbench. */
final class CertificateDemoWorkbench {
    private static final IterationLimits LIMITS = new IterationLimits(1_000_000, 128, 5_000);
    private static final BooleanSupplier RUN = () -> false;
    private static final int DISTANCE_ROWS = 100_000;
    private static final int DISTANCE_FIRST = -50_000;
    private static final int PREFIX_ROWS = 64;

    private CertificateDemoWorkbench() {}

    static String hall() {
        var source = new BipartiteMatching.Request(4, 4, List.of(new Edge(0, 0), new Edge(1, 0),
            new Edge(2, 0), new Edge(3, 1), new Edge(3, 2), new Edge(3, 3)));
        var result = BipartiteMatching.search(source, LIMITS, RUN);
        if (result.certificate().isEmpty()) return unavailable(result.reason());

        var certificate = result.certificate().orElseThrow();
        var verification = BipartiteMatching.verify(source, certificate, LIMITS, RUN);
        if (!verification.valid()) return unavailable(verification.reason());

        var witness = new HashSet<>(certificate.witness());
        var neighbors = source.edges().stream().filter(edge -> witness.contains(edge.left()))
            .map(Edge::right).distinct().sorted().toList();
        return verified().property("leftCount", source.leftCount()).property("rightCount", source.rightCount())
            .array("edges", array -> source.edges().forEach(edge -> writeEdge(array, edge)))
            .array("matching", array -> certificate.matching().forEach(edge -> writeEdge(array, edge)))
            .array("witness", array -> certificate.witness().forEach(array::value))
            .array("neighbors", array -> neighbors.forEach(array::value))
            .property("countDeficit", Math.max(0, source.leftCount() - source.rightCount()))
            .property("hallDeficit", certificate.deficiency())
            .property("searchWork", result.work()).property("verificationWork", verification.work())
            .endObject().toString();
    }

    static String montgomery() {
        var parameters = new Parameters(16, 3_329, 62_209);
        var leftRange = new Interval(-100, 100);
        var rightRange = new Interval(-200, 200);
        var inputs = new ArrayList<Input>();
        var products = new ArrayList<Product>();
        for (int index = 1; index <= 4; index++) {
            int left = inputs.size();
            inputs.add(new Input("a" + index, Word.SHORT, leftRange));
            inputs.add(new Input("b" + index, Word.SHORT, rightRange));
            products.add(new Product(left, left + 1));
        }

        var observation = Observation.CANONICAL_RESIDUE;
        var source = directMontgomerySource(inputs.size(), products);
        var result = MontgomeryReductionPlanner.dotProduct(parameters, inputs, products, Word.INT, observation,
            LIMITS, RUN);
        if (result.certificate().isEmpty()) return unavailable(result.reason());

        var certificate = result.certificate().orElseThrow();
        var target = certificate.request().target();
        // Only the target is taken from the proposal. The input, baseline and observation belong to the demo.
        var expected = new MontgomerySchedule.Request(parameters, inputs, source, target, observation);
        var verification = MontgomerySchedule.verify(expected, certificate, LIMITS, RUN);
        if (!verification.valid()) return unavailable(verification.reason());

        int sourceOutput = source.outputs().getFirst();
        int targetOutput = target.outputs().getFirst();
        int accumulator = target.steps().get(targetOutput - inputs.size()).operands().getFirst();
        return verified().property("modulus", parameters.modulus()).property("radixBits", parameters.radixBits())
            .property("inverse", parameters.inverse()).property("productCount", products.size())
            .property("residueBits", parameters.residueWord().bits())
            .property("accumulatorBits", parameters.accumulatorWord().bits())
            .property("observation", observation.name())
            .property("sourceReductions", certificate.sourceReductions())
            .property("targetReductions", certificate.targetReductions())
            .array("leftRange", array -> writeInterval(array, leftRange))
            .array("rightRange", array -> writeInterval(array, rightRange))
            .array("sourceOutputRange", array -> writeInterval(array, certificate.sourceRanges().get(sourceOutput)))
            .array("targetAccumulatorRange",
                array -> writeInterval(array, certificate.targetRanges().get(accumulator)))
            .array("targetOutputRange", array -> writeInterval(array, certificate.targetRanges().get(targetOutput)))
            .property("planningWork", result.work()).property("verificationWork", verification.work())
            .endObject().toString();
    }

    private static Schedule directMontgomerySource(int inputCount, List<Product> products) {
        var steps = new ArrayList<Step>();
        int sum = -1;
        for (var product : products) {
            int productReference = inputCount + steps.size();
            steps.add(Step.apply(Operation.MULTIPLY, Word.INT, product.left(), product.right()));
            int reduction = inputCount + steps.size();
            steps.add(Step.apply(Operation.MONTGOMERY, Word.SHORT, productReference));
            if (sum < 0) {
                sum = reduction;
            } else {
                steps.add(Step.apply(Operation.ADD, Word.INT, sum, reduction));
                sum = inputCount + steps.size() - 1;
            }
        }
        return new Schedule(steps, List.of(sum));
    }

    static String pairReduction() {
        var scope = new SymbolScope();
        var column = new PairReduction.Column(scope.declare("x_i"), scope.declare("x_j"));
        var source = new PairReduction(List.of(column), SymbolicExpression.parse("(x_i-x_j)^2", scope).expression());
        var result = PairReductionSearch.search(source, LIMITS, 3, RUN);
        if (result.verified().isEmpty()) return unavailable(result.reason());

        var checked = result.verified().orElseThrow();
        try {
            var evaluation = checked.evaluate(() -> distanceRows(DISTANCE_ROWS).iterator(), LIMITS, RUN);
            var prefix = distanceRows(PREFIX_ROWS).toList();
            var reducedPrefix = checked.evaluate(prefix, LIMITS, RUN);
            BigInteger directPrefix = BigInteger.ZERO;
            int directPairs = 0;
            for (int left = 0; left < prefix.size(); left++) {
                for (int right = left + 1; right < prefix.size(); right++) {
                    var difference = prefix.get(left).getFirst().subtract(prefix.get(right).getFirst());
                    directPrefix = directPrefix.add(difference.multiply(difference));
                    directPairs++;
                }
            }
            if (!reducedPrefix.value().equals(directPrefix)) return unavailable("PREFIX_MISMATCH");

            return verified().property("evaluatedRows", evaluation.rows()).property("value", evaluation.value().toString())
                .property("firstValue", DISTANCE_FIRST).property("lastValue", DISTANCE_FIRST + DISTANCE_ROWS - 1)
                .property("baselinePairs", PairReduction.originalPairs(evaluation.rows()).toString())
                .property("prefixRows", reducedPrefix.rows()).property("prefixPairs", directPairs)
                .property("prefixReducedValue", reducedPrefix.value().toString())
                .property("prefixDirectValue", directPrefix.toString())
                .property("evaluationWork", evaluation.work())
                .endObject().toString();
        } catch (IllegalArgumentException resourceStop) {
            return unavailable(resourceStop.getMessage());
        }
    }

    private static java.util.stream.Stream<List<BigInteger>> distanceRows(int count) {
        return IntStream.range(0, count).mapToObj(index -> List.of(BigInteger.valueOf(DISTANCE_FIRST + index)));
    }

    private static void writeEdge(JsonWriter array, Edge edge) {
        array.arrayValue(pair -> pair.value(edge.left()).value(edge.right()));
    }

    private static void writeInterval(JsonWriter array, Interval range) {
        array.value(Math.toIntExact(range.minimum())).value(Math.toIntExact(range.maximum()));
    }

    private static JsonWriter verified() {
        return new JsonWriter().beginObject().property("status", "VERIFIED");
    }

    private static String unavailable(String reason) {
        return new JsonWriter().beginObject().property("status", "UNAVAILABLE").property("reason", reason)
            .endObject().toString();
    }
}
