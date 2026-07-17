package de.regelsuche.evolution;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.assumption.Assumption;
import de.regelsuche.evolution.EvolutionGenome.AssumptionTemplate;
import de.regelsuche.evolution.EvolutionGenome.EvidenceObligation;
import de.regelsuche.evolution.EvolutionGenome.FeatureWeight;
import de.regelsuche.evolution.EvolutionGenome.FitnessSignal;
import de.regelsuche.evolution.EvolutionGenome.GuardPolicy;
import de.regelsuche.evolution.EvolutionGenome.Objective;
import de.regelsuche.evolution.EvolutionGenome.ResourceBudget;
import de.regelsuche.evolution.EvolutionGenome.RewriteGene;
import de.regelsuche.evolution.EvolutionGenome.SourceSplit;
import de.regelsuche.evolution.EvolutionGenome.TrainingScope;
import de.regelsuche.transform.RewriteKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Strict JSON round-trip codec for replaying versioned evolution genomes. */
public final class EvolutionGenomeCodec {
    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public String write(EvolutionGenome genome) {
        return Objects.requireNonNull(genome, "genome").toCanonicalJson();
    }

    public Path write(Path output, EvolutionGenome genome) {
        Objects.requireNonNull(output, "output");
        try {
            Path parent = output.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(output, write(genome), StandardCharsets.UTF_8);
            return output;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write evolution genome", exception);
        }
    }

    public EvolutionGenome read(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("genome JSON must not be blank");
        }
        try {
            return toGenome(JSON.readValue(json, GenomeDto.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid evolution genome JSON", exception);
        }
    }

    public EvolutionGenome read(Path input) {
        Objects.requireNonNull(input, "input");
        try {
            return read(Files.readString(input, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to read evolution genome", exception);
        }
    }

    private static EvolutionGenome toGenome(GenomeDto dto) {
        Objects.requireNonNull(dto, "genome payload");
        if (!EvolutionGenome.SCHEMA.equals(dto.schema)) {
            throw new IllegalArgumentException("unsupported evolution genome schema");
        }
        TrainingScope scope = new TrainingScope(
            enumValue(SourceSplit.class, require(dto.trainingScope, "trainingScope").sourceSplit),
            dto.trainingScope.corpusHash,
            dto.trainingScope.familyPartitionHash,
            dto.trainingScope.signaturePartitionHash,
            dto.trainingScope.featureSchemaHash);
        List<RewriteGene> rewrites = list(dto.rewrites).stream()
            .map(EvolutionGenomeCodec::toRewrite)
            .toList();
        List<FeatureWeight> features = list(dto.rankingFeatures).stream()
            .map(item -> new FeatureWeight(
                enumValue(FitnessSignal.class, item.signal),
                item.weightPermille))
            .toList();
        GuardDto guards = require(dto.guardPolicy, "guardPolicy");
        GuardPolicy guardPolicy = new GuardPolicy(
            guards.rejectCycles,
            guards.rejectUnboundedGrowth,
            guards.requireApplicabilityChecks,
            guards.enforceDuplicateSuppression,
            guards.deterministicTieBreaking);
        BudgetDto budget = require(dto.budget, "budget");
        ResourceBudget resourceBudget = new ResourceBudget(
            budget.maxProgramLength,
            budget.maxAstNodes,
            budget.maxAstGrowthPerStep,
            budget.maxApplicationsPerPath,
            budget.maxCandidatesPerState);
        return new EvolutionGenome(
            dto.schema,
            enumValue(Objective.class, dto.objective),
            scope,
            rewrites,
            features,
            guardPolicy,
            resourceBudget,
            list(dto.requiredCapabilities),
            list(dto.seedGenomeHashes),
            dto.alphaStructuralHash,
            dto.contentHash);
    }

    private static RewriteGene toRewrite(RewriteDto dto) {
        Objects.requireNonNull(dto, "rewrite entry");
        List<AssumptionTemplate> assumptions = list(dto.assumptions).stream()
            .map(item -> new AssumptionTemplate(
                enumValue(Assumption.Kind.class, item.kind),
                item.expression,
                list(item.symbols)))
            .toList();
        List<EvidenceObligation> obligations = list(dto.evidenceObligations).stream()
            .map(value -> enumValue(EvidenceObligation.class, value))
            .toList();
        return new RewriteGene(
            dto.geneId,
            dto.sourcePattern,
            dto.targetPattern,
            enumValue(RewriteKind.class, dto.kind),
            dto.reversible,
            dto.estimatedCostDelta,
            dto.maxApplicationsPerPath,
            dto.maxAstGrowth,
            assumptions,
            obligations);
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(type.getSimpleName() + " value is required");
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "Unsupported " + type.getSimpleName() + ": " + value,
                exception);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class GenomeDto {
        public String schema;
        public String objective;
        public TrainingScopeDto trainingScope;
        public List<RewriteDto> rewrites;
        public List<FeatureDto> rankingFeatures;
        public GuardDto guardPolicy;
        public BudgetDto budget;
        public List<String> requiredCapabilities;
        public List<String> seedGenomeHashes;
        public String alphaStructuralHash;
        public String contentHash;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class TrainingScopeDto {
        public String sourceSplit;
        public String corpusHash;
        public String familyPartitionHash;
        public String signaturePartitionHash;
        public String featureSchemaHash;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class RewriteDto {
        public String geneId;
        public String sourcePattern;
        public String targetPattern;
        public String kind;
        public boolean reversible;
        public int estimatedCostDelta;
        public int maxApplicationsPerPath;
        public int maxAstGrowth;
        public List<AssumptionDto> assumptions;
        public List<String> evidenceObligations;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class AssumptionDto {
        public String kind;
        public String expression;
        public List<String> symbols;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class FeatureDto {
        public String signal;
        public int weightPermille;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class GuardDto {
        public boolean rejectCycles;
        public boolean rejectUnboundedGrowth;
        public boolean requireApplicabilityChecks;
        public boolean enforceDuplicateSuppression;
        public boolean deterministicTieBreaking;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class BudgetDto {
        public int maxProgramLength;
        public int maxAstNodes;
        public int maxAstGrowthPerStep;
        public int maxApplicationsPerPath;
        public int maxCandidatesPerState;
    }
}
