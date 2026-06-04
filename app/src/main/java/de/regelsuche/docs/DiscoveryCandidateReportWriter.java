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

    CandidateBundle write(Path outputDirectory, String campaignId, List<PromotionRecord> promotionRecords) {
        try {
            Files.createDirectories(outputDirectory);
            CandidateBundle bundle = new CandidateBundle(
                campaignId,
                promotionRecords.stream()
                    .map(CandidateView::new)
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
                    .append(": investigate operator support for recorded path ")
                    .append(escape(candidate.rulePath().isEmpty() ? "—" : String.join(" -> ", candidate.rulePath())))
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

    record CandidateView(
        String id,
        String family,
        String oracleStatus,
        String ablationStatus,
        String source,
        String packId,
        String operatorId,
        List<String> rulePath,
        String stage
    ) {
        CandidateView(PromotionRecord record) {
            this(
                record.candidateId(),
                record.family(),
                record.oracleStatus(),
                record.ablationStatus(),
                record.sourcePack().isBlank() && record.sourceOperator().isBlank() ? "" : "promotion-record",
                record.sourcePack(),
                record.sourceOperator(),
                record.rulePath(),
                record.stage().name().toLowerCase(Locale.ROOT)
            );
        }

        CandidateView {
            rulePath = rulePath == null ? List.of() : List.copyOf(rulePath);
            stage = stage == null ? "observed" : stage;
        }

        boolean success() {
            return !"observed".equals(stage);
        }
    }
}
