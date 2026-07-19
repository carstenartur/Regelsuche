package de.regelsuche.evolution;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.evolution.EvolutionPopulationEngine.CandidateEvaluation;
import de.regelsuche.evolution.EvolutionPopulationEngine.EvaluationStatus;
import de.regelsuche.evolution.EvolutionPopulationEngine.GenerationOutcome;
import de.regelsuche.evolution.EvolutionPopulationEngine.GenerationReport;
import de.regelsuche.evolution.EvolutionPopulationEngine.LineageEdge;
import de.regelsuche.evolution.EvolutionPopulationEngine.MutationRejection;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Strict JSON replay codec for canonical evolution generation reports. */
public final class EvolutionGenerationReportCodec {
    private static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public String write(GenerationReport report) {
        return Objects.requireNonNull(report, "report").toCanonicalJson();
    }

    public Path write(Path output, GenerationReport report) {
        Objects.requireNonNull(output, "output");
        try {
            Path parent = output.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(output, write(report), StandardCharsets.UTF_8);
            return output;
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Unable to write evolution generation report", exception);
        }
    }

    public GenerationReport read(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(
                "generation report JSON must not be blank");
        }
        try {
            return toReport(JSON.readValue(json, ReportDto.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "Invalid evolution generation report JSON", exception);
        }
    }

    public GenerationReport read(Path input) {
        Objects.requireNonNull(input, "input");
        try {
            return read(Files.readString(input, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                "Unable to read evolution generation report", exception);
        }
    }

    private static GenerationReport toReport(ReportDto dto) {
        Objects.requireNonNull(dto, "generation report payload");
        List<CandidateEvaluation> candidates = list(dto.candidates).stream()
            .map(EvolutionGenerationReportCodec::toCandidate)
            .toList();
        List<LineageEdge> lineage = list(dto.acceptedLineage).stream()
            .map(item -> new LineageEdge(
                item.parentGenomeHash,
                item.childGenomeHash,
                item.childAlphaStructuralHash,
                enumValue(EvolutionMutationKind.class, item.mutationKind),
                item.proposalKey))
            .toList();
        List<MutationRejection> rejections = list(dto.rejectedMutations).stream()
            .map(item -> new MutationRejection(
                item.parentGenomeHash,
                item.ordinal,
                enumValue(EvolutionMutationKind.class, item.mutationKind),
                item.proposalKey,
                item.childGenomeHash,
                list(item.blockers)))
            .toList();
        BudgetDto budget = require(dto.budgetCumulative, "budgetCumulative");
        return new GenerationReport(
            dto.schema,
            dto.generation,
            candidates,
            list(dto.selectedGenomeHashes),
            lineage,
            rejections,
            dto.distinctAlphaStructures,
            budget.mutationAttempts,
            budget.trainEvaluations,
            enumValue(GenerationOutcome.class, dto.outcome),
            dto.contentHash);
    }

    private static CandidateEvaluation toCandidate(CandidateDto dto) {
        Objects.requireNonNull(dto, "candidate evaluation");
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
    public static final class ReportDto {
        public String schema;
        public int generation;
        public List<CandidateDto> candidates;
        public List<String> selectedGenomeHashes;
        public List<LineageDto> acceptedLineage;
        public List<RejectionDto> rejectedMutations;
        public int distinctAlphaStructures;
        public BudgetDto budgetCumulative;
        public String outcome;
        public String contentHash;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class CandidateDto {
        public String genomeHash;
        public String alphaStructuralHash;
        public Map<String, Integer> rawComponents;
        public int weightedScorePermille;
        public String status;
        public List<String> blockers;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class LineageDto {
        public String parentGenomeHash;
        public String childGenomeHash;
        public String childAlphaStructuralHash;
        public String mutationKind;
        public String proposalKey;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class RejectionDto {
        public String parentGenomeHash;
        public int ordinal;
        public String mutationKind;
        public String proposalKey;
        public String childGenomeHash;
        public List<String> blockers;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class BudgetDto {
        public int mutationAttempts;
        public int trainEvaluations;
    }
}
