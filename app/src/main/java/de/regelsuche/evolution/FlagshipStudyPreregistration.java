package de.regelsuche.evolution;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.benchmark.HistoricalRewriteArchitectureDecision;
import de.regelsuche.benchmark.HistoricalRewriteArchitectureDecision.VerifiedDecision;
import de.regelsuche.discovery.representation.RepresentationDiscoveryInformationBoundary.Track;
import de.regelsuche.evolution.EvolutionRewriteProgramBaselineAblationPlan.TrackKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Public, outcome-free inputs reviewed against the sealed split commitments. */
public record FlagshipStudyPreregistration(
    String schema, String validationCommitmentHash, String finalTestCommitmentHash,
    String architectureDecisionHash, Map<Component, Revision> components,
    Map<String, Tranche> tranches, Map<TrackKind, ComparatorIdentity> baselines,
    Endpoint endpoint
) {
    public static final String SCHEMA = "regelsuche.flagship-study-preregistration/v1";
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    public FlagshipStudyPreregistration {
        if (!SCHEMA.equals(schema)) throw new IllegalArgumentException("unsupported study preregistration");
        hashes(validationCommitmentHash, finalTestCommitmentHash, architectureDecisionHash);
        components = complete(components, Set.of(Component.values()));
        tranches = Map.copyOf(tranches);
        baselines = complete(baselines, Set.of(TrackKind.values()));
        Objects.requireNonNull(endpoint);
    }

    public static FlagshipStudyPreregistration read(Path file) {
        try {
            return JSON.readValue(Files.readString(file), FlagshipStudyPreregistration.class);
        } catch (IOException ex) {
            throw new IllegalArgumentException("invalid public study preregistration", ex);
        }
    }

    public static VerifiedDecision readDecision(Path file) {
        try {
            return HistoricalRewriteArchitectureDecision.readFrozenDecision(Files.readString(file).stripTrailing());
        } catch (IOException ex) {
            throw new IllegalArgumentException("unable to read architecture decision", ex);
        }
    }

    public void requireInputs(VerifiedDecision decision, EvolutionRewriteProgramHeldOutCommitment validation,
                              EvolutionRewriteProgramHeldOutCommitment finalTest) {
        if (!architectureDecisionHash.equals(decision.contentHash())
                || !validationCommitmentHash.equals(validation.contentHash())
                || !finalTestCommitmentHash.equals(finalTest.contentHash())) {
            throw new IllegalArgumentException("decision or sealed split differs from reviewed input");
        }
        complete(tranches, decision.dispositions().keySet());
        decision.dispositions().forEach((track, disposition) -> {
            Tranche tranche = tranches.get(track);
            boolean deferred = disposition.startsWith("DEFERRED_");
            if (deferred != (tranche instanceof Deferred)) {
                throw new IllegalArgumentException("characterization or deferral differs for " + track);
            }
        });
        endpoint.thresholds().requireCompatibleWithFinalSurface(finalTest);
    }

    public String canonicalJson() {
        return json(this);
    }

    public String contentHash() {
        return EvolutionGenome.hash(canonicalJson());
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException ex) {
            throw new IllegalStateException("cannot render study preregistration", ex);
        }
    }

    private static <K, V> Map<K, V> complete(Map<K, V> values, Set<K> keys) {
        if (!values.keySet().equals(keys)) throw new IllegalArgumentException("missing or unexpected axes: " + keys);
        return Map.copyOf(values);
    }

    private static void hashes(String... values) {
        for (String value : values) EvolutionGenome.requireSha256(value, "artifact identity");
    }

    public enum Component {
        SCALAR_VALUE_IDENTITY, SEARCH_QUOTIENT_RETAINED_LABELS, ASSUMPTION_PROPAGATION,
        RULE_DIRECTION_AND_COVERAGE, MATCHER_CANDIDATE_ORDER, SATURATION_RULE_COVERAGE,
        CERTIFICATE_BACKEND, PROGRAM_SOURCE, TOPOLOGY_INTERPRETER, SCHEDULER, TOTAL_WORK_ACCOUNTING,
        RUNTIME_ENVIRONMENT, BENCHMARK_SUITE
    }

    public record Revision(String implementationHash, String characterizationHash) {
        public Revision { hashes(implementationHash, characterizationHash); }
    }

    public record ComparatorIdentity(String implementationHash, String configurationHash, String translationLossHash) {
        public ComparatorIdentity { hashes(implementationHash, configurationHash, translationLossHash); }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "status")
    @JsonSubTypes({@JsonSubTypes.Type(value = Included.class, name = "INCLUDED"),
                   @JsonSubTypes.Type(value = Deferred.class, name = "DEFERRED")})
    public sealed interface Tranche permits Included, Deferred { }

    public record Included(Revision revision) implements Tranche {
        public Included { Objects.requireNonNull(revision); }
    }

    public record Deferred(String knownLimitation, String corpusCoverageHash, String excludedClaimHash) implements Tranche {
        public Deferred {
            if (knownLimitation.isBlank()) throw new IllegalArgumentException("missing deferred capability");
            hashes(corpusCoverageHash, excludedClaimHash);
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "primary")
    @JsonSubTypes({@JsonSubTypes.Type(value = SearchWork.class, name = "SEARCH_WORK"),
                   @JsonSubTypes.Type(value = Representation.class, name = "REPRESENTATION_DISCOVERY")})
    public sealed interface Endpoint permits SearchWork, Representation {
        FlagshipEndpointThresholds thresholds();
    }

    public record SearchWork(EvolutionRewriteProgramAcceptanceThresholds thresholds) implements Endpoint {
        public SearchWork { Objects.requireNonNull(thresholds); }
    }

    public enum RepresentationAxis {
        CANDIDATE_TAXONOMY, VALUE_AND_OCCURRENCE_IDENTITY, COMPRESSION_METRICS_AND_PROFILE,
        ANTI_ALIAS_EXPANDED_COST, KNOWN_STRUCTURE_CATALOGUE, CONSEQUENCES, RECOGNITION_SCOPE,
        CAPABILITY_FRONTIER_AND_MATERIAL_UNLOCK, RANKING_AND_QUALITY_DIVERSITY,
        GENERALIZED_BRIDGE_AND_HELD_OUT_REUSE, CERTIFICATE_POLICY
    }

    public enum RepresentationBaseline {
        INFORMATION_EQUIVALENT_ENUMERATION, RANDOMIZED_VALID_CANDIDATE, HELD_OUT_CROSS_FAMILY_TRANSFER,
        NO_CROSS_OBSERVATION_GENERALIZATION, NO_COUNTEREXAMPLE_REFINEMENT, NO_PROJECT_NOVELTY_SCHEDULING
    }

    public record Representation(Track informationTrack, Map<RepresentationAxis, String> policyRoots,
                                 Map<RepresentationBaseline, ComparatorIdentity> comparisonBaselines,
                                 RepresentationThresholds thresholds) implements Endpoint {
        public Representation {
            Objects.requireNonNull(informationTrack);
            policyRoots = complete(policyRoots, Set.of(RepresentationAxis.values()));
            policyRoots.values().forEach(value -> hashes(value));
            comparisonBaselines = complete(comparisonBaselines, Set.of(RepresentationBaseline.values()));
            Objects.requireNonNull(thresholds);
        }
    }

    /** An eligible case meets all three gain thresholds under the same work budget and certificate policy. */
    public record RepresentationThresholds(
        int minimumExpandedCostReductionPermille, int minimumNewCapabilitiesPerCase,
        int minimumHeldOutReusesPerCase, int minimumEligibleFinalCases, int minimumDistinctFamilies,
        long maximumWorkPerCase, int maximumCorrectnessRegressions, int maximumAssumptionRegressions,
        int requiredCleanReproductions, boolean requirePinnedReproduction, NullPolicy nullPolicy
    ) implements FlagshipEndpointThresholds {
        public RepresentationThresholds {
            if (minimumExpandedCostReductionPermille < 1 || minimumExpandedCostReductionPermille > 1000
                    || minimumNewCapabilitiesPerCase < 1 || minimumHeldOutReusesPerCase < 1
                    || minimumEligibleFinalCases < 1 || minimumDistinctFamilies < 1
                    || minimumDistinctFamilies > minimumEligibleFinalCases || maximumWorkPerCase < 1) {
                throw new IllegalArgumentException("infeasible representation gain or work limits");
            }
            if (maximumCorrectnessRegressions != 0 || maximumAssumptionRegressions != 0
                    || requiredCleanReproductions < 2 || !requirePinnedReproduction || nullPolicy == null) {
                throw new IllegalArgumentException("invalid representation no-regression or reproduction criteria");
            }
        }

        public enum NullPolicy { REPORT_ALL_CASES_AND_BASELINES_WITH_UNMET_CRITERIA }

        @Override public void requireCompatibleWithFinalSurface(EvolutionRewriteProgramHeldOutCommitment finalTest) {
            long families = finalTest.cases().stream().map(
                EvolutionRewriteProgramHeldOutCommitment.CaseCommitment::familyCommitmentHash).distinct().count();
            if (finalTest.split() != EvolutionRewriteProgramHeldOutCommitment.Split.FINAL_TEST
                    || minimumEligibleFinalCases > finalTest.cases().size() || minimumDistinctFamilies > families) {
                throw new IllegalArgumentException("representation gate exceeds committed FINAL surface");
            }
        }

        @Override public String toCanonicalJson() { return json(this); }
        @Override public String contentHash() { return EvolutionGenome.hash(toCanonicalJson()); }
    }
}
