package de.regelsuche.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

final class DiscoveryCandidateReportWriter {
    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    CandidateBundle write(Path outputDirectory, String campaignId, List<CandidateRecord> candidates) {
        try {
            Files.createDirectories(outputDirectory);
            CandidateBundle bundle = new CandidateBundle(
                campaignId,
                candidates.stream()
                    .map(candidate -> new CandidateView(candidate, stage(candidate)))
                    .toList()
            );
            AtomicJsonFile.writeUtf8(
                outputDirectory.resolve("discovery-candidates.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(bundle)
            );
            Files.writeString(
                outputDirectory.resolve("discovery-candidates.md"),
                renderCandidates(bundle),
                StandardCharsets.UTF_8
            );
            Files.writeString(
                outputDirectory.resolve("operator-suggestions.md"),
                renderOperatorSuggestions(bundle),
                StandardCharsets.UTF_8
            );
            Files.writeString(
                outputDirectory.resolve("macro-candidates.md"),
                renderMacroCandidates(bundle),
                StandardCharsets.UTF_8
            );
            return bundle;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private String stage(CandidateRecord candidate) {
        if (!candidate.success()) {
            return "observed";
        }
        if ("DISAGREE".equals(candidate.oracleStatus())) {
            return "candidate";
        }
        if (!"DEGRADED".equals(candidate.ablationStatus())) {
            return "candidate";
        }
        if (!candidate.operatorId().isBlank() || !candidate.packId().isBlank()) {
            return candidate.smallGraphMessage().isBlank() ? "public-evidence" : "promoted";
        }
        return "validated";
    }

    private String renderCandidates(CandidateBundle bundle) {
        StringBuilder out = new StringBuilder("# Discovery candidates\n\n");
        out.append("| Candidate | Family | Stage | Success | Oracle | Ablation | Source | Pack | Operator |\n");
        out.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (CandidateView candidate : bundle.candidates()) {
            out.append("| ").append(escape(candidate.id()))
                .append(" | ").append(escape(candidate.family()))
                .append(" | ").append(escape(candidate.stage()))
                .append(" | ").append(candidate.success() ? "yes" : "no")
                .append(" | ").append(escape(candidate.oracleStatus().toLowerCase(Locale.ROOT)))
                .append(" | ").append(escape(candidate.ablationStatus().toLowerCase(Locale.ROOT)))
                .append(" | ").append(escape(orDash(candidate.source())))
                .append(" | ").append(escape(orDash(candidate.packId())))
                .append(" | ").append(escape(orDash(candidate.operatorId())))
                .append(" |\n");
        }
        return out.toString();
    }

    private String renderOperatorSuggestions(CandidateBundle bundle) {
        StringBuilder out = new StringBuilder("# Operator suggestions\n\n");
        Map<String, List<CandidateView>> blockedByFamily = bundle.candidates().stream()
            .filter(candidate -> !candidate.success())
            .collect(Collectors.groupingBy(CandidateView::family, LinkedHashMap::new, Collectors.toList()));
        if (blockedByFamily.isEmpty()) {
            out.append("- none\n");
            return out.toString();
        }
        for (Map.Entry<String, List<CandidateView>> entry : blockedByFamily.entrySet()) {
            out.append("## ").append(escape(entry.getKey())).append("\n\n");
            for (CandidateView candidate : entry.getValue()) {
                out.append("- ").append(escape(candidate.id()))
                    .append(": investigate operator for ")
                    .append(escape(candidate.inputExpression()))
                    .append(" -> ")
                    .append(escape(candidate.targetExpression()))
                    .append('\n');
            }
            out.append('\n');
        }
        return out.toString();
    }

    private String renderMacroCandidates(CandidateBundle bundle) {
        StringBuilder out = new StringBuilder("# Macro candidates\n\n");
        List<CandidateView> macroCandidates = bundle.candidates().stream()
            .filter(candidate -> candidate.success())
            .filter(candidate -> candidate.rulePath().size() >= 2
                || "substitution".equals(candidate.family()))
            .toList();
        if (macroCandidates.isEmpty()) {
            out.append("- none\n");
            return out.toString();
        }
        for (CandidateView candidate : macroCandidates) {
            out.append("- ").append(escape(candidate.id()))
                .append(": ")
                .append(escape(String.join(" -> ", candidate.rulePath())))
                .append(" (stage=").append(escape(candidate.stage())).append(")\n");
        }
        return out.toString();
    }

    private String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    record CandidateBundle(String campaignId, List<CandidateView> candidates) {
        CandidateBundle {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    record CandidateRecord(
        String id,
        String family,
        String inputExpression,
        String targetExpression,
        boolean success,
        String oracleStatus,
        String ablationStatus,
        String source,
        String packId,
        String operatorId,
        List<String> rulePath,
        String smallGraphMessage
    ) {
        CandidateRecord {
            family = family == null ? "" : family;
            inputExpression = inputExpression == null ? "" : inputExpression;
            targetExpression = targetExpression == null ? "" : targetExpression;
            oracleStatus = oracleStatus == null || oracleStatus.isBlank() ? "UNAVAILABLE" : oracleStatus;
            ablationStatus = ablationStatus == null || ablationStatus.isBlank() ? "N/A" : ablationStatus;
            source = source == null ? "" : source;
            packId = packId == null ? "" : packId;
            operatorId = operatorId == null ? "" : operatorId;
            rulePath = rulePath == null ? List.of() : List.copyOf(rulePath);
            smallGraphMessage = smallGraphMessage == null ? "" : smallGraphMessage;
        }
    }

    record CandidateView(
        String id,
        String family,
        String inputExpression,
        String targetExpression,
        boolean success,
        String oracleStatus,
        String ablationStatus,
        String source,
        String packId,
        String operatorId,
        List<String> rulePath,
        String smallGraphMessage,
        String stage
    ) {
        CandidateView(CandidateRecord record, String stage) {
            this(
                record.id(),
                record.family(),
                record.inputExpression(),
                record.targetExpression(),
                record.success(),
                record.oracleStatus(),
                record.ablationStatus(),
                record.source(),
                record.packId(),
                record.operatorId(),
                record.rulePath(),
                record.smallGraphMessage(),
                stage
            );
        }

        CandidateView {
            rulePath = rulePath == null ? List.of() : List.copyOf(rulePath);
            stage = stage == null ? "observed" : stage;
        }
    }
}
