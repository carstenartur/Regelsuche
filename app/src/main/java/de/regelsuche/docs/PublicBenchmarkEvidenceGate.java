package de.regelsuche.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Gate for generated benchmark evidence that feeds docs/demo-gallery.md. */
final class PublicBenchmarkEvidenceGate {
    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    GateDecision evaluate(DiscoveryBenchmarkScenario scenario, DiscoveryBenchmarkEvidence evidence) {
        List<String> reasons = new ArrayList<>();
        if (!evidence.success()) {
            reasons.add("success=false");
        }
        if (!evidence.promotionEligible()) {
            reasons.add("promotionEligible=false");
        }
        if ("DISAGREE".equalsIgnoreCase(evidence.oracleStatus())) {
            reasons.add("oracle=DISAGREE");
        }
        if (evidence.foundPaths().isEmpty() || evidence.edges().isEmpty()) {
            reasons.add("pathSource!=REGELSUCHE_SEARCH");
        }
        if (hasCuratedOrFallbackEdge(evidence)) {
            reasons.add("curated-or-fallback-path=true");
        }
        if (evidence.edges().stream().noneMatch(edge -> !edge.operatorId().isBlank())) {
            reasons.add("operator=missing");
        }
        if (evidence.edges().stream().noneMatch(edge -> edge.packId() != null && !edge.packId().isBlank())) {
            reasons.add("pack=missing");
        }
        int minVisibleNodes = Math.max(1, scenario.gallery().minVisibleNodes());
        if (evidence.nodeCount() < minVisibleNodes || evidence.edgeCount() == 0) {
            reasons.add("visible-graph=insufficient");
        }
        AblationEvidence ablation = ablationEvidence(evidence);
        if (!ablation.hasStructuredMetrics()) {
            reasons.add("ablation=missing-structured");
        } else if (!ablation.promotionReady()) {
            reasons.add("ablation=" + ablation.ablationStatus());
        }
        return new GateDecision(
            scenario.id(),
            scenario.displayName(),
            reasons.isEmpty(),
            evidence.success(),
            evidence.promotionEligible(),
            evidence.oracleStatus(),
            ablation.ablationStatus(),
            ablation.hasStructuredMetrics(),
            evidence.nodeCount(),
            evidence.edgeCount(),
            reasons
        );
    }

    GateReport write(Path outputDirectory, List<GateDecision> decisions) {
        try {
            Files.createDirectories(outputDirectory);
            GateReport report = new GateReport(
                decisions,
                decisions.stream().filter(GateDecision::accepted).count(),
                decisions.stream().filter(decision -> !decision.accepted()).count()
            );
            AtomicJsonFile.writeUtf8(
                outputDirectory.resolve("public-scenario-gate.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)
            );
            Files.writeString(
                outputDirectory.resolve("public-scenario-rejections.md"),
                renderRejections(report),
                StandardCharsets.UTF_8
            );
            return report;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    String renderRejections(GateReport report) {
        StringBuilder out = new StringBuilder("# Public scenario gate rejections\n\n");
        out.append("| Scenario | Accepted | Success | Promotion eligible | Oracle | Ablation | Nodes | Edges | Rejection reasons |\n");
        out.append("| --- | --- | --- | --- | --- | --- | ---: | ---: | --- |\n");
        if (report.decisions().isEmpty()) {
            out.append("| — | — | — | — | — | — | 0 | 0 | none |\n");
            return out.toString();
        }
        for (GateDecision decision : report.decisions()) {
            out.append("| ").append(escape(decision.scenarioId()))
                .append(" | ").append(decision.accepted() ? "yes" : "no")
                .append(" | ").append(decision.success() ? "yes" : "no")
                .append(" | ").append(decision.promotionEligible() ? "yes" : "no")
                .append(" | ").append(escape(decision.oracleStatus().toLowerCase(Locale.ROOT)))
                .append(" | ").append(escape(decision.ablationStatus().toLowerCase(Locale.ROOT)))
                .append(" | ").append(decision.nodeCount())
                .append(" | ").append(decision.edgeCount())
                .append(" | ").append(decision.rejectionReasons().isEmpty() ? "—" : escape(String.join(", ", decision.rejectionReasons())))
                .append(" |\n");
        }
        return out.toString();
    }

    private AblationEvidence ablationEvidence(DiscoveryBenchmarkEvidence evidence) {
        DiscoveryBenchmarkEvidence.SearchRunEvidence withMacro = evidence.withMacroRun();
        DiscoveryBenchmarkEvidence.SearchRunEvidence withoutMacro = evidence.withoutMacroRun();
        if (withMacro == null || withoutMacro == null) {
            return AblationEvidence.statusOnly("N/A", "benchmark evidence does not contain both runs");
        }
        return AblationEvidence.compare(
            withMacro.success(),
            pathLength(withMacro),
            statesExplored(withMacro),
            withoutMacro.success(),
            pathLength(withoutMacro),
            statesExplored(withoutMacro),
            "public benchmark macro reuse ablation"
        );
    }

    private int pathLength(DiscoveryBenchmarkEvidence.SearchRunEvidence run) {
        return run.path().isEmpty() ? -1 : Math.max(0, run.path().size() - 1);
    }

    private long statesExplored(DiscoveryBenchmarkEvidence.SearchRunEvidence run) {
        return run.analytics() == null ? -1L : run.analytics().statesExplored();
    }

    private boolean hasCuratedOrFallbackEdge(DiscoveryBenchmarkEvidence evidence) {
        return evidence.edges().stream().anyMatch(edge -> {
            String source = edge.source() == null ? "" : edge.source().toLowerCase(Locale.ROOT);
            String rule = edge.ruleId() == null ? "" : edge.ruleId().toLowerCase(Locale.ROOT);
            return source.contains("scenario")
                || source.contains("curated")
                || source.contains("hardcoded")
                || source.contains("fallback")
                || rule.contains("fallback");
        });
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    record GateReport(List<GateDecision> decisions, long acceptedCount, long rejectedCount) {
        GateReport {
            decisions = decisions == null ? List.of() : List.copyOf(decisions);
        }
    }

    record GateDecision(
        String scenarioId,
        String displayName,
        boolean accepted,
        boolean success,
        boolean promotionEligible,
        String oracleStatus,
        String ablationStatus,
        boolean structuredAblation,
        int nodeCount,
        int edgeCount,
        List<String> rejectionReasons
    ) {
        GateDecision {
            scenarioId = scenarioId == null ? "" : scenarioId;
            displayName = displayName == null ? "" : displayName;
            oracleStatus = oracleStatus == null ? "UNAVAILABLE" : oracleStatus;
            ablationStatus = ablationStatus == null ? "N/A" : ablationStatus;
            rejectionReasons = rejectionReasons == null ? List.of() : List.copyOf(rejectionReasons);
        }
    }
}
