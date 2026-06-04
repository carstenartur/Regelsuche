package de.regelsuche.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Runs Discovery Campaign 4 by validating whether promoted macros measurably help a later reuse pass. */
public final class DiscoveryCampaignFourRunner {
    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final DiscoveryBenchmarkExecutor executor = new DiscoveryBenchmarkExecutor();
    private final DiscoveryBenchmarkScenarioLoader loader = new DiscoveryBenchmarkScenarioLoader();

    public static void main(String[] args) {
        Path repoRoot = args.length == 0
            ? Path.of(".").toAbsolutePath().normalize()
            : Path.of(args[0]).toAbsolutePath().normalize();
        List<PromotionRecord> promoted = new DiscoveryPromotionPipelineRunner().run().promotionRecords().stream()
            .filter(record -> record.stage().atLeast(PromotionStage.PROMOTED))
            .toList();
        new DiscoveryCampaignFourRunner().writeReport(repoRoot.resolve("app/build/reports/discovery-campaign-4"), promoted);
    }

    CampaignReport run(List<PromotionRecord> promotedRecords) {
        Set<String> promotedIds = promotedRecords.stream()
            .filter(record -> record.stage().atLeast(PromotionStage.PROMOTED))
            .map(PromotionRecord::candidateId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<CaseResult> results = supportedCases().stream()
            .filter(reuseCase -> promotedIds.contains(reuseCase.candidateId()))
            .map(this::evaluate)
            .sorted(Comparator.comparing(CaseResult::candidateId))
            .toList();
        long improved = results.stream().filter(CaseResult::measuredImprovement).count();
        return new CampaignReport("discovery-campaign-4", results, improved);
    }

    CampaignReport writeReport(Path outputDirectory, List<PromotionRecord> promotedRecords) {
        try {
            Files.createDirectories(outputDirectory);
            CampaignReport report = run(promotedRecords);
            AtomicJsonFile.writeUtf8(
                outputDirectory.resolve("discovery-campaign-4.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)
            );
            Files.writeString(
                outputDirectory.resolve("macro-reuse-report.md"),
                renderMarkdown(report),
                StandardCharsets.UTF_8
            );
            return report;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private CaseResult evaluate(ReuseCase reuseCase) {
        DiscoveryBenchmarkScenario scenario = loader.load(reuseCase.scenarioResource());
        DiscoveryBenchmarkEvidence evidence = executor.execute(scenario);
        RunMetrics macroDisabled = metrics(evidence.withoutMacroRun().success(), evidence.withoutMacroRun().path(),
            evidence.withoutMacroRun().analytics().statesExplored(), bridgeCount(evidence.withoutMacroRun().appliedRuleIds()));
        int enabledBridgeCount = bridgeCount(evidence.withMacroRun().appliedRuleIds());
        RunMetrics macroEnabled = metrics(evidence.withMacroRun().success(), evidence.withMacroRun().path(),
            evidence.withMacroRun().analytics().statesExplored(), enabledBridgeCount);
        boolean measuredImprovement = macroEnabled.success()
            && (!macroDisabled.success()
                || macroEnabled.statesExplored() < macroDisabled.statesExplored()
                || macroEnabled.pathLength() < macroDisabled.pathLength()
                || macroEnabled.bridgeCount() < macroDisabled.bridgeCount());
        String generatedMacroId = evidence.learnedMacros().isEmpty() ? "" : evidence.learnedMacros().getFirst();
        return new CaseResult(
            "discovery-campaign-4",
            reuseCase.candidateId(),
            reuseCase.sourceCampaign(),
            reuseCase.scenarioId(),
            generatedMacroId,
            evidence.reusedMacros(),
            macroDisabled,
            macroEnabled,
            measuredImprovement
        );
    }

    private RunMetrics metrics(boolean success, List<String> path, long statesExplored, int bridgeCount) {
        return new RunMetrics(success, Math.max(0, path.size() - 1), statesExplored, bridgeCount);
    }

    private int bridgeCount(List<String> appliedRuleIds) {
        return (int) appliedRuleIds.stream()
            .filter(ruleId -> ruleId != null)
            .filter(ruleId -> {
                String lower = ruleId.toLowerCase(Locale.ROOT);
                return lower.contains("bridge") || lower.startsWith("hypothesis_");
            })
            .count();
    }

    private String renderMarkdown(CampaignReport report) {
        StringBuilder out = new StringBuilder("# Discovery Campaign 4\n\n");
        out.append("| Candidate | Scenario | Macro | Reused | Disabled success | Enabled success | Disabled path | Enabled path | Disabled states | Enabled states | Improved |\n");
        out.append("| --- | --- | --- | --- | --- | --- | ---: | ---: | ---: | ---: | --- |\n");
        for (CaseResult result : report.results()) {
            out.append("| ").append(escape(result.candidateId()))
                .append(" | ").append(escape(result.scenarioId()))
                .append(" | ").append(escape(orDash(result.generatedMacroId())))
                .append(" | ").append(escape(result.reusedMacroIds().isEmpty() ? "—" : String.join(", ", result.reusedMacroIds())))
                .append(" | ").append(result.macroDisabled().success() ? "yes" : "no")
                .append(" | ").append(result.macroEnabled().success() ? "yes" : "no")
                .append(" | ").append(result.macroDisabled().pathLength())
                .append(" | ").append(result.macroEnabled().pathLength())
                .append(" | ").append(result.macroDisabled().statesExplored())
                .append(" | ").append(result.macroEnabled().statesExplored())
                .append(" | ").append(result.measuredImprovement() ? "yes" : "no")
                .append(" |\n");
        }
        out.append("\n- improvedCandidates: ").append(report.improvedCandidates()).append('\n');
        return out.toString();
    }

    private List<ReuseCase> supportedCases() {
        return List.of(
            new ReuseCase(
                "complete-square-family",
                "discovery-campaign-1",
                "complete-square-factorization",
                "discovery-scenarios/complete-square.yaml"
            ),
            new ReuseCase(
                "sophie-germain-variant",
                "discovery-campaign-1",
                "sophie-germain",
                "discovery-scenarios/sophie-germain.yaml"
            )
        );
    }

    private String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    public record CampaignReport(String campaignId, List<CaseResult> results, long improvedCandidates) {
        public CampaignReport {
            results = results == null ? List.of() : List.copyOf(results);
        }
    }

    public record CaseResult(
        String campaignId,
        String candidateId,
        String sourceCampaign,
        String scenarioId,
        String generatedMacroId,
        List<String> reusedMacroIds,
        RunMetrics macroDisabled,
        RunMetrics macroEnabled,
        boolean measuredImprovement
    ) {
        public CaseResult {
            generatedMacroId = generatedMacroId == null ? "" : generatedMacroId;
            reusedMacroIds = reusedMacroIds == null ? List.of() : List.copyOf(reusedMacroIds);
        }
    }

    public record RunMetrics(boolean success, int pathLength, long statesExplored, int bridgeCount) {
    }

    private record ReuseCase(String candidateId, String sourceCampaign, String scenarioId, String scenarioResource) {
    }
}
