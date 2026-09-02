package de.regelsuche.benchmark.polynomial;

import de.regelsuche.polynomial.PolynomialWorkLedger;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Partitions one complete polynomial-pipeline ledger for the fixed utility-study
 * work projection.
 *
 * <p>This class is a deterministic adapter convenience, not a second work
 * authority. The returned partition is always revalidated by
 * {@link PolynomialTheoryUtilityCanonicalWorkProjection#project}.</p>
 */
public final class PolynomialTheoryUtilityRawWorkPartitioner {
    private PolynomialTheoryUtilityRawWorkPartitioner() {
    }

    public static PolynomialTheoryUtilityCanonicalWorkProjection.Projection
            project(
                PolynomialTheoryUtilityExecutionInput input,
                long primitiveWork,
                PolynomialWorkLedger totalMechanicalWork
            ) {
        return PolynomialTheoryUtilityCanonicalWorkProjection.project(
            input,
            partition(primitiveWork, totalMechanicalWork)
        );
    }

    static PolynomialTheoryUtilityCanonicalWorkProjection.RawWork partition(
        long primitiveWork,
        PolynomialWorkLedger totalMechanicalWork
    ) {
        var total = Objects.requireNonNull(
            totalMechanicalWork,
            "totalMechanicalWork"
        );
        Map<Segment, Map<String, Long>> stages = new EnumMap<>(Segment.class);
        for (Segment segment : Segment.values()) {
            stages.put(segment, new LinkedHashMap<>());
        }
        total.stages().forEach((stage, units) ->
            stages.get(classify(stage)).put(stage, units)
        );
        return new PolynomialTheoryUtilityCanonicalWorkProjection.RawWork(
            primitiveWork,
            total,
            ledger(stages.get(Segment.MATCHING)),
            ledger(stages.get(Segment.SOURCE_VALIDATION)),
            ledger(stages.get(Segment.FACTORIZATION)),
            ledger(stages.get(Segment.VERIFICATION)),
            ledger(stages.get(Segment.RENDERING)),
            ledger(stages.get(Segment.REPARSE)),
            ledger(stages.get(Segment.RECONSTRUCTION)),
            ledger(stages.get(Segment.OCCURRENCE_REPLACEMENT)),
            ledger(stages.get(Segment.CACHE_LOOKUP)),
            ledger(stages.get(Segment.CACHE_INSERTION)),
            ledger(stages.get(Segment.CACHE_EVICTION)),
            ledger(stages.get(Segment.CACHE_REPLAY)),
            ledger(stages.get(Segment.EVIDENCE_CONSTRUCTION))
        );
    }

    private static PolynomialWorkLedger ledger(Map<String, Long> stages) {
        return new PolynomialWorkLedger(stages);
    }

    private static Segment classify(String stage) {
        String value = Objects.requireNonNull(stage, "stage");
        if (value.startsWith("projection.")
                || value.startsWith("nested.position-")
                || value.startsWith("nested.root-preflight-")
                || value.startsWith("nested.application-staleness-")
                || value.startsWith("nested.unchanged-")) {
            return Segment.MATCHING;
        }
        if (value.startsWith("exact-parsed-view.")
                || value.startsWith("transform.source-evidence-")) {
            return Segment.SOURCE_VALIDATION;
        }
        if (value.startsWith("verify.")) {
            return Segment.VERIFICATION;
        }
        if (value.startsWith("render.")) {
            return Segment.RENDERING;
        }
        if (value.startsWith("transform.exact-reparse-")) {
            return Segment.REPARSE;
        }
        if ("transform.structural-change-comparison".equals(value)) {
            return Segment.RECONSTRUCTION;
        }
        if (value.startsWith("nested.replacement-")
                || value.startsWith("nested.rewritten-")
                || value.startsWith("nested.replay-")
                || "nested.application-path-navigation".equals(value)) {
            return Segment.OCCURRENCE_REPLACEMENT;
        }
        if (value.startsWith("cache.lookup.")) {
            return Segment.CACHE_LOOKUP;
        }
        if (value.startsWith("cache.insertion.")) {
            return Segment.CACHE_INSERTION;
        }
        if (value.startsWith("cache.eviction.")) {
            return Segment.CACHE_EVICTION;
        }
        if (value.startsWith("cache.replay.")) {
            return Segment.CACHE_REPLAY;
        }
        if (value.startsWith("study.evidence.")) {
            return Segment.EVIDENCE_CONSTRUCTION;
        }
        return Segment.FACTORIZATION;
    }

    private enum Segment {
        MATCHING,
        SOURCE_VALIDATION,
        FACTORIZATION,
        VERIFICATION,
        RENDERING,
        REPARSE,
        RECONSTRUCTION,
        OCCURRENCE_REPLACEMENT,
        CACHE_LOOKUP,
        CACHE_INSERTION,
        CACHE_EVICTION,
        CACHE_REPLAY,
        EVIDENCE_CONSTRUCTION
    }
}
