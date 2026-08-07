package de.regelsuche.math.algorithms.equivalence;

import de.regelsuche.equivalence.PolynomialEquivalenceService;
import de.regelsuche.validation.MathematicalAlgorithmRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Gröbner-basis service for small polynomial ideals/systems over exact rational coefficients.
 *
 * <p>This class is intentionally separate from {@link PolynomialNormalFormEquivalenceService}: normal forms prove
 * direct polynomial identities, while this service computes a basis for generators and reduces a polynomial modulo
 * the generated ideal. If a concrete external backend such as JAS is requested but unavailable, the service reports
 * {@code UNAVAILABLE} instead of falling back to normal-form equivalence.
 */
public class GroebnerBasisEquivalenceService implements PolynomialEquivalenceService {
    private static final int DEFAULT_BASIS_CACHE_CAPACITY = 128;
    private static final MathematicalAlgorithmRegistry.AlgorithmBudget SAFE_DEFAULT_BUDGET =
        MathematicalAlgorithmRegistry.AlgorithmBudget.bounded(200, 1_000, 0, 0.0, 256, 20, 8);
    private final PolynomialArithmetic arithmetic = new PolynomialArithmetic();
    private final MathematicalAlgorithmRegistry registry;
    private final boolean jasBackendAvailable;
    private final MonomialOrder monomialOrder;
    private final GroebnerBasisEngine engine;
    private final BasisCache basisCache;
    private MathematicalAlgorithmRegistry.AlgorithmExecutionResult lastResult =
        MathematicalAlgorithmRegistry.AlgorithmExecutionResult.unknown("not executed");

    public GroebnerBasisEquivalenceService(MathematicalAlgorithmRegistry registry) {
        this(registry, false);
    }

    public GroebnerBasisEquivalenceService(MathematicalAlgorithmRegistry registry, boolean jasBackendAvailable) {
        this(registry, jasBackendAvailable, new GradedReverseLexOrder());
    }

    public GroebnerBasisEquivalenceService(
        MathematicalAlgorithmRegistry registry,
        boolean jasBackendAvailable,
        MonomialOrder monomialOrder
    ) {
        this(registry, jasBackendAvailable, monomialOrder, DEFAULT_BASIS_CACHE_CAPACITY);
    }

    public GroebnerBasisEquivalenceService(
        MathematicalAlgorithmRegistry registry,
        boolean jasBackendAvailable,
        MonomialOrder monomialOrder,
        int basisCacheCapacity
    ) {
        if (basisCacheCapacity < 0) {
            throw new IllegalArgumentException("basisCacheCapacity must be non-negative");
        }
        this.registry = registry;
        this.jasBackendAvailable = jasBackendAvailable;
        this.monomialOrder = monomialOrder;
        this.engine = new GroebnerBasisEngine(monomialOrder);
        this.basisCache = new BasisCache(basisCacheCapacity);
    }

    public MathematicalAlgorithmRegistry.AlgorithmExecutionResult lastResult() {
        return lastResult;
    }

    @Override
    public boolean arePolynomiallyEquivalent(String leftPolynomial, String rightPolynomial) {
        lastResult = MathematicalAlgorithmRegistry.AlgorithmExecutionResult.unknown(
            "Gröbner equivalence requires explicit ideal generators; use reducesToZeroModuloIdeal");
        return false;
    }

    @Override
    public String evidence(String leftExpression, String rightExpression) {
        arePolynomiallyEquivalent(leftExpression, rightExpression);
        return lastResult.detail();
    }

    public boolean reducesToZeroModuloIdeal(String polynomialExpression, List<String> generatorExpressions) {
        Optional<String> remainder = normalFormModuloIdeal(polynomialExpression, generatorExpressions);
        return remainder.isPresent() && "0".equals(remainder.orElseThrow());
    }

    public Optional<String> normalFormModuloIdeal(String polynomialExpression, List<String> generatorExpressions) {
        if (!registry.isEnabled(MathematicalAlgorithmRegistry.GROEBNER_BASIS)) {
            lastResult = MathematicalAlgorithmRegistry.AlgorithmExecutionResult.disabled(
                "groebnerBasis must be enabled for ideal reduction");
            return Optional.empty();
        }
        if (registry.isEnabled(MathematicalAlgorithmRegistry.JAS_BACKEND) && !jasBackendAvailable) {
            lastResult = MathematicalAlgorithmRegistry.AlgorithmExecutionResult.unavailable(
                "jasBackend is enabled, but no JAS Gröbner adapter is available");
            return Optional.empty();
        }
        if (generatorExpressions == null || generatorExpressions.isEmpty()) {
            lastResult = unsupported("ideal reduction requires at least one generator", "missing-generators");
            return Optional.empty();
        }

        Optional<Polynomial> polynomial = arithmetic.parse(polynomialExpression);
        if (polynomial.isEmpty()) {
            lastResult = unsupported("unsupported polynomial expression", "unsupported-polynomial-syntax");
            return Optional.empty();
        }

        List<Polynomial> generators = new ArrayList<>();
        for (String generatorExpression : generatorExpressions) {
            Optional<Polynomial> generator = arithmetic.parse(generatorExpression);
            if (generator.isEmpty()) {
                lastResult = unsupported("unsupported ideal generator", "unsupported-generator-syntax");
                return Optional.empty();
            }
            if (!generator.orElseThrow().isZero()) {
                generators.add(generator.orElseThrow());
            }
        }
        if (generators.isEmpty()) {
            lastResult = unsupported("ideal generators reduce to zero", "zero-generators");
            return Optional.empty();
        }

        MathematicalAlgorithmRegistry.AlgorithmBudget budget = registry.find(MathematicalAlgorithmRegistry.GROEBNER_BASIS)
            .map(MathematicalAlgorithmRegistry.AlgorithmDescriptor::budget)
            .orElse(SAFE_DEFAULT_BUDGET);
        Optional<String> unsupportedReason = unsupportedByHardLimits(polynomial.orElseThrow(), generators, budget);
        if (unsupportedReason.isPresent()) {
            lastResult = unsupported("polynomial outside configured Gröbner limits", unsupportedReason.orElseThrow());
            return Optional.empty();
        }

        IdealCacheKey cacheKey = idealCacheKey(generators);
        long coldPairUpperBound = pairCount(cacheKey.canonicalGenerators().size());
        GroebnerBasisEngine.BasisPreparation preparation = basisCache.get(cacheKey);
        ReuseDecision reuseDecision;
        boolean basisCacheHit = preparation != null;
        if (basisCacheHit) {
            reuseDecision = new ReuseDecision("EXACT_CACHE", 0, 0, coldPairUpperBound, "");
        } else {
            CachedBasis cachedSubset = basisCache.findBestExtensionBase(cacheKey);
            if (cachedSubset == null) {
                preparation = engine.prepareIdeal(generators, budget.maxSteps(), budget.maxStates());
                reuseDecision = new ReuseDecision("COLD", 0, 0, coldPairUpperBound, "no-cached-subset");
            } else if (cachedSubset.pairUpperBound() <= coldPairUpperBound) {
                List<Polynomial> additionalGenerators = additionalGenerators(generators, cachedSubset.key());
                preparation = engine.extendIdeal(
                    cachedSubset.preparation(),
                    additionalGenerators,
                    budget.maxSteps(),
                    budget.maxStates()
                );
                reuseDecision = new ReuseDecision(
                    "INCREMENTAL_EXTENSION",
                    cachedSubset.key().canonicalGenerators().size(),
                    cachedSubset.pairUpperBound(),
                    coldPairUpperBound,
                    ""
                );
            } else {
                preparation = engine.prepareIdeal(generators, budget.maxSteps(), budget.maxStates());
                reuseDecision = new ReuseDecision(
                    "COLD",
                    0,
                    cachedSubset.pairUpperBound(),
                    coldPairUpperBound,
                    "incremental-pair-upper-bound"
                );
            }
            if (!preparation.budgetExhausted()) {
                basisCache.put(cacheKey, preparation);
            }
        }

        GroebnerBasisEngine.EngineResult result = engine.normalFormModuloPreparedIdeal(
            polynomial.orElseThrow(),
            preparation,
            budget.maxSteps(),
            basisCacheHit
        );
        if (result.budgetExhausted()) {
            lastResult = new MathematicalAlgorithmRegistry.AlgorithmExecutionResult(
                MathematicalAlgorithmRegistry.ExecutionStatus.BUDGET_EXHAUSTED,
                MathematicalAlgorithmRegistry.ResultType.DIAGNOSTIC,
                "Gröbner basis computation exceeded budget",
                resultPayload(result, reuseDecision)
            );
            return Optional.empty();
        }

        boolean zero = result.remainder().isZero();
        lastResult = new MathematicalAlgorithmRegistry.AlgorithmExecutionResult(
            MathematicalAlgorithmRegistry.ExecutionStatus.SUCCESS,
            zero ? MathematicalAlgorithmRegistry.ResultType.PROOF : MathematicalAlgorithmRegistry.ResultType.REFUTATION,
            zero ? "polynomial reduces to 0 modulo Gröbner basis" : "non-zero remainder modulo Gröbner basis",
            resultPayload(result, reuseDecision)
        );
        return Optional.of(result.remainder().toCanonicalString(monomialOrder));
    }

    private IdealCacheKey idealCacheKey(List<Polynomial> generators) {
        List<String> canonicalGenerators = generators.stream()
            .filter(generator -> !generator.isZero())
            .map(generator -> generator.monic(monomialOrder).toCanonicalString(monomialOrder))
            .distinct()
            .sorted()
            .toList();
        return new IdealCacheKey(monomialOrder.name(), canonicalGenerators);
    }

    private List<Polynomial> additionalGenerators(List<Polynomial> generators, IdealCacheKey cachedKey) {
        Set<String> cachedGenerators = new HashSet<>(cachedKey.canonicalGenerators());
        Map<String, Polynomial> byCanonicalGenerator = new TreeMap<>();
        for (Polynomial generator : generators) {
            if (generator.isZero()) {
                continue;
            }
            Polynomial monic = generator.monic(monomialOrder);
            byCanonicalGenerator.putIfAbsent(monic.toCanonicalString(monomialOrder), monic);
        }
        List<Polynomial> additional = new ArrayList<>();
        for (Map.Entry<String, Polynomial> entry : byCanonicalGenerator.entrySet()) {
            if (!cachedGenerators.contains(entry.getKey())) {
                additional.add(entry.getValue());
            }
        }
        return List.copyOf(additional);
    }

    private Map<String, Object> resultPayload(
        GroebnerBasisEngine.EngineResult result,
        ReuseDecision reuseDecision
    ) {
        Map<String, Object> payload = basePayload("");
        payload.put("basis", result.basis().stream().map(polynomial -> polynomial.toCanonicalString(monomialOrder)).toList());
        payload.put("reducedBasis", result.reducedBasis().stream()
            .map(polynomial -> polynomial.toCanonicalString(monomialOrder))
            .toList());
        payload.put("reducedBasisStatus", result.reducedBasisStatus());
        payload.put("remainder", result.remainder().toCanonicalString(monomialOrder));
        payload.put("monomialOrder", result.monomialOrder());
        payload.put("pairSelectionStrategy", "lcm-total-degree");
        payload.put("buchbergerCriteria", List.of("product", "chain"));
        payload.put("pairsConsidered", result.pairsConsidered());
        payload.put("pairsReduced", result.pairsReduced());
        payload.put("pairsSkippedByProductCriterion", result.pairsSkippedByProductCriterion());
        payload.put("pairsSkippedByChainCriterion", result.pairsSkippedByChainCriterion());
        payload.put("maxPendingPairs", result.maxPendingPairs());
        payload.put("basisCacheHit", result.basisCacheHit());
        payload.put("basisReuseMode", reuseDecision.mode());
        payload.put("basisCacheSize", basisCache.size());
        payload.put("basisCacheCapacity", basisCache.capacity());
        payload.put("incrementalBaseGeneratorCount", reuseDecision.baseGeneratorCount());
        payload.put("incrementalBaseSize", result.incrementalBaseSize());
        payload.put("incrementalCandidatePairUpperBound", reuseDecision.incrementalPairUpperBound());
        payload.put("coldInitialPairUpperBound", reuseDecision.coldPairUpperBound());
        payload.put("incrementalReuseRejectedReason", reuseDecision.rejectionReason());
        payload.put("extensionGeneratorsConsidered", result.extensionGeneratorsConsidered());
        payload.put("extensionGeneratorsReduced", result.extensionGeneratorsReduced());
        payload.put("extensionGeneratorsEliminated", result.extensionGeneratorsEliminated());
        payload.put("basisPreparationSteps", result.basisPreparationSteps());
        payload.put("basisPreparationStepsSaved", result.basisPreparationStepsSaved());
        payload.put("queryReductionSteps", result.queryReductionSteps());
        payload.put("interreductionSteps", result.interreductionSteps());
        payload.put("steps", result.steps());
        payload.put("budgetStatus", result.budgetStatus());
        return Map.copyOf(payload);
    }

    private Optional<String> unsupportedByHardLimits(
        Polynomial polynomial,
        List<Polynomial> generators,
        MathematicalAlgorithmRegistry.AlgorithmBudget budget
    ) {
        List<Polynomial> all = new ArrayList<>();
        all.add(polynomial);
        all.addAll(generators);
        int termCount = all.stream().mapToInt(Polynomial::termCount).sum();
        if (termCount > budget.maxTerms()) {
            return Optional.of("maxTerms");
        }
        int degree = all.stream().mapToInt(Polynomial::totalDegree).max().orElse(0);
        if (degree > budget.maxDegree()) {
            return Optional.of("maxDegree");
        }
        Set<String> variables = new TreeSet<>();
        all.forEach(value -> variables.addAll(value.variables()));
        if (variables.size() > budget.maxVariables()) {
            return Optional.of("maxVariables");
        }
        if (budget.maxCoefficient() > 0 && all.stream().anyMatch(value -> value.coefficientMagnitudeExceeds(budget.maxCoefficient()))) {
            return Optional.of("maxCoefficient");
        }
        return Optional.empty();
    }

    private MathematicalAlgorithmRegistry.AlgorithmExecutionResult unsupported(String detail, String reason) {
        return new MathematicalAlgorithmRegistry.AlgorithmExecutionResult(
            MathematicalAlgorithmRegistry.ExecutionStatus.UNKNOWN,
            MathematicalAlgorithmRegistry.ResultType.DIAGNOSTIC,
            detail,
            basePayload(reason)
        );
    }

    private Map<String, Object> basePayload(String unsupportedReason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("capability", MathematicalAlgorithmRegistry.GROEBNER_BASIS);
        boolean jasRequested = registry.isEnabled(MathematicalAlgorithmRegistry.JAS_BACKEND);
        payload.put("requestedBackend", jasRequested ? "jas" : "pureJavaSmallGroebner");
        payload.put("backend", "pureJavaSmallGroebner");
        MathematicalAlgorithmRegistry.AlgorithmBudget budget = registry.find(MathematicalAlgorithmRegistry.GROEBNER_BASIS)
            .map(MathematicalAlgorithmRegistry.AlgorithmDescriptor::budget)
            .orElse(SAFE_DEFAULT_BUDGET);
        payload.put("maxTerms", budget.maxTerms());
        payload.put("maxDegree", budget.maxDegree());
        payload.put("maxVariables", budget.maxVariables());
        payload.put("maxPairs", registry.find(MathematicalAlgorithmRegistry.GROEBNER_BASIS)
            .map(MathematicalAlgorithmRegistry.AlgorithmDescriptor::budget)
            .map(MathematicalAlgorithmRegistry.AlgorithmBudget::maxStates)
            .orElse(SAFE_DEFAULT_BUDGET.maxStates()));
        payload.put("basisCacheCapacity", basisCache.capacity());
        payload.put("timeoutMillis", 0);
        payload.put("unsupportedReason", unsupportedReason == null ? "" : unsupportedReason);
        return payload;
    }

    private static long pairCount(int count) {
        return count < 2 ? 0 : ((long) count * (count - 1)) / 2;
    }

    private static long incrementalPairUpperBound(int basisSize, int additionalGeneratorCount) {
        return (long) basisSize * additionalGeneratorCount + pairCount(additionalGeneratorCount);
    }

    private record IdealCacheKey(String monomialOrder, List<String> canonicalGenerators) {
        private IdealCacheKey {
            canonicalGenerators = List.copyOf(canonicalGenerators);
        }
    }

    private record CachedBasis(
        IdealCacheKey key,
        GroebnerBasisEngine.BasisPreparation preparation,
        long pairUpperBound
    ) {
    }

    private record ReuseDecision(
        String mode,
        int baseGeneratorCount,
        long incrementalPairUpperBound,
        long coldPairUpperBound,
        String rejectionReason
    ) {
    }

    private static final class BasisCache {
        private final int capacity;
        private final LinkedHashMap<IdealCacheKey, GroebnerBasisEngine.BasisPreparation> entries =
            new LinkedHashMap<>(16, 0.75f, true);

        private BasisCache(int capacity) {
            this.capacity = capacity;
        }

        private synchronized GroebnerBasisEngine.BasisPreparation get(IdealCacheKey key) {
            return entries.get(key);
        }

        private synchronized CachedBasis findBestExtensionBase(IdealCacheKey target) {
            IdealCacheKey bestKey = null;
            GroebnerBasisEngine.BasisPreparation bestPreparation = null;
            long bestPairUpperBound = Long.MAX_VALUE;
            for (Map.Entry<IdealCacheKey, GroebnerBasisEngine.BasisPreparation> entry : entries.entrySet()) {
                IdealCacheKey candidate = entry.getKey();
                if (!candidate.monomialOrder().equals(target.monomialOrder())) {
                    continue;
                }
                if (candidate.canonicalGenerators().size() >= target.canonicalGenerators().size()) {
                    continue;
                }
                if (!isSortedSubset(candidate.canonicalGenerators(), target.canonicalGenerators())) {
                    continue;
                }
                int additionalGeneratorCount = target.canonicalGenerators().size()
                    - candidate.canonicalGenerators().size();
                long pairUpperBound = incrementalPairUpperBound(
                    entry.getValue().basis().size(),
                    additionalGeneratorCount
                );
                if (bestKey == null
                    || pairUpperBound < bestPairUpperBound
                    || (pairUpperBound == bestPairUpperBound
                        && candidate.canonicalGenerators().size() > bestKey.canonicalGenerators().size())) {
                    bestKey = candidate;
                    bestPreparation = entry.getValue();
                    bestPairUpperBound = pairUpperBound;
                }
            }
            if (bestKey == null) {
                return null;
            }
            entries.get(bestKey);
            return new CachedBasis(bestKey, bestPreparation, bestPairUpperBound);
        }

        private boolean isSortedSubset(List<String> candidate, List<String> target) {
            int candidateIndex = 0;
            int targetIndex = 0;
            while (candidateIndex < candidate.size() && targetIndex < target.size()) {
                int comparison = candidate.get(candidateIndex).compareTo(target.get(targetIndex));
                if (comparison == 0) {
                    candidateIndex++;
                    targetIndex++;
                } else if (comparison > 0) {
                    targetIndex++;
                } else {
                    return false;
                }
            }
            return candidateIndex == candidate.size();
        }

        private synchronized void put(IdealCacheKey key, GroebnerBasisEngine.BasisPreparation preparation) {
            if (capacity == 0) {
                return;
            }
            entries.put(key, preparation);
            while (entries.size() > capacity) {
                IdealCacheKey eldest = entries.keySet().iterator().next();
                entries.remove(eldest);
            }
        }

        private synchronized int size() {
            return entries.size();
        }

        private int capacity() {
            return capacity;
        }
    }
}
