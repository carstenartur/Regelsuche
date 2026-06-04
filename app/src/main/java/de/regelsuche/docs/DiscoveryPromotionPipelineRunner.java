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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Coordinates the promotion registry, closed-loop campaign 4 reuse validation, backlog, and metrics. */
public final class DiscoveryPromotionPipelineRunner {
    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private static final Pattern SUBEXPRESSION_PATTERN = Pattern.compile("\\([^()]+\\)");

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
            Path backlogDirectory = outputDirectory.resolve("discovery-backlog");
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
            PromotionDashboard dashboard = buildDashboard(report.promotionRecords());
            AtomicJsonFile.writeUtf8(
                outputDirectory.resolve("promotion-dashboard.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(dashboard)
            );
            Files.writeString(
                outputDirectory.resolve("promotion-dashboard.md"),
                renderDashboard(dashboard),
                StandardCharsets.UTF_8
            );
            writeDiscoveryDetailReports(outputDirectory.resolve("discovery-details"), report.promotionRecords());
            Files.writeString(
                outputDirectory.resolve("gallery-2.0.md"),
                renderGallery(report.promotionRecords()),
                StandardCharsets.UTF_8
            );
            campaignFourRunner.writeReport(outputDirectory.resolve("discovery-campaign-4"), report.promotionRecords());
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

    private void writeDiscoveryDetailReports(Path detailsDirectory, List<PromotionRecord> records) throws IOException {
        Files.createDirectories(detailsDirectory);
        List<PromotionRecord> explainable = records.stream()
            .filter(record -> record.stage().atLeast(PromotionStage.PROMOTED))
            .sorted(Comparator.comparing(PromotionRecord::candidateId))
            .toList();
        if (explainable.isEmpty()) {
            Files.writeString(detailsDirectory.resolve("README.md"), "# Discovery details\n\n- none\n", StandardCharsets.UTF_8);
            return;
        }
        StringBuilder index = new StringBuilder("# Discovery details\n\n");
        for (PromotionRecord record : explainable) {
            String slug = slug(record.candidateId());
            Files.writeString(detailsDirectory.resolve(slug + ".md"), renderDetailReport(record), StandardCharsets.UTF_8);
            index.append("- [").append(escape(record.candidateId())).append("](")
                .append(slug).append(".md)\n");
        }
        Files.writeString(detailsDirectory.resolve("README.md"), index.toString(), StandardCharsets.UTF_8);
    }

    private String renderDetailReport(PromotionRecord record) {
        HighlightSection highlights = highlights(record);
        return """
            # Discovery detail: %s

            ## Candidate context

            - Original expression: `%s`
            - Discovered hidden structure: `%s`
            - Operator path: %s
            - Oracle status: `%s`
            - Ablation result: `%s`
            - Promotion stage: `%s`
            - Reuse improvement: `%s`

            ## Timeline

            `%s` → `%s` → `%s` → `%s`

            ## Highlighted before/after

            - Abstracted subexpression: %s
            - Rewritten section:
              - before: %s
              - after: %s
            - Expanded section: %s
            """
            .formatted(
                escape(record.candidateId()),
                escape(record.originalExpression()),
                escape(record.discoveredStructure()),
                inlinePath(record.rulePath()),
                escape(record.oracleStatus()),
                escape(record.ablationStatus()),
                record.stage().name().toLowerCase(Locale.ROOT),
                record.measuredImprovement() ? "improved" : "not-measured",
                escape(orDash(record.originalExpression())),
                escape(orDash(timelineMiddle(record))),
                escape(orDash(record.discoveredStructure())),
                escape(orDash(timelineReuse(record))),
                escape(orDash(highlights.abstractedSubexpression())),
                escape(orDash(highlights.rewrittenBefore())),
                escape(orDash(highlights.rewrittenAfter())),
                escape(orDash(highlights.expandedSubexpression()))
            );
    }

    private String timelineMiddle(PromotionRecord record) {
        if (!record.sourceOperator().isBlank()) {
            return record.sourceOperator();
        }
        if (!record.rulePath().isEmpty()) {
            return String.join(" -> ", record.rulePath());
        }
        return "substitution/bridge";
    }

    private String timelineReuse(PromotionRecord record) {
        if (!record.reusedMacroIds().isEmpty()) {
            return "macro reuse: " + String.join(", ", record.reusedMacroIds());
        }
        if (!record.generatedMacroId().isBlank()) {
            return "generated macro: " + record.generatedMacroId();
        }
        return "macro reuse pending";
    }

    private HighlightSection highlights(PromotionRecord record) {
        String abstracted = detectAbstractedSubexpression(record.originalExpression());
        Diff rewriteDiff = diff(record.originalExpression(), record.discoveredStructure());
        String expanded = record.rulePath().stream()
            .filter(step -> step != null && step.toLowerCase(Locale.ROOT).contains("expansion"))
            .findFirst()
            .orElse(record.rulePath().stream()
                .filter(step -> step != null && step.toLowerCase(Locale.ROOT).contains("substitution"))
                .findFirst()
                .orElse(""));
        return new HighlightSection(abstracted, rewriteDiff.before(), rewriteDiff.after(), expanded);
    }

    private String detectAbstractedSubexpression(String expression) {
        if (expression == null || expression.isBlank()) {
            return "";
        }
        Matcher matcher = SUBEXPRESSION_PATTERN.matcher(expression);
        Map<String, Integer> counts = new LinkedHashMap<>();
        while (matcher.find()) {
            counts.merge(matcher.group(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
            .filter(entry -> entry.getValue() > 1)
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(counts.keySet().stream().findFirst().orElse(""));
    }

    private Diff diff(String before, String after) {
        String left = before == null ? "" : before;
        String right = after == null ? "" : after;
        if (left.equals(right)) {
            return new Diff(left, right);
        }
        int start = 0;
        int leftLength = left.length();
        int rightLength = right.length();
        while (start < leftLength && start < rightLength && left.charAt(start) == right.charAt(start)) {
            start++;
        }
        int leftEnd = leftLength - 1;
        int rightEnd = rightLength - 1;
        while (leftEnd >= start && rightEnd >= start && left.charAt(leftEnd) == right.charAt(rightEnd)) {
            leftEnd--;
            rightEnd--;
        }
        String leftChanged = start <= leftEnd ? left.substring(start, leftEnd + 1) : "";
        String rightChanged = start <= rightEnd ? right.substring(start, rightEnd + 1) : "";
        return new Diff(leftChanged, rightChanged);
    }

    private PromotionDashboard buildDashboard(List<PromotionRecord> records) {
        long observed = records.size();
        long candidate = records.stream().filter(record -> record.stage().atLeast(PromotionStage.CANDIDATE)).count();
        long validated = records.stream().filter(record -> record.stage().atLeast(PromotionStage.VALIDATED)).count();
        long promoted = records.stream().filter(record -> record.stage().atLeast(PromotionStage.PROMOTED)).count();
        long reused = records.stream().filter(record -> record.stage().atLeast(PromotionStage.REUSED)).count();
        List<TopCandidate> topPromoted = records.stream()
            .filter(record -> record.stage().atLeast(PromotionStage.PROMOTED))
            .sorted(Comparator.comparing(PromotionRecord::stage).reversed()
                .thenComparing(record -> record.measuredImprovement() ? 0 : 1)
                .thenComparing(record -> -record.rulePath().size())
                .thenComparing(PromotionRecord::candidateId))
            .limit(5)
            .map(record -> new TopCandidate(
                record.candidateId(),
                record.stage(),
                record.oracleStatus(),
                record.ablationStatus(),
                record.measuredImprovement()))
            .toList();
        List<UnresolvedBlocker> unresolved = records.stream()
            .filter(PromotionRecord::unresolved)
            .map(record -> new UnresolvedBlocker(
                record.candidateId(),
                record.stage(),
                record.promotionBlockers().isEmpty() ? List.of(orDash(record.rationale())) : record.promotionBlockers()))
            .toList();
        return new PromotionDashboard(
            observed,
            candidate,
            validated,
            promoted,
            reused,
            conversionRates(observed, candidate, validated, promoted, reused),
            topPromoted,
            unresolved
        );
    }

    private String renderDashboard(PromotionDashboard dashboard) {
        StringBuilder out = new StringBuilder("# Promotion dashboard\n\n");
        out.append("| observed | candidate | validated | promoted | reused |\n")
            .append("| ---: | ---: | ---: | ---: | ---: |\n")
            .append("| ").append(dashboard.observed())
            .append(" | ").append(dashboard.candidate())
            .append(" | ").append(dashboard.validated())
            .append(" | ").append(dashboard.promoted())
            .append(" | ").append(dashboard.reused())
            .append(" |\n\n");
        out.append("## Conversion rates\n\n");
        dashboard.conversionRates().forEach((key, value) -> out.append("- ")
            .append(key).append(": ")
            .append(String.format(Locale.ROOT, "%.3f", value)).append('\n'));
        out.append("\n## Top promoted candidates\n\n");
        if (dashboard.topPromotedCandidates().isEmpty()) {
            out.append("- none\n");
        } else {
            for (TopCandidate candidate : dashboard.topPromotedCandidates()) {
                out.append("- ").append(escape(candidate.candidateId()))
                    .append(" [").append(candidate.stage().name().toLowerCase(Locale.ROOT)).append("]")
                    .append(" oracle=").append(escape(candidate.oracleStatus()))
                    .append(" ablation=").append(escape(candidate.ablationStatus()))
                    .append(" reuseImprovement=").append(candidate.measuredImprovement() ? "yes" : "no")
                    .append('\n');
            }
        }
        out.append("\n## Unresolved blockers\n\n");
        if (dashboard.unresolvedBlockers().isEmpty()) {
            out.append("- none\n");
        } else {
            for (UnresolvedBlocker blocker : dashboard.unresolvedBlockers()) {
                out.append("- ").append(escape(blocker.candidateId()))
                    .append(" [").append(blocker.stage().name().toLowerCase(Locale.ROOT)).append("]")
                    .append(": ").append(escape(String.join(", ", blocker.blockers())))
                    .append('\n');
            }
        }
        return out.toString();
    }

    private String renderGallery(List<PromotionRecord> records) {
        StringBuilder out = new StringBuilder("# Gallery 2.0\n\n");
        List<PromotionRecord> selected = records.stream()
            .filter(PromotionRecord::galleryEligible)
            .sorted(Comparator.comparing(PromotionRecord::candidateId))
            .toList();
        if (selected.isEmpty()) {
            out.append("- none\n");
            return out.toString();
        }
        out.append("| Candidate | Stage | Original | Result | Operator path | Reused macros |\n")
            .append("| --- | --- | --- | --- | --- | --- |\n");
        for (PromotionRecord record : selected) {
            out.append("| ").append(escape(record.candidateId()))
                .append(" | ").append(record.stage().name().toLowerCase(Locale.ROOT))
                .append(" | `").append(escape(orDash(record.originalExpression()))).append("`")
                .append(" | `").append(escape(orDash(record.discoveredStructure()))).append("`")
                .append(" | ").append(escape(inlinePath(record.rulePath())))
                .append(" | ").append(escape(record.reusedMacroIds().isEmpty() ? "—" : String.join(", ", record.reusedMacroIds())))
                .append(" |\n");
        }
        return out.toString();
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
                .append(" [").append(record.stage().name().toLowerCase(Locale.ROOT)).append("]")
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
                .append(" (stage=").append(record.stage().name().toLowerCase(Locale.ROOT)).append(")\n");
        }
        return out.toString();
    }

    private String inlinePath(List<String> path) {
        return path == null || path.isEmpty() ? "—" : String.join(" -> ", path);
    }

    private String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private String slug(String value) {
        String normalized = value == null ? "candidate" : value.toLowerCase(Locale.ROOT);
        String slug = normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return slug.isBlank() ? "candidate" : slug;
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

    record PromotionDashboard(
        long observed,
        long candidate,
        long validated,
        long promoted,
        long reused,
        Map<String, Double> conversionRates,
        List<TopCandidate> topPromotedCandidates,
        List<UnresolvedBlocker> unresolvedBlockers
    ) {
        PromotionDashboard {
            conversionRates = conversionRates == null ? Map.of() : Map.copyOf(conversionRates);
            topPromotedCandidates = topPromotedCandidates == null ? List.of() : List.copyOf(topPromotedCandidates);
            unresolvedBlockers = unresolvedBlockers == null ? List.of() : List.copyOf(unresolvedBlockers);
        }
    }

    record TopCandidate(
        String candidateId,
        PromotionStage stage,
        String oracleStatus,
        String ablationStatus,
        boolean measuredImprovement
    ) {
    }

    record UnresolvedBlocker(String candidateId, PromotionStage stage, List<String> blockers) {
        UnresolvedBlocker {
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
        }
    }

    private record HighlightSection(
        String abstractedSubexpression,
        String rewrittenBefore,
        String rewrittenAfter,
        String expandedSubexpression
    ) {
    }

    private record Diff(String before, String after) {
    }
}
