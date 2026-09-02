from pathlib import Path
import runpy
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SOURCE_REVISION = "838fbbee2135128351bd68811a25c3b2e8d8581b"
SOURCE_PATH = "tools/apply_pr861_projected_work_v2.py"
TEMPORARY = ROOT / "tools/.apply_pr861_projected_work_runtime.py"


def write(relative: str, content: str) -> None:
    path = ROOT / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


source = subprocess.run(
    ["git", "show", f"{SOURCE_REVISION}:{SOURCE_PATH}"],
    check=True,
    capture_output=True,
    text=True,
).stdout

old_projection_replacement = '''old_default = ": divideRoundUp(entry.getValue(), quantum(entry.getKey()));"
new_default = ": projectedStageUnits(entry.getKey(), entry.getValue());"
if new_default not in canonical:
    if old_default not in canonical:
        raise RuntimeError("canonical default projection expression not found")
    canonical = canonical.replace(old_default, new_default, 1)
'''
new_projection_replacement = '''new_default = ": projectedStageUnits(entry.getKey(), entry.getValue());"
if new_default not in canonical:
    import re
    canonical, replacement_count = re.subn(
        r":\\s*divideRoundUp\\(\\s*entry\\.getValue\\(\\),\\s*"
        r"quantum\\(entry\\.getKey\\(\\)\\)\\s*\\);",
        new_default,
        canonical,
        count=1,
    )
    if replacement_count != 1:
        raise RuntimeError("canonical default projection expression not found")
'''
if old_projection_replacement not in source:
    raise RuntimeError("historical projection bootstrap block not found")
source = source.replace(
    old_projection_replacement,
    new_projection_replacement,
    1,
)

# The one-off characterization probe was deliberately removed from the PR.
characterization_start = source.index("characterization_relative = (")
characterization_end = source.index(
    'write(\n    "regelsuche-core/src/test/java/de/regelsuche/polynomial/'
    'PolynomialWorkProjectionTest.java"',
    characterization_start,
)
source = source[:characterization_start] + source[characterization_end:]

TEMPORARY.write_text(source, encoding="utf-8")
try:
    runpy.run_path(str(TEMPORARY), run_name="__main__")
finally:
    TEMPORARY.unlink(missing_ok=True)

write(
    "regelsuche-core/src/main/java/de/regelsuche/polynomial/"
    "PolynomialWorkProjection.java",
    '''package de.regelsuche.polynomial;

import java.util.Objects;

/**
 * Converts cumulative raw instrumentation units for one stage into the work
 * units enforced by an owning execution authority.
 *
 * <p>The projection is stage-local, monotone and non-expansive. A budget
 * retains the complete raw ledger while charging only the projected delta
 * caused by each raw entry. This keeps evidence lossless without allowing a
 * projection to create work or to bypass the independent raw safety ceiling.
 * </p>
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
        if (previous < 0L
                || next < previous
                || previous > previousRawUnits
                || next > nextRawUnits) {
            throw new IllegalArgumentException(
                "polynomial work projection must be non-negative, monotone "
                    + "and non-expansive"
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
    "regelsuche-math-algorithms/src/main/java/de/regelsuche/math/"
    "algorithms/polynomial/PolynomialWorkBudget.java",
    '''package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.PolynomialWorkProjection;
import de.regelsuche.polynomial.PolynomialWorkSink;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Shared non-resettable authority with independent raw and projected ceilings.
 *
 * <p>The raw ceiling bounds retained implementation work. The projected
 * ceiling bounds the study-authoritative work after applying one stable,
 * stage-local projection. A child starts from an exact cumulative snapshot but
 * retains only its local additions, so nested certificates remain local while
 * stage quanta are never reset.</p>
 */
final class PolynomialWorkBudget implements PolynomialWorkSink {
    private final long rawLimit;
    private final long projectedLimit;
    private final PolynomialWorkProjection projection;
    private final Map<String, Long> baseStages;
    private final long baseRawTotal;
    private final Map<String, Long> stages = new LinkedHashMap<>();
    private long localRawTotal;
    private long projectedTotal;

    PolynomialWorkBudget(long limit) {
        this(
            limit,
            limit,
            PolynomialWorkProjection.identity(),
            Map.of()
        );
    }

    PolynomialWorkBudget(
        long rawLimit,
        long projectedLimit,
        PolynomialWorkProjection projection
    ) {
        this(rawLimit, projectedLimit, projection, Map.of());
    }

    private PolynomialWorkBudget(
        long rawLimit,
        long projectedLimit,
        PolynomialWorkProjection projection,
        Map<String, Long> baseStages
    ) {
        if (rawLimit < 1L || projectedLimit < 1L) {
            throw new IllegalArgumentException(
                "polynomial work authorities must be positive"
            );
        }
        this.rawLimit = rawLimit;
        this.projectedLimit = projectedLimit;
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
        PolynomialWorkLedger base = new PolynomialWorkLedger(
            Objects.requireNonNull(baseStages, "baseStages")
        );
        this.baseStages = base.stages();
        baseRawTotal = base.totalWorkUnits();
        projectedTotal = projectedUnits(this.baseStages);
        if (baseRawTotal > rawLimit || projectedTotal > projectedLimit) {
            throw new IllegalArgumentException(
                "polynomial child work exceeds its owning authority"
            );
        }
    }

    long limit() {
        return rawLimit;
    }

    long projectedLimit() {
        return projectedLimit;
    }

    long rawWorkUnits() {
        return Math.addExact(baseRawTotal, localRawTotal);
    }

    long projectedWorkUnits() {
        return projectedTotal;
    }

    long remainingRawWorkUnits() {
        return Math.subtractExact(rawLimit, rawWorkUnits());
    }

    long remainingWorkUnits() {
        return Math.subtractExact(projectedLimit, projectedTotal);
    }

    PolynomialWorkProjection projection() {
        return projection;
    }

    PolynomialWorkBudget child() {
        Map<String, Long> cumulative = new LinkedHashMap<>(baseStages);
        stages.forEach((stage, units) ->
            cumulative.merge(stage, units, Math::addExact)
        );
        PolynomialWorkBudget child = new PolynomialWorkBudget(
            rawLimit,
            projectedLimit,
            projection,
            cumulative
        );
        if (child.rawWorkUnits() != rawWorkUnits()
                || child.projectedWorkUnits() != projectedWorkUnits()) {
            throw new IllegalStateException(
                "polynomial child authority lost its cumulative prefix"
            );
        }
        return child;
    }

    @Override
    public void consume(String stage, long units) {
        String retainedStage = Objects.requireNonNull(stage, "stage");
        if (retainedStage.isBlank() || units < 0L) {
            throw new IllegalArgumentException(
                "polynomial work entry is invalid"
            );
        }
        if (units == 0L) {
            return;
        }
        if (units > remainingRawWorkUnits()) {
            throw new LimitReached();
        }

        long localStage = stages.getOrDefault(retainedStage, 0L);
        long previousRawUnits = Math.addExact(
            baseStages.getOrDefault(retainedStage, 0L),
            localStage
        );
        long projectedDelta = projection.projectedDelta(
            retainedStage,
            previousRawUnits,
            units
        );
        if (projectedDelta > remainingWorkUnits()) {
            throw new LimitReached();
        }

        long nextLocalStage = Math.addExact(localStage, units);
        long nextLocalRawTotal = Math.addExact(localRawTotal, units);
        long nextProjectedTotal = Math.addExact(
            projectedTotal,
            projectedDelta
        );
        stages.put(retainedStage, nextLocalStage);
        localRawTotal = nextLocalRawTotal;
        projectedTotal = nextProjectedTotal;
    }

    PolynomialWorkLedger ledger() {
        return new PolynomialWorkLedger(stages);
    }

    private long projectedUnits(Map<String, Long> rawStages) {
        long result = 0L;
        for (Map.Entry<String, Long> entry : rawStages.entrySet()) {
            result = Math.addExact(
                result,
                projection.projectedDelta(
                    entry.getKey(),
                    0L,
                    entry.getValue()
                )
            );
        }
        return result;
    }

    static final class LimitReached extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
'''
)

pipeline_path = (
    ROOT
    / "regelsuche-math-algorithms/src/main/java/de/regelsuche/math/"
      "algorithms/polynomial/NativeUnivariateFactorizationPipeline.java"
)
pipeline = pipeline_path.read_text(encoding="utf-8")
factor_start = pipeline.index(
    "    static <C> FactorizationEngine.EngineResult<C> factor("
)
factor_end = pipeline.index(
    "    private static <C> FactorizationEngine.EngineResult<C> execute(",
    factor_start,
)
factor_method = '''    static <C> FactorizationEngine.EngineResult<C> factor(
        FactorizationRequest<C> request,
        NativeUnivariateFactorizationPolicy policy,
        NativeCoefficientAdapter<C> adapter
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(adapter, "adapter");
        long engineWorkLimit = Math.min(
            request.maxWorkUnits(),
            policy.maxEngineWorkUnits()
        );
        // The request remains the independent raw-evidence authority. The
        // second ceiling below enforces the projected backend allocation.
        FactorizationRequest<C> engineRequest = request;
        PolynomialWorkBudget work = new PolynomialWorkBudget(
            request.maxWorkUnits(),
            engineWorkLimit,
            policy.workProjection()
        );
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
pipeline = pipeline[:factor_start] + factor_method + pipeline[factor_end:]

old_square_free = '''        SquareFreeDecomposition.Result<ExactRational> squareFree =
            SquareFreeDecomposition.decompose(
                rationalPrimitive,
                internalLimits,
                remainingWork(engineRequest, work));
        mergeWork(work, squareFree.work());
'''
new_square_free = '''        PolynomialWorkBudget squareFreeWork = work.child();
        SquareFreeDecomposition.Result<ExactRational> squareFree =
            SquareFreeDecomposition.decompose(
                rationalPrimitive,
                internalLimits,
                squareFreeWork
            );
        mergeWork(work, squareFree.work());
'''
if old_square_free not in pipeline:
    raise RuntimeError("square-free child authority block not found")
pipeline = pipeline.replace(old_square_free, new_square_free, 1)

engine_request_start = pipeline.index(
    "    private static <C> FactorizationRequest<C> engineRequest("
)
engine_request_end = pipeline.index(
    "    private static <C> FactorizationEngine.EngineResult<C> rejectInput(",
    engine_request_start,
)
pipeline = pipeline[:engine_request_start] + pipeline[engine_request_end:]

remaining_start = pipeline.index("    private static long remainingWork(")
remaining_end = pipeline.index(
    "    private static void mergeWork(",
    remaining_start,
)
pipeline = pipeline[:remaining_start] + pipeline[remaining_end:]
pipeline_path.write_text(pipeline, encoding="utf-8")

square_free_path = (
    ROOT
    / "regelsuche-math-algorithms/src/main/java/de/regelsuche/math/"
      "algorithms/polynomial/SquareFreeDecomposition.java"
)
square_free = square_free_path.read_text(encoding="utf-8")
decompose_start = square_free.index(
    "    public static <C> Result<C> decompose("
)
decompose_end = square_free.index(
    "    private static <C> Result<C> completed(",
    decompose_start,
)
decompose_methods = '''    public static <C> Result<C> decompose(
        SparsePolynomial<C> source,
        FactorizationRequest.StructuralLimits structuralLimits,
        long maxWorkUnits
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(
            structuralLimits,
            "structuralLimits"
        );
        return decompose(
            source,
            structuralLimits,
            new PolynomialWorkBudget(maxWorkUnits)
        );
    }

    static <C> Result<C> decompose(
        SparsePolynomial<C> source,
        FactorizationRequest.StructuralLimits structuralLimits,
        PolynomialWorkBudget work
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(
            structuralLimits,
            "structuralLimits"
        );
        Objects.requireNonNull(work, "work");
        String violation = structuralLimits.firstViolation(source)
            .orElse(null);
        if (violation != null) {
            return Result.failure(
                Status.BUDGET_INCONCLUSIVE,
                violation,
                work.ledger(),
                source,
                structuralLimits
            );
        }
        if (source.ring().variableCount() != 1
                || source.isConstant()) {
            return Result.failure(
                Status.UNSUPPORTED_SHAPE,
                "REQUIRES_NONCONSTANT_UNIVARIATE_POLYNOMIAL",
                work.ledger(),
                source,
                structuralLimits
            );
        }
        if (source.ring().coefficientDomain()
                .characteristic().signum() != 0) {
            return Result.failure(
                Status.UNSUPPORTED_DOMAIN,
                "REQUIRES_CHARACTERISTIC_ZERO",
                work.ledger(),
                source,
                structuralLimits
            );
        }
        ExactField<C> field =
            UnivariatePolynomialAlgorithms.exactField(source.ring());
        if (field == null) {
            return Result.failure(
                Status.UNSUPPORTED_DOMAIN,
                "REQUIRES_EXACT_COEFFICIENT_FIELD",
                work.ledger(),
                source,
                structuralLimits
            );
        }

        try {
            return completed(
                source,
                structuralLimits,
                field,
                work
            );
        } catch (PolynomialWorkBudget.LimitReached exception) {
            return Result.failure(
                Status.BUDGET_INCONCLUSIVE,
                "SQUARE_FREE_WORK_BUDGET_EXCEEDED",
                work.ledger(),
                source,
                structuralLimits
            );
        } catch (ArithmeticException exception) {
            return Result.failure(
                Status.TECHNICAL_FAILURE,
                "SQUARE_FREE_EXACT_DIVISION_FAILED",
                work.ledger(),
                source,
                structuralLimits
            );
        } catch (RuntimeException exception) {
            return Result.failure(
                Status.TECHNICAL_FAILURE,
                "SQUARE_FREE_"
                    + exception.getClass().getSimpleName()
                        .toUpperCase(java.util.Locale.ROOT),
                work.ledger(),
                source,
                structuralLimits
            );
        }
    }

'''
square_free = (
    square_free[:decompose_start]
    + decompose_methods
    + square_free[decompose_end:]
)
square_free_path.write_text(square_free, encoding="utf-8")

canonical_path = (
    ROOT
    / "regelsuche-experiments/src/main/java/de/regelsuche/benchmark/"
      "polynomial/PolynomialTheoryUtilityCanonicalWorkProjection.java"
)
canonical = canonical_path.read_text(encoding="utf-8")
old_runtime_projection = '''        public long projectStage(
            String stage,
            long cumulativeRawUnits
        ) {
            return projectedStageUnits(stage, cumulativeRawUnits);
        }
'''
new_runtime_projection = '''        public long projectStage(
            String stage,
            long cumulativeRawUnits
        ) {
            String retainedStage = Objects.requireNonNull(stage, "stage");
            if (!Dimension.FACTORIZATION.accepts(retainedStage)) {
                throw new IllegalArgumentException(
                    "native runtime projection received non-factorization "
                        + "stage: " + retainedStage
                );
            }
            return projectedStageUnits(retainedStage, cumulativeRawUnits);
        }
'''
if old_runtime_projection not in canonical:
    raise RuntimeError("generated native runtime projection not found")
canonical_path.write_text(
    canonical.replace(
        old_runtime_projection,
        new_runtime_projection,
        1,
    ),
    encoding="utf-8",
)

write(
    "regelsuche-core/src/test/java/de/regelsuche/polynomial/"
    "PolynomialWorkProjectionTest.java",
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
                return cumulativeRawUnits == 0L ? 0L : 2L - cumulativeRawUnits;
            }
        };

        assertThrows(
            IllegalArgumentException.class,
            () -> projection.projectedDelta("stage", 1L, 1L)
        );
    }

    @Test
    void rejectsAProjectionThatCreatesAdditionalWork() {
        PolynomialWorkProjection projection = new PolynomialWorkProjection() {
            @Override
            public String projectionId() {
                return "test.expansive/v1";
            }

            @Override
            public long projectStage(String stage, long cumulativeRawUnits) {
                return Math.addExact(cumulativeRawUnits, 1L);
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
    "regelsuche-math-algorithms/src/test/java/de/regelsuche/math/"
    "algorithms/polynomial/PolynomialWorkBudgetProjectionTest.java",
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
        var work = new PolynomialWorkBudget(128L, 1L, BLOCKS_OF_64);

        work.consume("payload", 32L);
        work.consume("payload", 32L);

        assertEquals(64L, work.ledger().units("payload"));
        assertEquals(64L, work.rawWorkUnits());
        assertEquals(1L, work.projectedWorkUnits());
        assertEquals(64L, work.remainingRawWorkUnits());
        assertEquals(0L, work.remainingWorkUnits());
        assertThrows(
            PolynomialWorkBudget.LimitReached.class,
            () -> work.consume("payload", 1L)
        );
        assertEquals(64L, work.ledger().units("payload"));
    }

    @Test
    void rawCeilingRemainsIndependentOfACompressiveProjection() {
        var work = new PolynomialWorkBudget(64L, 2L, BLOCKS_OF_64);

        work.consume("payload", 64L);

        assertEquals(1L, work.projectedWorkUnits());
        assertThrows(
            PolynomialWorkBudget.LimitReached.class,
            () -> work.consume("payload", 1L)
        );
        assertEquals(64L, work.rawWorkUnits());
    }

    @Test
    void childRetainsCumulativeQuantaButOnlyItsLocalLedger() {
        var parent = new PolynomialWorkBudget(128L, 2L, BLOCKS_OF_64);
        parent.consume("payload", 32L);

        PolynomialWorkBudget child = parent.child();
        assertSame(BLOCKS_OF_64, child.projection());
        assertEquals(parent.limit(), child.limit());
        assertEquals(parent.projectedLimit(), child.projectedLimit());
        assertEquals(32L, child.rawWorkUnits());
        assertEquals(1L, child.projectedWorkUnits());

        child.consume("payload", 32L);
        child.consume("other", 1L);

        assertEquals(32L, child.ledger().units("payload"));
        assertEquals(1L, child.ledger().units("other"));
        assertEquals(65L, child.rawWorkUnits());
        assertEquals(2L, child.projectedWorkUnits());

        child.ledger().stages().forEach(parent::consume);
        assertEquals(child.rawWorkUnits(), parent.rawWorkUnits());
        assertEquals(
            child.projectedWorkUnits(),
            parent.projectedWorkUnits()
        );
    }

    @Test
    void identityBudgetPreservesTheHistoricalOneForOneAuthority() {
        var work = new PolynomialWorkBudget(2L);

        work.consume("first", 1L);
        work.consume("second", 1L);

        assertEquals(2L, work.rawWorkUnits());
        assertEquals(2L, work.projectedWorkUnits());
        assertThrows(
            PolynomialWorkBudget.LimitReached.class,
            () -> work.consume("third", 1L)
        );
    }
}
'''
)

write(
    "regelsuche-experiments/src/test/java/de/regelsuche/benchmark/"
    "polynomial/PolynomialTheoryUtilityRuntimeProjectionParityTest.java",
    '''package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void runtimeProjectionRejectsAStageOwnedByAnotherDimension() {
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCanonicalWorkProjection
                .nativeRuntimeProjection()
                .projectStage("verify.product-reconstruction", 1L)
        );
    }
}
'''
)

authority_test_path = (
    ROOT
    / "regelsuche-math-algorithms/src/test/java/de/regelsuche/math/"
      "algorithms/polynomial/NativeUnivariateEngineWorkAuthorityTest.java"
)
authority_test = authority_test_path.read_text(encoding="utf-8")
old_assertion = '''        assertEquals(
            FactorizationEngine.Outcome.BUDGET_INCONCLUSIVE,
            result.outcome()
        );
'''
new_assertion = '''        assertEquals(
            FactorizationEngine.Outcome.BUDGET_INCONCLUSIVE,
            result.outcome(),
            result.detailCode()
        );
'''
if old_assertion not in authority_test:
    raise RuntimeError("native authority outcome assertion not found")
authority_test_path.write_text(
    authority_test.replace(old_assertion, new_assertion, 1),
    encoding="utf-8",
)

documentation_path = (
    ROOT / "docs/architecture/projected-polynomial-work-authority-review.md"
)
documentation = documentation_path.read_text(encoding="utf-8")
paragraph = '''
The native budget therefore retains two independent ceilings: the original
request remains the maximum raw ledger admitted to evidence, while the profile
allocation limits projected work. A nested stage starts from the parent's exact
cumulative per-stage counters, so projection quanta cannot be reset, but its
certificate contains only the stage-local raw additions that the parent later
replays through the same projection.
'''
if paragraph.strip() not in documentation:
    documentation += paragraph
documentation_path.write_text(documentation, encoding="utf-8")
