from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def write(relative: str, content: str) -> None:
    path = ROOT / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def replace_once(relative: str, old: str, new: str) -> None:
    path = ROOT / relative
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise RuntimeError(f"expected source block not found: {relative}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


write(
    "regelsuche-core/src/main/java/de/regelsuche/polynomial/PolynomialWorkProjection.java",
    '''package de.regelsuche.polynomial;

import java.util.Objects;

/**
 * Converts cumulative raw instrumentation units for one stage into the work
 * units enforced by an owning execution authority.
 *
 * <p>The projection is stage-local and monotone. A budget retains the complete
 * raw ledger while charging only the projected delta caused by each raw entry.
 * This keeps evidence lossless without confusing an implementation multiplier
 * with a scientific work unit.</p>
 */
public interface PolynomialWorkProjection {
    /** Stable identity included in policy and result evidence. */
    String projectionId();

    /** Projects cumulative raw units for one named stage. */
    long projectStage(String stage, long cumulativeRawUnits);

    /** Returns the projected increment caused by adding raw units to a stage. */
    default long projectedDelta(
        String stage,
        long previousRawUnits,
        long addedRawUnits
    ) {
        String retainedStage = Objects.requireNonNull(stage, "stage");
        if (retainedStage.isBlank()
                || previousRawUnits < 0L
                || addedRawUnits < 0L) {
            throw new IllegalArgumentException(
                "polynomial work projection input is invalid"
            );
        }
        long nextRawUnits = Math.addExact(
            previousRawUnits,
            addedRawUnits
        );
        long previous = projectStage(retainedStage, previousRawUnits);
        long next = projectStage(retainedStage, nextRawUnits);
        if (previous < 0L || next < previous) {
            throw new IllegalArgumentException(
                "polynomial work projection must be non-negative and monotone"
            );
        }
        return Math.subtractExact(next, previous);
    }

    /** Raw one-for-one accounting used unless a caller declares a projection. */
    static PolynomialWorkProjection identity() {
        return Identity.INSTANCE;
    }

    enum Identity implements PolynomialWorkProjection {
        INSTANCE;

        private static final String ID =
            "regelsuche.polynomial-work-projection.identity/v1";

        @Override
        public String projectionId() {
            return ID;
        }

        @Override
        public long projectStage(String stage, long cumulativeRawUnits) {
            String retainedStage = Objects.requireNonNull(stage, "stage");
            if (retainedStage.isBlank() || cumulativeRawUnits < 0L) {
                throw new IllegalArgumentException(
                    "polynomial work projection input is invalid"
                );
            }
            return cumulativeRawUnits;
        }
    }
}
'''
)

write(
    "regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/polynomial/PolynomialWorkBudget.java",
    '''package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.PolynomialWorkProjection;
import de.regelsuche.polynomial.PolynomialWorkSink;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Shared non-resettable work budget for exact polynomial algorithm stages. */
final class PolynomialWorkBudget implements PolynomialWorkSink {
    private static final ThreadLocal<Deque<PolynomialWorkProjection>>
        ACTIVE_PROJECTIONS = ThreadLocal.withInitial(ArrayDeque::new);

    private final long limit;
    private final PolynomialWorkProjection projection;
    private final Map<String, Long> stages = new LinkedHashMap<>();
    private long projectedTotal;

    PolynomialWorkBudget(long limit) {
        this(limit, activeProjection());
    }

    PolynomialWorkBudget(
        long limit,
        PolynomialWorkProjection projection
    ) {
        if (limit < 1L) {
            throw new IllegalArgumentException(
                "polynomial work budget must be positive"
            );
        }
        this.limit = limit;
        this.projection = Objects.requireNonNull(
            projection,
            "projection"
        );
        String projectionId = Objects.requireNonNull(
            projection.projectionId(),
            "projectionId"
        );
        if (projectionId.isBlank()) {
            throw new IllegalArgumentException(
                "polynomial work projection identity must not be blank"
            );
        }
    }

    static <T> T withProjection(
        PolynomialWorkProjection projection,
        Supplier<T> action
    ) {
        var retainedProjection = Objects.requireNonNull(
            projection,
            "projection"
        );
        var retainedAction = Objects.requireNonNull(action, "action");
        Deque<PolynomialWorkProjection> stack = ACTIVE_PROJECTIONS.get();
        stack.push(retainedProjection);
        try {
            return retainedAction.get();
        } finally {
            PolynomialWorkProjection removed = stack.pop();
            if (removed != retainedProjection) {
                throw new IllegalStateException(
                    "polynomial work projection scope is corrupted"
                );
            }
            if (stack.isEmpty()) {
                ACTIVE_PROJECTIONS.remove();
            }
        }
    }

    private static PolynomialWorkProjection activeProjection() {
        Deque<PolynomialWorkProjection> stack = ACTIVE_PROJECTIONS.get();
        if (stack.isEmpty()) {
            ACTIVE_PROJECTIONS.remove();
            return PolynomialWorkProjection.identity();
        }
        return stack.peek();
    }

    long limit() {
        return limit;
    }

    long projectedWorkUnits() {
        return projectedTotal;
    }

    long remainingWorkUnits() {
        return limit - projectedTotal;
    }

    PolynomialWorkProjection projection() {
        return projection;
    }

    @Override
    public void consume(String stage, long units) {
        if (stage == null || stage.isBlank() || units < 0L) {
            throw new IllegalArgumentException(
                "polynomial work entry is invalid"
            );
        }
        if (units == 0L) {
            return;
        }
        long previousRawUnits = stages.getOrDefault(stage, 0L);
        long nextRawUnits = Math.addExact(previousRawUnits, units);
        long projectedDelta = projection.projectedDelta(
            stage,
            previousRawUnits,
            units
        );
        if (projectedDelta > limit - projectedTotal) {
            throw new LimitReached();
        }
        projectedTotal = Math.addExact(projectedTotal, projectedDelta);
        stages.put(stage, nextRawUnits);
    }

    PolynomialWorkLedger ledger() {
        return new PolynomialWorkLedger(stages);
    }

    static final class LimitReached extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
'''
)

write(
    "regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/polynomial/NativeUnivariateFactorizationPolicy.java",
    '''package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.PolynomialWorkProjection;
import java.util.Objects;

/** Complete bounded policy for the native univariate Z[x]/Q[x] engine. */
public record NativeUnivariateFactorizationPolicy(
    UnivariateContentPolicy contentPolicy,
    SuitablePrimeSelectionPolicy suitablePrimePolicy,
    ZassenhausRecombinationPolicy recombinationPolicy,
    long maxEngineWorkUnits,
    PolynomialWorkProjection workProjection
) {
    /** Preserves the historical request-owned work authority. */
    public NativeUnivariateFactorizationPolicy(
        UnivariateContentPolicy contentPolicy,
        SuitablePrimeSelectionPolicy suitablePrimePolicy,
        ZassenhausRecombinationPolicy recombinationPolicy
    ) {
        this(
            contentPolicy,
            suitablePrimePolicy,
            recombinationPolicy,
            Long.MAX_VALUE,
            PolynomialWorkProjection.identity()
        );
    }

    public NativeUnivariateFactorizationPolicy(
        UnivariateContentPolicy contentPolicy,
        SuitablePrimeSelectionPolicy suitablePrimePolicy,
        ZassenhausRecombinationPolicy recombinationPolicy,
        long maxEngineWorkUnits
    ) {
        this(
            contentPolicy,
            suitablePrimePolicy,
            recombinationPolicy,
            maxEngineWorkUnits,
            PolynomialWorkProjection.identity()
        );
    }

    public NativeUnivariateFactorizationPolicy {
        Objects.requireNonNull(contentPolicy, "contentPolicy");
        Objects.requireNonNull(
            suitablePrimePolicy,
            "suitablePrimePolicy"
        );
        Objects.requireNonNull(
            recombinationPolicy,
            "recombinationPolicy"
        );
        workProjection = Objects.requireNonNull(
            workProjection,
            "workProjection"
        );
        String projectionId = Objects.requireNonNull(
            workProjection.projectionId(),
            "projectionId"
        );
        if (projectionId.isBlank()) {
            throw new IllegalArgumentException(
                "native engine work projection identity must not be blank"
            );
        }
        if (maxEngineWorkUnits < 1L) {
            throw new IllegalArgumentException(
                "native engine work authority must be positive"
            );
        }
    }

    public static NativeUnivariateFactorizationPolicy boundedDefaults() {
        FiniteFieldFactorizationPolicy finiteField =
            FiniteFieldFactorizationPolicy.deterministicBerlekamp(
                257,
                1_000_000
            );
        return new NativeUnivariateFactorizationPolicy(
            new UnivariateContentPolicy(65_536),
            SuitablePrimeSelectionPolicy.deterministicAscending(
                257,
                55,
                finiteField
            ),
            ZassenhausRecombinationPolicy.boundedDefaults()
        );
    }

    public NativeUnivariateFactorizationPolicy withMaxEngineWorkUnits(
        long maximum
    ) {
        return new NativeUnivariateFactorizationPolicy(
            contentPolicy,
            suitablePrimePolicy,
            recombinationPolicy,
            maximum,
            workProjection
        );
    }

    public NativeUnivariateFactorizationPolicy withWorkProjection(
        PolynomialWorkProjection projection
    ) {
        return new NativeUnivariateFactorizationPolicy(
            contentPolicy,
            suitablePrimePolicy,
            recombinationPolicy,
            maxEngineWorkUnits,
            projection
        );
    }

    public String canonicalMaterial() {
        StringBuilder result = new StringBuilder();
        AlgorithmEvidence.append(
            result,
            contentPolicy.canonicalMaterial()
        );
        AlgorithmEvidence.append(
            result,
            suitablePrimePolicy.canonicalMaterial()
        );
        AlgorithmEvidence.append(
            result,
            recombinationPolicy.canonicalMaterial()
        );
        if (maxEngineWorkUnits != Long.MAX_VALUE) {
            AlgorithmEvidence.append(
                result,
                "regelsuche.native-engine-work-authority/v1"
            );
            AlgorithmEvidence.append(
                result,
                Long.toString(maxEngineWorkUnits)
            );
        }
        if (!PolynomialWorkProjection.identity().projectionId().equals(
                workProjection.projectionId())) {
            AlgorithmEvidence.append(
                result,
                "regelsuche.native-engine-work-projection/v1"
            );
            AlgorithmEvidence.append(
                result,
                workProjection.projectionId()
            );
        }
        return result.toString();
    }
}
'''
)

pipeline_relative = (
    "regelsuche-math-algorithms/src/main/java/de/regelsuche/math/"
    "algorithms/polynomial/NativeUnivariateFactorizationPipeline.java"
)
pipeline_path = ROOT / pipeline_relative
pipeline = pipeline_path.read_text(encoding="utf-8")
if "factorUnderProjection(" not in pipeline:
    start = pipeline.index(
        "    static <C> FactorizationEngine.EngineResult<C> factor("
    )
    end = pipeline.index(
        "    private static <C> FactorizationEngine.EngineResult<C> execute("
    )
    replacement = '''    static <C> FactorizationEngine.EngineResult<C> factor(
        FactorizationRequest<C> request,
        NativeUnivariateFactorizationPolicy policy,
        NativeCoefficientAdapter<C> adapter
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(adapter, "adapter");
        return PolynomialWorkBudget.withProjection(
            policy.workProjection(),
            () -> factorUnderProjection(request, policy, adapter)
        );
    }

    private static <C> FactorizationEngine.EngineResult<C>
            factorUnderProjection(
                FactorizationRequest<C> request,
                NativeUnivariateFactorizationPolicy policy,
                NativeCoefficientAdapter<C> adapter
            ) {
        long engineWorkLimit = Math.min(
            request.maxWorkUnits(),
            policy.maxEngineWorkUnits()
        );
        // The request keeps its outer raw-evidence ceiling. The projected
        // backend authority is enforced solely by the shared budget below.
        FactorizationRequest<C> engineRequest = request;
        PolynomialWorkBudget work =
            new PolynomialWorkBudget(engineWorkLimit);
        ArrayList<String> certificates = new ArrayList<>();

        FactorizationEngine.EngineResult<C> rejected =
            rejectInput(request, policy, adapter, work);
        if (rejected != null) {
            return rejected;
        }

        try {
            return execute(
                request,
                engineRequest,
                policy,
                adapter,
                work,
                certificates
            );
        } catch (PolynomialWorkBudget.LimitReached exception) {
            return failure(
                request,
                policy,
                adapter,
                FactorizationEngine.Outcome.BUDGET_INCONCLUSIVE,
                "NATIVE_UNIVARIATE_WORK_BUDGET_EXCEEDED",
                work.ledger(),
                certificates
            );
        } catch (IntegerPolynomialArithmetic.LimitReached exception) {
            return failure(
                request,
                policy,
                adapter,
                FactorizationEngine.Outcome.BUDGET_INCONCLUSIVE,
                exception.detailCode(),
                work.ledger(),
                certificates
            );
        } catch (ArithmeticException exception) {
            return failure(
                request,
                policy,
                adapter,
                FactorizationEngine.Outcome.TECHNICAL_FAILURE,
                "NATIVE_UNIVARIATE_EXACT_ARITHMETIC_FAILED",
                work.ledger(),
                certificates
            );
        } catch (RuntimeException exception) {
            return failure(
                request,
                policy,
                adapter,
                FactorizationEngine.Outcome.TECHNICAL_FAILURE,
                "NATIVE_UNIVARIATE_"
                    + exception.getClass().getSimpleName()
                        .toUpperCase(java.util.Locale.ROOT),
                work.ledger(),
                certificates
            );
        }
    }

'''
    pipeline = pipeline[:start] + replacement + pipeline[end:]
old_remaining = '''        long remaining = request.maxWorkUnits()
            - work.ledger().totalWorkUnits();
        if (remaining < 1) {
            throw new PolynomialWorkBudget.LimitReached();
        }
        return remaining;'''
new_remaining = '''        long remaining = work.remainingWorkUnits();
        if (remaining < 1L) {
            throw new PolynomialWorkBudget.LimitReached();
        }
        return remaining;'''
if new_remaining not in pipeline:
    if old_remaining not in pipeline:
        raise RuntimeError("native remaining-work block not found")
    pipeline = pipeline.replace(old_remaining, new_remaining, 1)
pipeline_path.write_text(pipeline, encoding="utf-8")

canonical_relative = (
    "regelsuche-experiments/src/main/java/de/regelsuche/benchmark/"
    "polynomial/PolynomialTheoryUtilityCanonicalWorkProjection.java"
)
canonical_path = ROOT / canonical_relative
canonical = canonical_path.read_text(encoding="utf-8")
if "import de.regelsuche.polynomial.PolynomialWorkProjection;" not in canonical:
    canonical = canonical.replace(
        "import de.regelsuche.polynomial.PolynomialWorkLedger;\n",
        "import de.regelsuche.polynomial.PolynomialWorkLedger;\n"
        "import de.regelsuche.polynomial.PolynomialWorkProjection;\n",
        1,
    )
if "NATIVE_RUNTIME_PROJECTION" not in canonical:
    anchor = "    private static final long UTF8_EVIDENCE_QUANTUM = 64L;\n"
    if anchor not in canonical:
        raise RuntimeError("canonical UTF-8 quantum anchor not found")
    canonical = canonical.replace(
        anchor,
        anchor
        + "    private static final PolynomialWorkProjection\n"
        + "        NATIVE_RUNTIME_PROJECTION = new NativeRuntimeProjection();\n",
        1,
    )
constructor = '''    private PolynomialTheoryUtilityCanonicalWorkProjection() {
    }
'''
method = '''    private PolynomialTheoryUtilityCanonicalWorkProjection() {
    }

    /** Uses final-evidence stage quanta during native execution. */
    public static PolynomialWorkProjection nativeRuntimeProjection() {
        return NATIVE_RUNTIME_PROJECTION;
    }
'''
if "public static PolynomialWorkProjection nativeRuntimeProjection()" not in canonical:
    if constructor not in canonical:
        raise RuntimeError("canonical constructor anchor not found")
    canonical = canonical.replace(constructor, method, 1)
old_default = ": divideRoundUp(entry.getValue(), quantum(entry.getKey()));"
new_default = ": projectedStageUnits(entry.getKey(), entry.getValue());"
if new_default not in canonical:
    if old_default not in canonical:
        raise RuntimeError("canonical default projection expression not found")
    canonical = canonical.replace(old_default, new_default, 1)
if "static long projectedStageUnits(" not in canonical:
    quantum_anchor = "    private static long quantum(String stage) {"
    projected_method = '''    static long projectedStageUnits(
        String stage,
        long rawUnits
    ) {
        String retainedStage = Objects.requireNonNull(stage, "stage");
        if (retainedStage.isBlank() || rawUnits < 0L) {
            throw new IllegalArgumentException(
                "stage work projection input is invalid"
            );
        }
        return divideRoundUp(rawUnits, quantum(retainedStage));
    }

'''
    if quantum_anchor not in canonical:
        raise RuntimeError("canonical quantum method anchor not found")
    canonical = canonical.replace(
        quantum_anchor,
        projected_method + quantum_anchor,
        1,
    )
if "private static final class NativeRuntimeProjection" not in canonical:
    dimension_anchor = "    private enum Dimension {"
    projection_class = '''    private static final class NativeRuntimeProjection
            implements PolynomialWorkProjection {
        private static final String ID = REVISION + "/native-runtime";

        @Override
        public String projectionId() {
            return ID;
        }

        @Override
        public long projectStage(
            String stage,
            long cumulativeRawUnits
        ) {
            return projectedStageUnits(stage, cumulativeRawUnits);
        }
    }

'''
    if dimension_anchor not in canonical:
        raise RuntimeError("canonical dimension anchor not found")
    canonical = canonical.replace(
        dimension_anchor,
        projection_class + dimension_anchor,
        1,
    )
canonical_path.write_text(canonical, encoding="utf-8")

adapter_relative = (
    "app/src/main/java/de/regelsuche/benchmark/polynomial/"
    "PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.java"
)
adapter_path = ROOT / adapter_relative
adapter = adapter_path.read_text(encoding="utf-8")
old_engine = '''            var engine = NativeUnivariateFactorizationEngine.rationals(
                NativeUnivariateFactorizationPolicy.boundedDefaults()
                    .withMaxEngineWorkUnits(
                        occurrence.factorizationWork()
                    )
            );'''
new_engine = '''            var engine = NativeUnivariateFactorizationEngine.rationals(
                NativeUnivariateFactorizationPolicy.boundedDefaults()
                    .withWorkProjection(
                        PolynomialTheoryUtilityCanonicalWorkProjection
                            .nativeRuntimeProjection()
                    )
                    .withMaxEngineWorkUnits(
                        occurrence.factorizationWork()
                    )
            );'''
if new_engine not in adapter:
    if old_engine not in adapter:
        raise RuntimeError("adapter engine construction not found")
    adapter = adapter.replace(old_engine, new_engine, 1)
if "static TerminalStatus terminalStatus(\n        int transitionCount," not in adapter:
    start = adapter.index("    private static TerminalStatus terminalStatus(")
    end = adapter.index("    private static boolean unsupported(", start)
    terminal = '''    private static TerminalStatus terminalStatus(
        List<PolynomialTheoryUtilityTransitionOutcome> transitions,
        List<ExactNestedFactorizationTransformationPipeline.Status> statuses
    ) {
        return terminalStatus(transitions.size(), statuses);
    }

    static TerminalStatus terminalStatus(
        int transitionCount,
        List<ExactNestedFactorizationTransformationPipeline.Status> statuses
    ) {
        var retainedStatuses = List.copyOf(
            Objects.requireNonNull(statuses, "statuses")
        );
        if (transitionCount < 0 || transitionCount > retainedStatuses.size()) {
            throw new IllegalArgumentException(
                "transition count differs from occurrence status count"
            );
        }
        if (transitionCount > 0) {
            boolean everyOccurrenceTransformed =
                transitionCount == retainedStatuses.size()
                    && retainedStatuses.stream().allMatch(value ->
                        value
                            == ExactNestedFactorizationTransformationPipeline
                                .Status.TRANSFORMED
                    );
            if (!everyOccurrenceTransformed) {
                throw new IllegalStateException(
                    "partial occurrence success cannot become a terminal "
                        + "validated result"
                );
            }
            return TerminalStatus.VALIDATED_TRANSITION;
        }
        if (retainedStatuses.contains(
                ExactNestedFactorizationTransformationPipeline.Status
                    .TECHNICAL_FAILURE)) {
            return TerminalStatus.TECHNICAL_FAILURE;
        }
        if (retainedStatuses.contains(
                ExactNestedFactorizationTransformationPipeline.Status
                    .BUDGET_INCONCLUSIVE)) {
            return TerminalStatus.BUDGET_INCONCLUSIVE;
        }
        if (retainedStatuses.stream().anyMatch(
                PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter
                    ::unsupported
        )) {
            return TerminalStatus.UNSUPPORTED;
        }
        return TerminalStatus.NO_TRANSITION;
    }

'''
    adapter = adapter[:start] + terminal + adapter[end:]
adapter_path.write_text(adapter, encoding="utf-8")

characterization_relative = (
    "app/src/test/java/de/regelsuche/benchmark/polynomial/"
    "PolynomialTheoryUtilityOnDemandWorkCharacterizationTest.java"
)
characterization_path = ROOT / characterization_relative
characterization = characterization_path.read_text(encoding="utf-8")
engine_start = characterization.index(
    "        var engine = NativeUnivariateFactorizationEngine.rationals("
)
engine_end = characterization.index("        var nested =", engine_start)
characterization_engine = '''        var engine = NativeUnivariateFactorizationEngine.rationals(
            NativeUnivariateFactorizationPolicy.boundedDefaults()
                .withWorkProjection(
                    PolynomialTheoryUtilityCanonicalWorkProjection
                        .nativeRuntimeProjection()
                )
                .withMaxEngineWorkUnits(input.factorizationWork())
        );
'''
characterization = (
    characterization[:engine_start]
    + characterization_engine
    + characterization[engine_end:]
)
characterization_path.write_text(characterization, encoding="utf-8")

write(
    "regelsuche-core/src/test/java/de/regelsuche/polynomial/PolynomialWorkProjectionTest.java",
    '''package de.regelsuche.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PolynomialWorkProjectionTest {
    @Test
    void identityProjectionChargesEveryRawUnit() {
        var projection = PolynomialWorkProjection.identity();

        assertEquals(5L, projection.projectedDelta("stage", 3L, 5L));
        assertEquals(8L, projection.projectStage("stage", 8L));
    }

    @Test
    void rejectsANonMonotoneProjection() {
        PolynomialWorkProjection projection = new PolynomialWorkProjection() {
            @Override
            public String projectionId() {
                return "test.non-monotone/v1";
            }

            @Override
            public long projectStage(String stage, long cumulativeRawUnits) {
                return cumulativeRawUnits == 0L ? 1L : 0L;
            }
        };

        assertThrows(
            IllegalArgumentException.class,
            () -> projection.projectedDelta("stage", 0L, 1L)
        );
    }
}
'''
)

write(
    "regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/"
    "polynomial/PolynomialWorkBudgetProjectionTest.java",
    '''package de.regelsuche.math.algorithms.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.polynomial.PolynomialWorkProjection;
import org.junit.jupiter.api.Test;

class PolynomialWorkBudgetProjectionTest {
    private static final PolynomialWorkProjection BLOCKS_OF_64 =
        new PolynomialWorkProjection() {
            @Override
            public String projectionId() {
                return "test.blocks-of-64/v1";
            }

            @Override
            public long projectStage(
                String stage,
                long cumulativeRawUnits
            ) {
                return cumulativeRawUnits == 0L
                    ? 0L
                    : 1L + (cumulativeRawUnits - 1L) / 64L;
            }
        };

    @Test
    void retainsRawEvidenceWhileEnforcingProjectedUnits() {
        var work = new PolynomialWorkBudget(1L, BLOCKS_OF_64);

        work.consume("payload", 32L);
        work.consume("payload", 32L);

        assertEquals(64L, work.ledger().stages().get("payload"));
        assertEquals(1L, work.projectedWorkUnits());
        assertEquals(0L, work.remainingWorkUnits());
        assertThrows(
            PolynomialWorkBudget.LimitReached.class,
            () -> work.consume("payload", 1L)
        );
        assertEquals(64L, work.ledger().stages().get("payload"));
    }

    @Test
    void projectionScopeReachesNestedBudgetsAndRestoresIdentity() {
        PolynomialWorkBudget.withProjection(BLOCKS_OF_64, () -> {
            var outer = new PolynomialWorkBudget(1L);
            assertSame(BLOCKS_OF_64, outer.projection());
            outer.consume("payload", 64L);

            PolynomialWorkBudget.withProjection(
                PolynomialWorkProjection.identity(),
                () -> {
                    var nested = new PolynomialWorkBudget(1L);
                    assertSame(
                        PolynomialWorkProjection.identity(),
                        nested.projection()
                    );
                    assertThrows(
                        PolynomialWorkBudget.LimitReached.class,
                        () -> nested.consume("payload", 2L)
                    );
                    return null;
                }
            );
            var restoredOuter = new PolynomialWorkBudget(1L);
            assertSame(BLOCKS_OF_64, restoredOuter.projection());
            return null;
        });

        var restored = new PolynomialWorkBudget(1L);
        assertSame(
            PolynomialWorkProjection.identity(),
            restored.projection()
        );
    }
}
'''
)

write(
    "regelsuche-experiments/src/test/java/de/regelsuche/benchmark/polynomial/"
    "PolynomialTheoryUtilityRuntimeProjectionParityTest.java",
    '''package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityRuntimeProjectionParityTest {
    @Test
    void runtimeProjectionUsesTheFinalEvidenceStagePricing() {
        Map<String, Long> stages = Map.of(
            "native.operation", 7L,
            "native.result-structural-hash", 256L,
            "native.result-payload-utf8-bytes", 65L
        );

        stages.forEach((stage, rawUnits) -> assertEquals(
            PolynomialTheoryUtilityCanonicalWorkProjection
                .projectedStageUnits(stage, rawUnits),
            PolynomialTheoryUtilityCanonicalWorkProjection
                .nativeRuntimeProjection()
                .projectStage(stage, rawUnits)
        ));
    }
}
'''
)

write(
    "app/src/test/java/de/regelsuche/benchmark/polynomial/"
    "PolynomialTheoryUtilityTerminalStatusTest.java",
    '''package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.polynomial
    .ExactNestedFactorizationTransformationPipeline;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityTerminalStatusTest {
    @Test
    void rejectsPartialSuccessThatWouldHideAnInconclusiveOccurrence() {
        assertThrows(
            IllegalStateException.class,
            () -> PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter
                .terminalStatus(
                    1,
                    List.of(
                        ExactNestedFactorizationTransformationPipeline.Status
                            .TRANSFORMED,
                        ExactNestedFactorizationTransformationPipeline.Status
                            .BUDGET_INCONCLUSIVE
                    )
                )
        );
    }

    @Test
    void rejectsPartialSuccessThatWouldHideATechnicalOccurrence() {
        assertThrows(
            IllegalStateException.class,
            () -> PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter
                .terminalStatus(
                    1,
                    List.of(
                        ExactNestedFactorizationTransformationPipeline.Status
                            .TRANSFORMED,
                        ExactNestedFactorizationTransformationPipeline.Status
                            .TECHNICAL_FAILURE
                    )
                )
        );
    }

    @Test
    void requiresEveryOccurrenceForAValidatedTransition() {
        assertEquals(
            PolynomialTheoryUtilityCandidateResult.TerminalStatus
                .VALIDATED_TRANSITION,
            PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter
                .terminalStatus(
                    2,
                    List.of(
                        ExactNestedFactorizationTransformationPipeline.Status
                            .TRANSFORMED,
                        ExactNestedFactorizationTransformationPipeline.Status
                            .TRANSFORMED
                    )
                )
        );
    }
}
'''
)

for transient in (
    "tools/_connector_local_path_probe.txt",
):
    path = ROOT / transient
    if path.exists():
        path.unlink()
