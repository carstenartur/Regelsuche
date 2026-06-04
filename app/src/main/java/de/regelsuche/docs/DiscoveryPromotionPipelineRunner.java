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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

/** Coordinates the promotion registry, closed-loop campaign 4 reuse validation, backlog, and metrics. */
public final class DiscoveryPromotionPipelineRunner {
    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final PromotionDecider decider = new PromotionDecider();
    private final PromotionRegistry registry = new PromotionRegistry();
    private final DiscoveryCampaignFourRunner campaignFourRunner = new DiscoveryCampaignFourRunner();

    public static void main(String[] args) {
        Path repoRoot = args.length == 0
            ? Path.of(".").toAbsolutePath().normalize()
            : Path.of(args[0]).toAbsolutePath().normalize();
        new DiscoveryPromotionPipelineRunner()
            .writeReport(repoRoot.resolve("app/build/reports/discovery-promotion"));
    }

    PipelineReport run() {
        DiscoveryCampaignOneRunner.CampaignReport campaignOne = new DiscoveryCampaignOneRunner().run();
        DiscoveryCampaignTwoRunner.CampaignReport campaignTwo = new DiscoveryCampaignTwoRunner().run();
        DiscoveryCampaignThreeRunner.CampaignReport campaignThree = new DiscoveryCampaignThreeRunner().run();

        List<PromotionRecord> promotionRecords = Stream.of(
                campaignOne.results().stream()
                    .map(result -> decider.decide(PromotionObservation.fromCampaignOne(result, campaignOne.id()))),
                campaignTwo.results().stream()
                    .map(result -> decider.decide(PromotionObservation.fromCampaignTwo(result, campaignTwo.id()))),
                campaignThree.results().stream()
                    .map(result -> decider.decide(PromotionObservation.fromCampaignThree(result, campaignThree.id()))))
            .flatMap(Function.identity())
            .sorted(Comparator.comparing(PromotionRecord::candidateId))
            .toList();

        DiscoveryCampaignFourRunner.CampaignReport campaignFour = campaignFourRunner.run(promotionRecords);
        Map<String, DiscoveryCampaignFourRunner.CaseResult> reuseByCandidateId = campaignFour.results().stream()
            .collect(java.util.stream.Collectors.toMap(
                DiscoveryCampaignFourRunner.CaseResult::candidateId,
                Function.identity(),
                (left, right) -> left,
                LinkedHashMap::new));
        List<PromotionRecord> updatedRecords = promotionRecords.stream()
            .map(record -> reuseByCandidateId.containsKey(record.candidateId())
                ? record.withReuse(reuseByCandidateId.get(record.candidateId()))
                : record)
            .toList();
        PromotionRegistry.Registry promotionRegistry = registry.build(updatedRecords);
        return new PipelineReport(updatedRecords, promotionRegistry, campaignFour, campaignMetrics(updatedRecords));
    }

    PipelineReport writeReport(Path outputDirectory) {
        try {
            Files.createDirectories(outputDirectory);
            PipelineReport report = run();
            AtomicJsonFile.writeUtf8(
                outputDirectory.resolve("promotion-records.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report.promotionRecords())
            );
            AtomicJsonFile.writeUtf8(
                outputDirectory.resolve("promotion-registry.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report.registry().records())
            );
            Files.writeString(
                outputDirectory.resolve("promotion-history.md"),
                registry.renderHistoryMarkdown(report.registry()),
                StandardCharsets.UTF_8
            );
            AtomicJsonFile.writeUtf8(
                outputDirectory.resolve("campaign-metrics.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report.campaignMetrics())
            );
            Path backlogDirectory = outputDirectory.resolveSibling("discovery-backlog");
            Files.createDirectories(backlogDirectory);
            Files.writeString(
                backlogDirectory.resolve("blocked-candidates.md"),
                renderBlockedCandidates(report.promotionRecords()),
                StandardCharsets.UTF_8
            );
            Files.writeString(
                backlogDirectory.resolve("operator-opportunities.md"),
                renderOperatorOpportunities(report.promotionRecords()),
                StandardCharsets.UTF_8
            );
            Files.writeString(
                backlogDirectory.resolve("macro-opportunities.md"),
                renderMacroOpportunities(report.promotionRecords()),
                StandardCharsets.UTF_8
            );
            campaignFourRunner.writeReport(outputDirectory.resolveSibling("discovery-campaign-4"), report.promotionRecords());
            return report;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private List<CampaignMetric> campaignMetrics(List<PromotionRecord> records) {
        return records.stream()
            .collect(java.util.stream.Collectors.groupingBy(
                PromotionRecord::sourceCampaign,
                LinkedHashMap::new,
                java.util.stream.Collectors.toList()))
            .entrySet().stream()
            .map(entry -> metric(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparing(CampaignMetric::campaign))
            .toList();
    }

    private CampaignMetric metric(String campaignId, List<PromotionRecord> records) {
        long observed = records.size();
        long candidate = records.stream().filter(record -> record.stage().atLeast(PromotionStage.CANDIDATE)).count();
        long validated = records.stream().filter(record -> record.stage().atLeast(PromotionStage.VALIDATED)).count();
        long promoted = records.stream().filter(record -> record.stage().atLeast(PromotionStage.PROMOTED)).count();
        long reused = records.stream().filter(record -> record.stage().atLeast(PromotionStage.REUSED)).count();
        return new CampaignMetric(
            campaignId,
            observed,
            candidate,
            validated,
            promoted,
            reused,
            conversionRates(observed, candidate, validated, promoted, reused)
        );
    }

    private Map<String, Double> conversionRates(long observed, long candidate, long validated, long promoted, long reused) {
        Map<String, Double> rates = new LinkedHashMap<>();
        rates.put("candidatePerObserved", ratio(candidate, observed));
        rates.put("validatedPerCandidate", ratio(validated, candidate));
        rates.put("promotedPerValidated", ratio(promoted, validated));
        rates.put("reusedPerPromoted", ratio(reused, promoted));
        return Map.copyOf(rates);
    }

    private double ratio(long numerator, long denominator) {
        return denominator == 0L ? 0.0d : numerator / (double) denominator;
    }

    private String renderBlockedCandidates(List<PromotionRecord> records) {
        StringBuilder out = new StringBuilder("# Blocked candidates\n\n");
        List<PromotionRecord> blocked = records.stream()
            .filter(PromotionRecord::unresolved)
            .sorted(Comparator.comparing(PromotionRecord::candidateId))
            .toList();
        if (blocked.isEmpty()) {
            out.append("- none\n");
            return out.toString();
        }
        for (PromotionRecord record : blocked) {
            out.append("- ").append(escape(record.candidateId()))
                .append(" [").append(record.stage().name().toLowerCase()).append("]")
                .append(": ").append(escape(record.promotionBlockers().isEmpty()
                    ? record.rationale()
                    : String.join(", ", record.promotionBlockers())))
                .append('\n');
        }
        return out.toString();
    }

    private String renderOperatorOpportunities(List<PromotionRecord> records) {
        StringBuilder out = new StringBuilder("# Operator opportunities\n\n");
        List<PromotionRecord> opportunities = records.stream()
            .filter(PromotionRecord::unresolved)
            .filter(record -> record.sourceOperator().isBlank() || record.stage() == PromotionStage.OBSERVED)
            .sorted(Comparator.comparing(PromotionRecord::candidateId))
            .toList();
        if (opportunities.isEmpty()) {
            out.append("- none\n");
            return out.toString();
        }
        for (PromotionRecord record : opportunities) {
            out.append("- ").append(escape(record.candidateId()))
                .append(": investigate operator support for family ")
                .append(escape(record.family()))
                .append(" (campaign=").append(escape(record.sourceCampaign())).append(")\n");
        }
        return out.toString();
    }

    private String renderMacroOpportunities(List<PromotionRecord> records) {
        StringBuilder out = new StringBuilder("# Macro opportunities\n\n");
        List<PromotionRecord> opportunities = records.stream()
            .filter(PromotionRecord::unresolved)
            .filter(PromotionRecord::macroOpportunity)
            .sorted(Comparator.comparing(PromotionRecord::candidateId))
            .toList();
        if (opportunities.isEmpty()) {
            out.append("- none\n");
            return out.toString();
        }
        for (PromotionRecord record : opportunities) {
            out.append("- ").append(escape(record.candidateId()))
                .append(": ").append(escape(String.join(" -> ", record.rulePath())))
                .append(" (stage=").append(record.stage().name().toLowerCase()).append(")\n");
        }
        return out.toString();
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    record PipelineReport(
        List<PromotionRecord> promotionRecords,
        PromotionRegistry.Registry registry,
        DiscoveryCampaignFourRunner.CampaignReport campaignFour,
        List<CampaignMetric> campaignMetrics
    ) {
        PipelineReport {
            promotionRecords = promotionRecords == null ? List.of() : List.copyOf(promotionRecords);
            campaignMetrics = campaignMetrics == null ? List.of() : List.copyOf(campaignMetrics);
        }
    }

    record CampaignMetric(
        String campaign,
        long observed,
        long candidate,
        long validated,
        long promoted,
        long reused,
        Map<String, Double> conversionRates
    ) {
        CampaignMetric {
            conversionRates = conversionRates == null ? Map.of() : Map.copyOf(conversionRates);
        }
    }
}
