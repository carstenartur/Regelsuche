package de.regelsuche.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.regelsuche.mining.GeneralizedPattern;
import de.regelsuche.mining.PatternGeneralizer;
import de.regelsuche.mining.SuccessfulTransformationPath;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Mines generalized rule hypotheses from grouped candidate-store support examples. */
final class PatternHypothesisMiner {
    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final PatternGeneralizer generalizer = new PatternGeneralizer();

    PatternHypothesisReport mine(DiscoveryCandidateStore.CandidateStoreReport storeReport) {
        List<DiscoveryCandidateStore.CandidateEntry> entries = storeReport == null
            ? List.of()
            : storeReport.candidates();
        Map<String, List<SupportExample>> byFamilyAndOperator = entries.stream()
            .flatMap(entry -> supportExamples(entry).stream())
            .collect(Collectors.groupingBy(
                SupportExample::clusterKey,
                LinkedHashMap::new,
                Collectors.toList()
            ));

        List<GeneralizedHypothesis> hypotheses = new ArrayList<>();
        List<RejectedCluster> rejected = new ArrayList<>();
        for (Map.Entry<String, List<SupportExample>> entry : byFamilyAndOperator.entrySet()) {
            List<SupportExample> examples = entry.getValue().stream()
                .sorted(Comparator.comparing(SupportExample::exampleId))
                .toList();
            if (examples.size() < 2) {
                rejected.add(reject(entry.getKey(), examples, "support-count<2"));
                continue;
            }
            List<SuccessfulTransformationPath> paths = examples.stream()
                .map(this::toPath)
                .toList();
            Optional<GeneralizedPattern> generalized = generalizer.generalize(paths);
            if (generalized.isEmpty()) {
                rejected.add(reject(entry.getKey(), examples, "generalizer returned no compatible pattern"));
                continue;
            }
            GeneralizedPattern pattern = generalized.orElseThrow();
            SupportExample first = examples.getFirst();
            hypotheses.add(new GeneralizedHypothesis(
                hypothesisId(first.family(), first.operatorId(), pattern.leftPattern(), pattern.rightPattern()),
                first.family(),
                first.operatorId(),
                pattern.leftPattern(),
                pattern.rightPattern(),
                examples.size(),
                examples.stream().map(SupportExample::exampleId).distinct().toList(),
                examples.stream().map(SupportExample::candidateId).distinct().toList(),
                pattern.parameterRelations(),
                pattern.expressionPlaceholderValues(),
                "GENERALIZED_FROM_SUPPORT"
            ));
        }
        return new PatternHypothesisReport(
            hypotheses.stream()
                .sorted(Comparator.comparing(GeneralizedHypothesis::hypothesisId))
                .toList(),
            rejected.stream()
                .sorted(Comparator.comparing(RejectedCluster::clusterKey))
                .toList()
        );
    }

    PatternHypothesisReport write(Path outputDirectory, DiscoveryCandidateStore.CandidateStoreReport storeReport) {
        try {
            Files.createDirectories(outputDirectory);
            PatternHypothesisReport report = mine(storeReport);
            AtomicJsonFile.writeUtf8(
                outputDirectory.resolve("pattern-hypotheses.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)
            );
            Files.writeString(
                outputDirectory.resolve("operator-suggestions.md"),
                renderOperatorSuggestions(report),
                StandardCharsets.UTF_8
            );
            Files.writeString(
                outputDirectory.resolve("pattern-hypotheses.md"),
                renderPatternHypotheses(report),
                StandardCharsets.UTF_8
            );
            return report;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    String renderOperatorSuggestions(PatternHypothesisReport report) {
        StringBuilder out = new StringBuilder("# Operator suggestions from generalized hypotheses\n\n");
        if (report.hypotheses().isEmpty()) {
            out.append("- none\n");
        } else {
            for (GeneralizedHypothesis hypothesis : report.hypotheses()) {
                out.append("- ").append(escape(hypothesis.hypothesisId()))
                    .append(": `").append(escapeInlineCode(hypothesis.leftPattern()))
                    .append(" -> ").append(escapeInlineCode(hypothesis.rightPattern()))
                    .append("` (family=").append(escape(hypothesis.family()))
                    .append(", operator=").append(escape(orDash(hypothesis.operatorId())))
                    .append(", support=").append(hypothesis.supportCount())
                    .append(", examples=").append(escape(String.join(", ", hypothesis.supportingExampleIds())))
                    .append(")\n");
            }
        }
        if (!report.rejectedClusters().isEmpty()) {
            out.append("\n## Rejected clusters\n\n");
            for (RejectedCluster rejected : report.rejectedClusters()) {
                out.append("- ").append(escape(rejected.clusterKey()))
                    .append(": ").append(escape(rejected.reason()))
                    .append(" (support=").append(rejected.supportCount())
                    .append(")\n");
            }
        }
        return out.toString();
    }

    String renderPatternHypotheses(PatternHypothesisReport report) {
        StringBuilder out = new StringBuilder("# Pattern hypotheses\n\n");
        out.append("| Hypothesis | Family | Operator | Support | Left pattern | Right pattern | Examples |\n");
        out.append("| --- | --- | --- | ---: | --- | --- | --- |\n");
        for (GeneralizedHypothesis hypothesis : report.hypotheses()) {
            out.append("| ").append(escape(hypothesis.hypothesisId()))
                .append(" | ").append(escape(hypothesis.family()))
                .append(" | ").append(escape(orDash(hypothesis.operatorId())))
                .append(" | ").append(hypothesis.supportCount())
                .append(" | `").append(escapeInlineCode(hypothesis.leftPattern())).append("`")
                .append(" | `").append(escapeInlineCode(hypothesis.rightPattern())).append("`")
                .append(" | ").append(escape(String.join(", ", hypothesis.supportingExampleIds())))
                .append(" |\n");
        }
        out.append("\n## Parameter relations\n\n");
        for (GeneralizedHypothesis hypothesis : report.hypotheses()) {
            out.append("### ").append(escape(hypothesis.hypothesisId())).append("\n\n");
            if (hypothesis.parameterRelations().isEmpty()) {
                out.append("- none\n");
            } else {
                for (String relation : hypothesis.parameterRelations()) {
                    out.append("- ").append(escape(relation)).append('\n');
                }
            }
            out.append('\n');
        }
        return out.toString();
    }

    private List<SupportExample> supportExamples(DiscoveryCandidateStore.CandidateEntry entry) {
        if (entry.lifecycleStatus() == DiscoveryCandidateStore.CandidateLifecycleStatus.REJECTED) {
            return List.of();
        }
        if (entry.operatorId().isBlank()) {
            return List.of();
        }
        List<SupportExample> examples = new ArrayList<>();
        for (DiscoveryCandidateStore.ConcreteExample example : entry.concreteExamples()) {
            if (example.lifecycleStatus() == DiscoveryCandidateStore.CandidateLifecycleStatus.REJECTED) {
                continue;
            }
            if ("DISAGREE".equalsIgnoreCase(example.oracleStatus())) {
                continue;
            }
            examples.add(new SupportExample(
                example.exampleId(),
                entry.candidateId(),
                entry.family(),
                entry.operatorId(),
                example.inputExpression(),
                example.targetExpression(),
                entry.rulePath(),
                example.oracleStatus()
            ));
        }
        return examples;
    }

    private SuccessfulTransformationPath toPath(SupportExample example) {
        int beforeCost = Math.max(1, example.inputExpression().replaceAll("\\s+", "").length() + 8);
        int afterCost = Math.max(1, example.targetExpression().replaceAll("\\s+", "").length());
        return new SuccessfulTransformationPath(
            example.exampleId(),
            example.inputExpression(),
            example.targetExpression(),
            List.of(example.inputExpression(), example.targetExpression()),
            example.rulePath().isEmpty() ? List.of(example.operatorId()) : example.rulePath(),
            new ExpressionScore(beforeCost, 0, 0, 0, 0),
            new ExpressionScore(afterCost, 0, 0, 0, 0),
            true,
            example.oracleStatus(),
            Map.of("family", example.family(), "operator", example.operatorId())
        );
    }

    private RejectedCluster reject(String clusterKey, List<SupportExample> examples, String reason) {
        return new RejectedCluster(
            clusterKey,
            examples.size(),
            examples.stream().map(SupportExample::exampleId).distinct().toList(),
            reason
        );
    }

    private String hypothesisId(String family, String operatorId, String leftPattern, String rightPattern) {
        String seed = (family == null ? "" : family) + "|" + (operatorId == null ? "" : operatorId)
            + "|" + leftPattern + "->" + rightPattern;
        return "hypothesis-" + Integer.toHexString(seed.hashCode());
    }

    private String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    private String escapeInlineCode(String value) {
        return escape(value).replace("`", "\\`");
    }

    record PatternHypothesisReport(
        List<GeneralizedHypothesis> hypotheses,
        List<RejectedCluster> rejectedClusters
    ) {
        PatternHypothesisReport {
            hypotheses = hypotheses == null ? List.of() : List.copyOf(hypotheses);
            rejectedClusters = rejectedClusters == null ? List.of() : List.copyOf(rejectedClusters);
        }
    }

    record GeneralizedHypothesis(
        String hypothesisId,
        String family,
        String operatorId,
        String leftPattern,
        String rightPattern,
        int supportCount,
        List<String> supportingExampleIds,
        List<String> supportingCandidateIds,
        List<String> parameterRelations,
        Map<String, List<String>> expressionPlaceholderValues,
        String confidence
    ) {
        GeneralizedHypothesis {
            supportingExampleIds = supportingExampleIds == null ? List.of() : List.copyOf(supportingExampleIds);
            supportingCandidateIds = supportingCandidateIds == null ? List.of() : List.copyOf(supportingCandidateIds);
            parameterRelations = parameterRelations == null ? List.of() : List.copyOf(parameterRelations);
            expressionPlaceholderValues = expressionPlaceholderValues == null ? Map.of() : Map.copyOf(expressionPlaceholderValues);
            confidence = confidence == null ? "" : confidence;
        }
    }

    record RejectedCluster(String clusterKey, int supportCount, List<String> exampleIds, String reason) {
        RejectedCluster {
            exampleIds = exampleIds == null ? List.of() : List.copyOf(exampleIds);
            reason = reason == null ? "" : reason;
        }
    }

    private record SupportExample(
        String exampleId,
        String candidateId,
        String family,
        String operatorId,
        String inputExpression,
        String targetExpression,
        List<String> rulePath,
        String oracleStatus
    ) {
        private SupportExample {
            exampleId = exampleId == null ? "" : exampleId;
            candidateId = candidateId == null ? "" : candidateId;
            family = family == null ? "" : family;
            operatorId = operatorId == null ? "" : operatorId;
            inputExpression = inputExpression == null ? "" : inputExpression;
            targetExpression = targetExpression == null ? "" : targetExpression;
            rulePath = rulePath == null ? List.of() : List.copyOf(rulePath);
            oracleStatus = oracleStatus == null ? "UNAVAILABLE" : oracleStatus;
        }

        private String clusterKey() {
            return family + "|" + operatorId;
        }
    }
}
