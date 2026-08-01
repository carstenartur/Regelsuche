package de.regelsuche.evolution;

import de.regelsuche.json.JsonWriter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Environment-qualified performance measurement plan kept separate from the
 * canonical primitive and total-work ledgers used for scientific comparison.
 */
public record EvolutionRewriteProgramPerformancePlan(
    String schema,
    String benchmarkRevisionHash,
    String benchmarkSuiteHash,
    String runtimeEnvironmentHash,
    int warmupIterations,
    int measurementIterations,
    int forks,
    int sampleTimeMillis,
    boolean allocationMeasurementRequired,
    List<MeasurementLayer> requiredLayers,
    boolean fixedWorkEndToEndRequired,
    boolean fixedTimeDiagnosticRequired,
    int maximumPinnedRegressionPermille,
    boolean canonicalEvidenceParityRequired,
    boolean referenceFallbackRequired,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.evolution-rewrite-program-performance-plan/v1";

    public EvolutionRewriteProgramPerformancePlan {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported rewrite-program performance-plan schema");
        }
        EvolutionGenome.requireSha256(
            benchmarkRevisionHash, "benchmarkRevisionHash");
        EvolutionGenome.requireSha256(
            benchmarkSuiteHash, "benchmarkSuiteHash");
        EvolutionGenome.requireSha256(
            runtimeEnvironmentHash, "runtimeEnvironmentHash");
        requiredLayers = canonicalLayers(requiredLayers);
        validatePolicy(
            warmupIterations,
            measurementIterations,
            forks,
            sampleTimeMillis,
            allocationMeasurementRequired,
            requiredLayers,
            fixedWorkEndToEndRequired,
            fixedTimeDiagnosticRequired,
            maximumPinnedRegressionPermille,
            canonicalEvidenceParityRequired,
            referenceFallbackRequired);
        EvolutionGenome.requireSha256(contentHash, "contentHash");
        String expected = EvolutionGenome.hash(render(
            benchmarkRevisionHash,
            benchmarkSuiteHash,
            runtimeEnvironmentHash,
            warmupIterations,
            measurementIterations,
            forks,
            sampleTimeMillis,
            allocationMeasurementRequired,
            requiredLayers,
            fixedWorkEndToEndRequired,
            fixedTimeDiagnosticRequired,
            maximumPinnedRegressionPermille,
            canonicalEvidenceParityRequired,
            referenceFallbackRequired,
            null));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "performance-plan contentHash mismatch");
        }
    }

    public static EvolutionRewriteProgramPerformancePlan create(
        String benchmarkRevisionHash,
        String benchmarkSuiteHash,
        String runtimeEnvironmentHash,
        int warmupIterations,
        int measurementIterations,
        int forks,
        int sampleTimeMillis,
        int maximumPinnedRegressionPermille
    ) {
        EvolutionGenome.requireSha256(
            benchmarkRevisionHash, "benchmarkRevisionHash");
        EvolutionGenome.requireSha256(
            benchmarkSuiteHash, "benchmarkSuiteHash");
        EvolutionGenome.requireSha256(
            runtimeEnvironmentHash, "runtimeEnvironmentHash");
        List<MeasurementLayer> layers = canonicalLayers(
            Arrays.asList(MeasurementLayer.values()));
        validatePolicy(
            warmupIterations,
            measurementIterations,
            forks,
            sampleTimeMillis,
            true,
            layers,
            true,
            true,
            maximumPinnedRegressionPermille,
            true,
            true);
        String hash = EvolutionGenome.hash(render(
            benchmarkRevisionHash,
            benchmarkSuiteHash,
            runtimeEnvironmentHash,
            warmupIterations,
            measurementIterations,
            forks,
            sampleTimeMillis,
            true,
            layers,
            true,
            true,
            maximumPinnedRegressionPermille,
            true,
            true,
            null));
        return new EvolutionRewriteProgramPerformancePlan(
            SCHEMA,
            benchmarkRevisionHash,
            benchmarkSuiteHash,
            runtimeEnvironmentHash,
            warmupIterations,
            measurementIterations,
            forks,
            sampleTimeMillis,
            true,
            layers,
            true,
            true,
            maximumPinnedRegressionPermille,
            true,
            true,
            hash);
    }

    public String toCanonicalJson() {
        return render(
            benchmarkRevisionHash,
            benchmarkSuiteHash,
            runtimeEnvironmentHash,
            warmupIterations,
            measurementIterations,
            forks,
            sampleTimeMillis,
            allocationMeasurementRequired,
            requiredLayers,
            fixedWorkEndToEndRequired,
            fixedTimeDiagnosticRequired,
            maximumPinnedRegressionPermille,
            canonicalEvidenceParityRequired,
            referenceFallbackRequired,
            contentHash);
    }

    private static List<MeasurementLayer> canonicalLayers(
        List<MeasurementLayer> values
    ) {
        Objects.requireNonNull(values, "requiredLayers");
        List<MeasurementLayer> result = values.stream()
            .map(value -> Objects.requireNonNull(value, "measurement layer"))
            .sorted(Comparator.comparing(Enum::name))
            .toList();
        if (new HashSet<>(result).size() != result.size()) {
            throw new IllegalArgumentException(
                "performance measurement layers must be unique");
        }
        return List.copyOf(result);
    }

    private static void validatePolicy(
        int warmupIterations,
        int measurementIterations,
        int forks,
        int sampleTimeMillis,
        boolean allocationMeasurementRequired,
        List<MeasurementLayer> requiredLayers,
        boolean fixedWorkEndToEndRequired,
        boolean fixedTimeDiagnosticRequired,
        int maximumPinnedRegressionPermille,
        boolean canonicalEvidenceParityRequired,
        boolean referenceFallbackRequired
    ) {
        if (warmupIterations < 1
                || measurementIterations < 3
                || forks < 1
                || sampleTimeMillis < 100) {
            throw new IllegalArgumentException(
                "performance sampling policy is too weak");
        }
        List<MeasurementLayer> all = canonicalLayers(
            Arrays.asList(MeasurementLayer.values()));
        if (!requiredLayers.equals(all)) {
            throw new IllegalArgumentException(
                "performance plan must cover every required layer");
        }
        if (!allocationMeasurementRequired
                || !fixedWorkEndToEndRequired
                || !fixedTimeDiagnosticRequired
                || !canonicalEvidenceParityRequired
                || !referenceFallbackRequired) {
            throw new IllegalArgumentException(
                "performance plan must retain allocation, end-to-end, parity and fallback controls");
        }
        if (maximumPinnedRegressionPermille < 0
                || maximumPinnedRegressionPermille > 250) {
            throw new IllegalArgumentException(
                "pinned runtime regression threshold must be in [0,250] permille");
        }
    }

    private static String render(
        String benchmarkRevisionHash,
        String benchmarkSuiteHash,
        String runtimeEnvironmentHash,
        int warmupIterations,
        int measurementIterations,
        int forks,
        int sampleTimeMillis,
        boolean allocationMeasurementRequired,
        List<MeasurementLayer> requiredLayers,
        boolean fixedWorkEndToEndRequired,
        boolean fixedTimeDiagnosticRequired,
        int maximumPinnedRegressionPermille,
        boolean canonicalEvidenceParityRequired,
        boolean referenceFallbackRequired,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("benchmarkRevisionHash", benchmarkRevisionHash)
            .property("benchmarkSuiteHash", benchmarkSuiteHash)
            .property("runtimeEnvironmentHash", runtimeEnvironmentHash)
            .property("warmupIterations", warmupIterations)
            .property("measurementIterations", measurementIterations)
            .property("forks", forks)
            .property("sampleTimeMillis", sampleTimeMillis)
            .property("allocationMeasurementRequired",
                allocationMeasurementRequired)
            .stringArray("requiredLayers", requiredLayers.stream()
                .map(Enum::name).toList())
            .property("fixedWorkEndToEndRequired",
                fixedWorkEndToEndRequired)
            .property("fixedTimeDiagnosticRequired",
                fixedTimeDiagnosticRequired)
            .property("maximumPinnedRegressionPermille",
                maximumPinnedRegressionPermille)
            .property("canonicalEvidenceParityRequired",
                canonicalEvidenceParityRequired)
            .property("referenceFallbackRequired", referenceFallbackRequired)
            .property("scientificResourceAuthority",
                "CANONICAL_PRIMITIVE_AND_TOTAL_WORK_LEDGER")
            .property("wallClockRole",
                "ENVIRONMENT_QUALIFIED_ENGINEERING_DIAGNOSTIC");
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    public enum MeasurementLayer {
        EXECUTOR,
        AST_MATCH_AND_REWRITE,
        CANONICALIZATION,
        DEDUPLICATION,
        FRONTIER,
        EXACT_AUDIT,
        END_TO_END
    }
}
