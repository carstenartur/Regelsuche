package de.regelsuche.evolution;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.evolution.EvolutionPopulationEngine.CandidateEvaluation;
import de.regelsuche.evolution.EvolutionPopulationEngine.EvaluationStatus;
import de.regelsuche.evolution.EvolutionPopulationEngine.GenerationReport;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Strict JSON round-trip codec for population checkpoints. */
public final class EvolutionPopulationCheckpointCodec {
    private static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final EvolutionGenerationReportCodec reportCodec =
        new EvolutionGenerationReportCodec();
    private final EvolutionGenomeCodec genomeCodec = new EvolutionGenomeCodec();

    public String write(EvolutionPopulationCheckpoint checkpoint) {
        return Objects.requireNonNull(checkpoint, "checkpoint")
            .toCanonicalJson();
    }

    public Path write(Path output, EvolutionPopulationCheckpoint checkpoint) {
        Objects.requireNonNull(output, "output");
        try {
            Path parent = output.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(output, write(checkpoint), StandardCharsets.UTF_8);
            return output;
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Unable to write evolution population checkpoint", exception);
        }
    }

    public EvolutionPopulationCheckpoint read(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(
                "population checkpoint JSON must not be blank");
        }
        try {
            return toCheckpoint(JSON.readValue(json, CheckpointDto.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "Invalid evolution population checkpoint JSON", exception);
        }
    }

    public EvolutionPopulationCheckpoint read(Path input) {
        Objects.requireNonNull(input, "input");
        try {
            return read(Files.readString(input, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                "Unable to read evolution population checkpoint", exception);
        }
    }

    private EvolutionPopulationCheckpoint toCheckpoint(CheckpointDto dto) {
        Objects.requireNonNull(dto, "checkpoint payload");
        List<GenerationReport> reports = list(dto.generationReportDocuments)
            .stream()
            .map(reportCodec::read)
            .toList();
        List<EvolutionGenome> population = list(dto.currentPopulationDocuments)
            .stream()
            .map(genomeCodec::read)
            .toList();
        List<CandidateEvaluation> evaluations = list(dto.evaluationCache)
            .stream()
            .map(EvolutionPopulationCheckpointCodec::toEvaluation)
            .toList();
        BudgetDto budget = require(dto.budgetUsed, "budgetUsed");
        return new EvolutionPopulationCheckpoint(
            dto.schema,
            dto.studyPlanHash,
            list(dto.seedGenomeHashes),
            dto.mutationCatalogHash,
            dto.evaluatorConfigurationHash,
            dto.completedGenerations,
            reports,
            population,
            evaluations,
            budget.mutationAttempts,
            budget.trainEvaluations,
            dto.validationStatus,
            dto.finalTestStatus,
            dto.proofStatus,
            dto.externalNoveltyStatus,
            dto.promotionStatus,
            dto.publicEvidenceStatus,
            dto.contentHash);
    }

    private static CandidateEvaluation toEvaluation(EvaluationDto dto) {
        Objects.requireNonNull(dto, "cached evaluation");
        EnumMap<FitnessComponent, Integer> components =
            new EnumMap<>(FitnessComponent.class);
        map(dto.rawComponents).forEach((key, value) -> components.put(
            enumValue(FitnessComponent.class, key),
            Objects.requireNonNull(value, "fitness component value")));
        return new CandidateEvaluation(
            dto.genomeHash,
            dto.alphaStructuralHash,
            components,
            dto.weightedScorePermille,
            enumValue(EvaluationStatus.class, dto.status),
            list(dto.blockers));
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

    private static <K, V> Map<K, V> map(Map<K, V> values) {
        return values == null ? Map.of() : Map.copyOf(values);
    }

    private static <E extends Enum<E>> E enumValue(
        Class<E> type,
        String value
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                type.getSimpleName() + " value is required");
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
    public static final class CheckpointDto {
        public String schema;
        public String studyPlanHash;
        public List<String> seedGenomeHashes;
        public String mutationCatalogHash;
        public String evaluatorConfigurationHash;
        public int completedGenerations;
        public List<String> generationReportDocuments;
        public List<String> currentPopulationDocuments;
        public List<EvaluationDto> evaluationCache;
        public BudgetDto budgetUsed;
        public String validationStatus;
        public String finalTestStatus;
        public String proofStatus;
        public String externalNoveltyStatus;
        public String promotionStatus;
        public String publicEvidenceStatus;
        public String contentHash;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class EvaluationDto {
        public String genomeHash;
        public String alphaStructuralHash;
        public Map<String, Integer> rawComponents;
        public int weightedScorePermille;
        public String status;
        public List<String> blockers;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class BudgetDto {
        public int mutationAttempts;
        public int trainEvaluations;
    }
}
