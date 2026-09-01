package de.regelsuche.benchmark.polynomial;

import de.regelsuche.polynomial.PolynomialWorkLedger;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Projects the exact polynomial pipeline ledger into the preregistered study
 * work vector without consulting qualification data or mathematical outcomes.
 *
 * <p>Every raw stage must occur in exactly one named segment. Unknown stages
 * are charged conservatively one-for-one and are accepted only as native
 * factorization work. The few discounted stages undo explicit implementation
 * multipliers for literal comparison, source-text comparison and structural
 * hashing; their divisors are fixed by this revision before profile
 * execution.</p>
 */
public final class PolynomialTheoryUtilityCanonicalWorkProjection {
    public static final String REVISION =
        "regelsuche.polynomial-theory-utility-work-projection/v1";

    private static final long LITERAL_VALIDATION_QUANTUM = 512L;
    private static final long SOURCE_TEXT_VALIDATION_QUANTUM = 4L;
    private static final long STRUCTURAL_HASH_QUANTUM = 128L;
    private static final long UTF8_EVIDENCE_QUANTUM = 64L;

    private PolynomialTheoryUtilityCanonicalWorkProjection() {
    }

    public static Projection project(
        PolynomialTheoryUtilityExecutionInput input,
        RawWork rawWork
    ) {
        var frozenInput = Objects.requireNonNull(input, "input");
        var raw = Objects.requireNonNull(rawWork, "rawWork");
        PolynomialTheoryUtilityWorkBreakdown work =
            new PolynomialTheoryUtilityWorkBreakdown(
                raw.primitiveWork(),
                canonicalUnits(raw.matchingWork(), Dimension.MATCHING),
                canonicalUnits(
                    raw.sourceValidationWork(),
                    Dimension.SOURCE_VALIDATION
                ),
                canonicalUnits(
                    raw.factorizationWork(),
                    Dimension.FACTORIZATION
                ),
                canonicalUnits(
                    raw.verificationWork(),
                    Dimension.VERIFICATION
                ),
                canonicalUnits(raw.renderingWork(), Dimension.RENDERING),
                canonicalUnits(raw.reparseWork(), Dimension.REPARSE),
                canonicalUnits(
                    raw.reconstructionWork(),
                    Dimension.RECONSTRUCTION
                ),
                canonicalUnits(
                    raw.occurrenceReplacementWork(),
                    Dimension.OCCURRENCE_REPLACEMENT
                ),
                canonicalUnits(
                    raw.cacheLookupWork(),
                    Dimension.CACHE_LOOKUP
                ),
                canonicalUnits(
                    raw.cacheInsertionWork(),
                    Dimension.CACHE_INSERTION
                ),
                canonicalUnits(
                    raw.cacheEvictionWork(),
                    Dimension.CACHE_EVICTION
                ),
                canonicalUnits(
                    raw.cacheReplayWork(),
                    Dimension.CACHE_REPLAY
                ),
                canonicalUnits(
                    raw.evidenceConstructionWork(),
                    Dimension.EVIDENCE_CONSTRUCTION
                )
            );
        requireWithinAuthority(frozenInput, work);
        String rawWorkHash = hash(raw.identityMaterial());
        String projectionId = projectionId(
            frozenInput.inputId(),
            rawWorkHash,
            work
        );
        return new Projection(
            projectionId,
            frozenInput.inputId(),
            REVISION,
            rawWorkHash,
            work
        );
    }

    private static void requireWithinAuthority(
        PolynomialTheoryUtilityExecutionInput input,
        PolynomialTheoryUtilityWorkBreakdown work
    ) {
        if (work.primitiveWork() > input.admittedPrimitiveWork()
                || work.mechanicalWork() > input.totalMechanicalWork()
                || work.factorizationWork() > input.factorizationWork()) {
            throw new IllegalArgumentException(
                "projected work exceeds the frozen execution authority"
            );
        }
    }

    private static long canonicalUnits(
        PolynomialWorkLedger ledger,
        Dimension dimension
    ) {
        long result = 0L;
        for (Map.Entry<String, Long> entry
                : Objects.requireNonNull(ledger, "ledger").stages().entrySet()) {
            String stage = entry.getKey();
            if (!dimension.accepts(stage)) {
                throw new IllegalArgumentException(
                    "raw stage is assigned to the wrong study dimension: "
                        + dimension + ":" + stage
                );
            }
            result = Math.addExact(
                result,
                divideRoundUp(entry.getValue(), quantum(stage))
            );
        }
        return result;
    }

    private static long quantum(String stage) {
        return switch (stage) {
            case "transform.source-evidence-literal-validation" ->
                LITERAL_VALIDATION_QUANTUM;
            case "transform.source-evidence-text-validation" ->
                SOURCE_TEXT_VALIDATION_QUANTUM;
            default -> {
                if (stage.endsWith("-payload-utf8-bytes")) {
                    yield UTF8_EVIDENCE_QUANTUM;
                }
                if (stage.contains("structural-hash")) {
                    yield STRUCTURAL_HASH_QUANTUM;
                }
                yield 1L;
            }
        };
    }

    private static long divideRoundUp(long value, long divisor) {
        if (value < 0L || divisor < 1L) {
            throw new IllegalArgumentException(
                "canonical work conversion received an invalid value"
            );
        }
        if (value == 0L) {
            return 0L;
        }
        return 1L + (value - 1L) / divisor;
    }

    private static String projectionId(
        String inputId,
        String rawWorkHash,
        PolynomialTheoryUtilityWorkBreakdown work
    ) {
        StringBuilder material = new StringBuilder();
        append(material, REVISION);
        append(material, inputId);
        append(material, rawWorkHash);
        work.appendIdentityMaterial(material);
        return hash(material.toString());
    }

    private static String hash(String value) {
        return PolynomialTheoryUtilityExecutionIdentity.sha256(
            value.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    private enum Dimension {
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
        EVIDENCE_CONSTRUCTION;

        private boolean accepts(String stage) {
            Objects.requireNonNull(stage, "stage");
            return switch (this) {
                case MATCHING -> stage.startsWith("projection.")
                    || stage.startsWith("nested.position-")
                    || stage.startsWith("nested.root-preflight-")
                    || stage.startsWith("nested.application-staleness-")
                    || stage.startsWith("nested.unchanged-");
                case SOURCE_VALIDATION ->
                    stage.startsWith("exact-parsed-view.")
                    || stage.startsWith("transform.source-evidence-");
                case FACTORIZATION -> !reserved(stage);
                case VERIFICATION -> stage.startsWith("verify.");
                case RENDERING -> stage.startsWith("render.");
                case REPARSE ->
                    stage.startsWith("transform.exact-reparse-");
                case RECONSTRUCTION -> stage.equals(
                    "transform.structural-change-comparison"
                );
                case OCCURRENCE_REPLACEMENT ->
                    stage.startsWith("nested.replacement-")
                    || stage.startsWith("nested.rewritten-")
                    || stage.startsWith("nested.replay-")
                    || stage.startsWith(
                        "nested.application-path-navigation"
                    );
                case CACHE_LOOKUP -> stage.startsWith("cache.lookup.");
                case CACHE_INSERTION ->
                    stage.startsWith("cache.insertion.");
                case CACHE_EVICTION ->
                    stage.startsWith("cache.eviction.");
                case CACHE_REPLAY -> stage.startsWith("cache.replay.");
                case EVIDENCE_CONSTRUCTION ->
                    stage.startsWith("study.evidence.");
            };
        }

        private static boolean reserved(String stage) {
            return valuesExceptFactorization().stream()
                .anyMatch(value -> value.accepts(stage));
        }

        private static java.util.List<Dimension> valuesExceptFactorization() {
            return java.util.List.of(
                MATCHING,
                SOURCE_VALIDATION,
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
            );
        }
    }

    /**
     * One exact partition of the raw mechanical ledger plus primitive lineage.
     */
    public record RawWork(
        long primitiveWork,
        PolynomialWorkLedger totalMechanicalWork,
        PolynomialWorkLedger matchingWork,
        PolynomialWorkLedger sourceValidationWork,
        PolynomialWorkLedger factorizationWork,
        PolynomialWorkLedger verificationWork,
        PolynomialWorkLedger renderingWork,
        PolynomialWorkLedger reparseWork,
        PolynomialWorkLedger reconstructionWork,
        PolynomialWorkLedger occurrenceReplacementWork,
        PolynomialWorkLedger cacheLookupWork,
        PolynomialWorkLedger cacheInsertionWork,
        PolynomialWorkLedger cacheEvictionWork,
        PolynomialWorkLedger cacheReplayWork,
        PolynomialWorkLedger evidenceConstructionWork
    ) {
        public RawWork {
            if (primitiveWork < 0L) {
                throw new IllegalArgumentException(
                    "primitive work must be non-negative"
                );
            }
            totalMechanicalWork = require(totalMechanicalWork);
            matchingWork = require(matchingWork);
            sourceValidationWork = require(sourceValidationWork);
            factorizationWork = require(factorizationWork);
            verificationWork = require(verificationWork);
            renderingWork = require(renderingWork);
            reparseWork = require(reparseWork);
            reconstructionWork = require(reconstructionWork);
            occurrenceReplacementWork = require(
                occurrenceReplacementWork
            );
            cacheLookupWork = require(cacheLookupWork);
            cacheInsertionWork = require(cacheInsertionWork);
            cacheEvictionWork = require(cacheEvictionWork);
            cacheReplayWork = require(cacheReplayWork);
            evidenceConstructionWork = require(
                evidenceConstructionWork
            );
            requireUniqueStageOwnership(
                matchingWork,
                sourceValidationWork,
                factorizationWork,
                verificationWork,
                renderingWork,
                reparseWork,
                reconstructionWork,
                occurrenceReplacementWork,
                cacheLookupWork,
                cacheInsertionWork,
                cacheEvictionWork,
                cacheReplayWork,
                evidenceConstructionWork
            );
            PolynomialWorkLedger partition = merge(
                matchingWork,
                sourceValidationWork,
                factorizationWork,
                verificationWork,
                renderingWork,
                reparseWork,
                reconstructionWork,
                occurrenceReplacementWork,
                cacheLookupWork,
                cacheInsertionWork,
                cacheEvictionWork,
                cacheReplayWork,
                evidenceConstructionWork
            );
            if (!totalMechanicalWork.equals(partition)) {
                throw new IllegalArgumentException(
                    "raw study work does not exactly partition the pipeline ledger"
                );
            }
        }

        private static PolynomialWorkLedger require(
            PolynomialWorkLedger value
        ) {
            return Objects.requireNonNull(value, "raw work segment");
        }

        private static void requireUniqueStageOwnership(
            PolynomialWorkLedger... values
        ) {
            Set<String> ownedStages = new LinkedHashSet<>();
            for (PolynomialWorkLedger value : values) {
                for (String stage : value.stages().keySet()) {
                    if (!ownedStages.add(stage)) {
                        throw new IllegalArgumentException(
                            "raw stage is split across study dimensions: "
                                + stage
                        );
                    }
                }
            }
        }

        private String identityMaterial() {
            StringBuilder material = new StringBuilder();
            append(material, REVISION);
            append(material, Long.toString(primitiveWork));
            appendLedger(material, "total", totalMechanicalWork);
            appendLedger(material, "matching", matchingWork);
            appendLedger(
                material,
                "source-validation",
                sourceValidationWork
            );
            appendLedger(material, "factorization", factorizationWork);
            appendLedger(material, "verification", verificationWork);
            appendLedger(material, "rendering", renderingWork);
            appendLedger(material, "reparse", reparseWork);
            appendLedger(
                material,
                "reconstruction",
                reconstructionWork
            );
            appendLedger(
                material,
                "occurrence-replacement",
                occurrenceReplacementWork
            );
            appendLedger(material, "cache-lookup", cacheLookupWork);
            appendLedger(
                material,
                "cache-insertion",
                cacheInsertionWork
            );
            appendLedger(material, "cache-eviction", cacheEvictionWork);
            appendLedger(material, "cache-replay", cacheReplayWork);
            appendLedger(
                material,
                "evidence-construction",
                evidenceConstructionWork
            );
            return material.toString();
        }

        private static void appendLedger(
            StringBuilder target,
            String role,
            PolynomialWorkLedger ledger
        ) {
            append(target, role);
            append(target, ledger.canonicalMaterial());
        }

        private static PolynomialWorkLedger merge(
            PolynomialWorkLedger... values
        ) {
            Map<String, Long> stages = new LinkedHashMap<>();
            for (PolynomialWorkLedger value : values) {
                value.stages().forEach((stage, units) ->
                    stages.merge(stage, units, Math::addExact)
                );
            }
            return new PolynomialWorkLedger(stages);
        }
    }

    /** Content-addressed result of the fixed raw-to-canonical projection. */
    public record Projection(
        String projectionId,
        String executionInputId,
        String projectionRevision,
        String rawWorkHash,
        PolynomialTheoryUtilityWorkBreakdown work
    ) {
        public Projection {
            projectionId = requireHash(projectionId, "projectionId");
            executionInputId = requireHash(
                executionInputId,
                "executionInputId"
            );
            if (!REVISION.equals(projectionRevision)) {
                throw new IllegalArgumentException(
                    "projection revision differs from the frozen contract"
                );
            }
            rawWorkHash = requireHash(rawWorkHash, "rawWorkHash");
            work = Objects.requireNonNull(work, "work");
            if (!projectionId.equals(
                    PolynomialTheoryUtilityCanonicalWorkProjection
                        .projectionId(executionInputId, rawWorkHash, work))) {
                throw new IllegalArgumentException(
                    "projection identity differs from its evidence"
                );
            }
        }

        private static String requireHash(String value, String name) {
            String text = Objects.requireNonNull(value, name);
            if (!text.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(name + " is not SHA-256");
            }
            return text;
        }
    }
}
