package de.regelsuche.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import de.regelsuche.ast.iteration.AffineReduction;
import de.regelsuche.math.algorithms.iteration.AffineReductionPlan;
import de.regelsuche.math.algorithms.iteration.AffineReductionPlan.Shape;
import de.regelsuche.math.algorithms.iteration.AffineReductionReference;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Comparison fixtures and subprocess adapter for the affine source fragment. */
final class AffineAlphaExperiment {
    static final String SCHEMA = "regelsuche.external-polyhedral-affine/v2";
    private static final IterationLimits ORACLE_LIMITS = new IterationLimits(3_000_000, 128, 5_000);

    private AffineAlphaExperiment() {}

    record Input(String name, Optional<AffineReduction> affine, Optional<ExternalPolyhedralComparison.Imported> prefix) {
        static Input of(AlphaAffineReductionParser.Imported imported) {
            return new Input(imported.name(), Optional.of(imported.source()), Optional.empty());
        }

        static Input of(ExternalPolyhedralComparison.Imported imported) {
            return new Input(imported.name(), Optional.empty(), Optional.of(imported));
        }

        List<BigInteger> oracle(int count, int seed) {
            if (prefix.isPresent()) return ExternalPolyhedralComparison.oracle(prefix.orElseThrow(), count, seed);
            var source = affine.orElseThrow();
            var shape = AffineReductionPlan.prepareShape(source, parameter(count), ORACLE_LIMITS, () -> false).shape();
            return AffineReductionReference.evaluate(source, parameter(count), values(shape, seed),
                ORACLE_LIMITS, () -> false).values();
        }

        String driver() {
            return affine.isPresent()
                ? AlphaCOutputDriver.source(name, affine.orElseThrow().outputRank()) : ExternalPolyhedralComparison.driver(name);
        }

        List<String> command(Path binary, int count, int seed) {
            if (affine.isEmpty()) return List.of(binary.toString(), Integer.toString(count), Integer.toString(seed));
            var shape = AffineReductionPlan.prepareShape(affine.orElseThrow(), parameter(count), ORACLE_LIMITS, () -> false).shape();
            return AlphaCOutputDriver.command(binary, parameter(count), seed, shape.inputSize(), shape.outputCoordinates());
        }
    }

    static Optional<Input> importSource(String source, boolean affine) {
        if (affine) {
            try {
                return Optional.of(Input.of(AlphaAffineReductionParser.parse(source)));
            } catch (IllegalArgumentException unsupported) {
                // Polynomial prefix proposals still take the existing checked path.
            }
        }
        try {
            return Optional.of(Input.of(ExternalPolyhedralComparison.parse(source)));
        } catch (IllegalArgumentException unsupported) {
            return Optional.empty();
        }
    }

    static Map<String, Object> nativeRun(String source, JsonNode request) {
        long started = System.nanoTime();
        AlphaAffineReductionParser.Imported imported;
        try {
            imported = AlphaAffineReductionParser.parse(source);
        } catch (IllegalArgumentException unsupported) {
            var prefixResponse = ExternalPolyhedralComparison.nativeRun(source, request);
            prefixResponse.put("nativeWallNanos", System.nanoTime() - started);
            if ("UNSUPPORTED".equals(prefixResponse.get("status")))
                prefixResponse.put("affineReason", unsupported.getMessage());
            return prefixResponse;
        }
        var response = new LinkedHashMap<String, Object>();
        try {
            var budget = request.path("limits");
            var limits = new IterationLimits(budget.path("nativeWork").longValue(), budget.path("nativeTerms").intValue(),
                budget.path("synthesisSeconds").longValue() * 1000);
            var evaluation = evaluate(imported.source(), request, limits);
            response.put("outputs", evaluation.outputs());
            response.put("planPreparationWork", evaluation.preparationWork());
            response.put("fixturePreparationWork", evaluation.fixtureWork());
            response.put("evaluationWork", evaluation.evaluationWork());
            response.put("evaluationNanos", evaluation.evaluationNanos());
            response.put("totalNativeWork", evaluation.preparationWork() + evaluation.fixtureWork() + evaluation.evaluationWork());
            response.put("status", "CANDIDATE");
        } catch (IllegalArgumentException failure) {
            response.put("status", "ERROR");
            response.put("reason", failure.getMessage());
        } finally {
            response.put("nativeWallNanos", System.nanoTime() - started);
        }
        return response;
    }

    private static Batch evaluate(AffineReduction source, JsonNode request, IterationLimits limits) {
        var prepared = AffineReductionPlan.prepare(source, limits, () -> false);
        long fixtureWork = 0, evaluationWork = 0;
        var outputs = new ArrayList<Map<String, Object>>();
        long evaluationNanos = 0;
        for (var countNode : request.path("rowCounts")) {
            int count = countNode.intValue();
            int parameter = parameter(count);
            var shape = AffineReductionPlan.prepareShape(source, parameter,
                remaining(limits, prepared.work() + fixtureWork + evaluationWork), () -> false);
            fixtureWork += shape.work();
            for (var seedNode : request.path("seeds")) {
                int seed = seedNode.intValue();
                fixtureWork += shape.shape().inputSize();
                var budgetLeft = remaining(limits, prepared.work() + fixtureWork + evaluationWork);
                var input = values(shape.shape(), seed);
                long evaluationStarted = System.nanoTime();
                var result = prepared.plan().evaluate(parameter, input, budgetLeft, () -> false);
                evaluationNanos += System.nanoTime() - evaluationStarted;
                evaluationWork += result.work();
                outputs.add(Map.of("rows", count, "seed", seed, "values",
                    result.values().stream().map(BigInteger::toString).toList()));
            }
        }
        return new Batch(outputs, prepared.work(), fixtureWork, evaluationWork, evaluationNanos);
    }

    private static IterationLimits remaining(IterationLimits limits, long used) {
        if (used >= limits.maximumWork()) throw new IllegalArgumentException("WORK_LIMIT");
        return limits.withMaximumWork(limits.maximumWork() - used);
    }

    private static int parameter(int count) {
        if (count < 2 || count > 20) throw new IllegalArgumentException("FIXTURE_OR_PARAMETER_DOMAIN_LIMIT");
        return count - 1;
    }

    private static List<BigInteger> values(Shape shape, int seed) {
        if (seed < 0 || seed > 2) throw new IllegalArgumentException("FIXTURE_OR_PARAMETER_DOMAIN_LIMIT");
        var result = new ArrayList<BigInteger>();
        for (int i = 0; i < shape.inputSize(); i++)
            result.add(BigInteger.valueOf((i * 17L + seed * 7L) % 19L - 9L));
        return result;
    }

    private record Batch(List<Map<String, Object>> outputs, long preparationWork, long fixtureWork, long evaluationWork,
            long evaluationNanos) {}
}
