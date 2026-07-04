package de.regelsuche.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.regelsuche.explanation.Explanation;
import de.regelsuche.explanation.ExplanationFact;
import de.regelsuche.explanation.ExplanationSection;
import de.regelsuche.explanation.MarkdownExplanationRenderer;
import de.regelsuche.explanation.TransformationExplanation;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Coordinates the promotion registry, closed-loop campaign 4 reuse validation, backlog, and metrics. */
public final class DiscoveryPromotionPipelineRunner {
    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private final PromotionDecider decider = new PromotionDecider();
    private final PromotionRegistry registry = new PromotionRegistry();
    private final DiscoveryCampaignFiveRunner campaignFiveRunner = new DiscoveryCampaignFiveRunner();
    private final DiscoveryCampaignFourRunner campaignFourRunner = new DiscoveryCampaignFourRunner();
    private final DiscoveryCampaignSevenRunner campaignSevenRunner = new DiscoveryCampaignSevenRunner();
    private final DiscoveryCampaignEightRunner campaignEightRunner = new DiscoveryCampaignEightRunner();
    private final DiscoveryExplanationFactory explanationFactory = new DiscoveryExplanationFactory();
    private final MarkdownExplanationRenderer markdownExplanationRenderer = new MarkdownExplanationRenderer();

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
        // Order decision: Campaign 5 runs before Campaign 4 so newly promoted hidden-structure cases can be
        // considered by Campaign 4's macro-reuse validation in the same pipeline run.
        DiscoveryCampaignFiveRunner.CampaignReport campaignFive = campaignFiveRunner.run();
        // Campaign 7 runs after Campaign 5 so its new families can benefit from the same promotion context.
        DiscoveryCampaignSevenRunner.CampaignReport campaignSeven = campaignSevenRunner.run();
        // Campaign 8 runs after Campaign 7 so trig/log-exp families extend the promotion context.
        DiscoveryCampaignEightRunner.CampaignReport campaignEight = campaignEightRunner.run();

        List<PromotionRecord> promotionRecords = Stream.of(
                campaignOne.results().stream()
                    .map(result -> decider.decide(PromotionObservation.fromCampaignOne(result, campaignOne.id()))),
                campaignTwo.results().stream()
                    .map(result -> decider.decide(PromotionObservation.fromCampaignTwo(result, campaignTwo.id()))),
                campaignThree.results().stream()
                    .map(result -> decider.decide(PromotionObservation.fromCampaignThree(result, campaignThree.id()))),
                campaignFive.results().stream()
                    .map(result -> decider.decide(PromotionObservation.fromCampaignFive(result, campaignFive.id()))),
                campaignSeven.results().stream()
                    .map(result -> decider.decide(PromotionObservation.fromCampaignSeven(result, campaignSeven.id()))),
                campaignEight.results().stream()
                    .map(result -> decider.decide(PromotionObservation.fromCampaignEight(result, campaignEight.id()))))
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
        return new PipelineReport(updatedRecords, promotionRegistry, campaignFive, campaignFour, campaignSeven, campaignEight, campaignMetrics(updatedRecords));
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
            Files.writeString(
                backlogDirectory.resolve("operator-impact.md"),
                renderOperatorImpactView(report.promotionRecords()),
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
            campaignFiveRunner.writeReport(outputDirectory.resolve("discovery-campaign-5"), report.campaignFive());
            campaignFourRunner.writeReport(outputDirectory.resolve("discovery-campaign-4"), report.promotionRecords());
            campaignSevenRunner.writeReport(outputDirectory.resolve("discovery-campaign-7"), report.campaignSeven());
            campaignEightRunner.writeReport(outputDirectory.resolve("discovery-campaign-8"), report.campaignEight());
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
        Map<String, Integer> slugCounts = new LinkedHashMap<>();
        for (PromotionRecord record : explainable) {
            String fileSlug = uniqueSlug(slug(record.candidateId()), slugCounts);
            Files.writeString(detailsDirectory.resolve(fileSlug + ".md"), renderDetailReport(record), StandardCharsets.UTF_8);
            index.append("- [").append(escapeMarkdownInline(record.candidateId())).append("](")
                .append(fileSlug).append(".md)\n");
        }
        Files.writeString(detailsDirectory.resolve("README.md"), index.toString(), StandardCharsets.UTF_8);
    }

    String renderDetailReport(PromotionRecord record) {
        DiscoveryHighlightModel highlightModel = highlightModel(record);
        TransformationExplanation transformationExplanation = explanationFactory.buildTransformationExplanation(record);
        return """
            # Discovery detail: %s

            ## Discovery summary

            - Origin expression: %s
            - Discovered structure: %s
            - Supporting evidence: %s
            - Introduced placeholders: %s
            - Transformation/operator path: %s
            - Promotion decision: %s
            - Missing pieces: %s

            ## Candidate context

            - Original expression: %s
            - Detected structure from evidence: %s
            - Placeholder mappings: %s
            - Operator path: %s
            - Oracle status: %s
            - Oracle evidence: %s
            - Ablation result: %s
            - Promotion stage: %s
            - Reuse improvement: %s

            ## Timeline

            - original: %s
            - evidence-based abstraction/substitution: %s
            - bridge/operator path: %s
            - result: %s
            - macro reuse: %s

            ## Evidence-based highlight model

            - Original expression: %s
            - Discovered structure: %s
            - Abstracted subexpression: %s
            - Placeholder mappings: %s
            - Substituted expression: %s
            - Expanded expression: %s
            - Rewritten section:
              - before: %s
              - after: %s
            - Source evidence: %s
            - Token-safe placeholder expansion: only whole placeholder tokens are replaced; identifiers like `ABC` or `A1` stay unchanged when replacing placeholder `A`.

            ## Local transformation highlighting

            %s
            """
            .formatted(
                escapeMarkdownInline(record.candidateId()),
                inlineCodeOrDash(record.originalExpression()),
                inlineCodeOrDash(highlightModel.discoveredStructure()),
                escapeMarkdownInline(orDash(renderSourceEvidence(highlightModel))),
                escapeMarkdownInline(orDash(renderPlaceholderMappings(highlightModel))),
                escapeMarkdownInline(inlinePath(record.rulePath())),
                escapeMarkdownInline(promotionDecision(record)),
                escapeMarkdownInline(missingPieces(record)),
                inlineCodeOrDash(record.originalExpression()),
                inlineCodeOrDash(highlightModel.discoveredStructure()),
                escapeMarkdownInline(orDash(renderPlaceholderMappings(highlightModel))),
                escapeMarkdownInline(inlinePath(record.rulePath())),
                inlineCodeOrDash(record.oracleStatus()),
                inlineCodeOrDash(orDash(record.oracleEvidence())),
                inlineCodeOrDash(record.ablationStatus()),
                inlineCodeOrDash(record.stage().name().toLowerCase(Locale.ROOT)),
                record.measuredImprovement() ? "improved" : "not-measured",
                inlineCodeOrDash(record.originalExpression()),
                inlineCodeOrDash(timelineAbstraction(highlightModel)),
                inlineCodeOrDash(timelineMiddle(record)),
                inlineCodeOrDash(record.discoveredStructure()),
                inlineCodeOrDash(timelineReuse(record)),
                inlineCodeOrDash(highlightModel.originalExpression()),
                inlineCodeOrDash(highlightModel.discoveredStructure()),
                escapeMarkdownInline(orDash(abstractedSubexpression(highlightModel))),
                escapeMarkdownInline(orDash(renderPlaceholderMappings(highlightModel))),
                escapeMarkdownInline(orDash(highlightModel.substitutedExpression())),
                escapeMarkdownInline(orDash(highlightModel.expandedExpression())),
                escapeMarkdownInline(orDash(highlightModel.rewrittenBefore())),
                escapeMarkdownInline(orDash(highlightModel.rewrittenAfter())),
                escapeMarkdownInline(orDash(renderSourceEvidence(highlightModel))),
                renderLocalTransformationHighlighting(transformationExplanation)
            );
    }

    String renderLocalTransformationHighlighting(TransformationExplanation transformationExplanation) {
        return markdownExplanationRenderer.renderSections(
            localTransformationHighlightExplanation(transformationExplanation),
            3
        ).stripTrailing();
    }

    Explanation localTransformationHighlightExplanation(TransformationExplanation transformationExplanation) {
        List<ExplanationFact> facts = new ArrayList<>();
        facts.add(new ExplanationFact(
            "Affected TreePosition",
            transformationExplanation.position().isBlank() ? "root" : transformationExplanation.position()
        ));
        facts.add(new ExplanationFact("Before (subtree at position)", transformationExplanation.before()));
        facts.add(new ExplanationFact("After (subtree at position)", transformationExplanation.after()));
        for (String rulePathStep : transformationExplanation.rulePath()) {
            facts.add(new ExplanationFact("Transformation/operator path", rulePathStep));
        }
        for (String pathReason : transformationExplanation.pathReasons()) {
            facts.add(new ExplanationFact("Why path works", pathReason));
        }
        return new Explanation(
            transformationExplanation.candidateId(),
            List.of(new ExplanationSection("Transformation", facts, List.of(), List.of()))
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

    private String timelineAbstraction(DiscoveryHighlightModel highlightModel) {
        if (!highlightModel.substitutedExpression().isBlank()) {
            return highlightModel.substitutedExpression();
        }
        if (!highlightModel.placeholderMappings().isEmpty()) {
            return "placeholder mappings: " + renderPlaceholderMappings(highlightModel);
        }
        return "no substitution evidence";
    }

    private DiscoveryHighlightModel highlightModel(PromotionRecord record) {
        Map<String, String> placeholderMappings = new LinkedHashMap<>();
        Map<String, Integer> placeholderOccurrences = new LinkedHashMap<>();
        Map<String, String> invalidPlaceholderOccurrences = new LinkedHashMap<>();
        Set<String> expandedPlaceholders = new LinkedHashSet<>();
        List<String> sourceEvidence = record.assumptions().stream()
            .filter(assumption -> assumption != null && assumption.startsWith("substitution."))
            .toList();
        String substitutedExpression = "";
        for (String assumption : sourceEvidence) {
            int separatorIndex = assumption.indexOf('=');
            if (separatorIndex < 0) {
                continue;
            }
            String key = assumption.substring(0, separatorIndex);
            String value = assumption.substring(separatorIndex + 1);
            if (key.startsWith("substitution.placeholder.")) {
                placeholderMappings.put(key.substring("substitution.placeholder.".length()), value);
                continue;
            }
            if (key.startsWith("substitution.occurrences.")) {
                String placeholder = key.substring("substitution.occurrences.".length());
                try {
                    placeholderOccurrences.put(placeholder, Integer.parseInt(value));
                } catch (NumberFormatException ignored) {
                    invalidPlaceholderOccurrences.put(placeholder, value);
                }
                continue;
            }
            if (key.equals("substitution.substituted")) {
                substitutedExpression = value;
                continue;
            }
            if (key.startsWith("substitution.expanded.") && "true".equalsIgnoreCase(value)) {
                expandedPlaceholders.add(key.substring("substitution.expanded.".length()));
            }
        }
        String discoveredStructure = !substitutedExpression.isBlank()
            ? substitutedExpression
            : record.discoveredStructure();
        Map<String, String> placeholdersToExpand = expandedPlaceholders.isEmpty()
            ? Map.of()
            : placeholderMappings.entrySet().stream()
                .filter(entry -> expandedPlaceholders.contains(entry.getKey()))
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue,
                    (left, right) -> left,
                    LinkedHashMap::new
                ));
        String expandedExpression = placeholdersToExpand.isEmpty()
            ? ""
            : expandPlaceholders(
                substitutedExpression.isBlank() ? record.discoveredStructure() : substitutedExpression,
                placeholdersToExpand
            );
        String rewrittenAfter = !substitutedExpression.isBlank()
            ? substitutedExpression
            : record.discoveredStructure();
        String affectedPathKey = record.assumptions().stream()
            .filter(a -> a != null && a.startsWith("treePosition.pathKey="))
            .map(a -> a.substring("treePosition.pathKey=".length()))
            .findFirst()
            .orElse("");
        String positionBefore = record.assumptions().stream()
            .filter(a -> a != null && a.startsWith("treePosition.before="))
            .map(a -> a.substring("treePosition.before=".length()))
            .findFirst()
            .orElse("");
        String positionAfter = record.assumptions().stream()
            .filter(a -> a != null && a.startsWith("treePosition.after="))
            .map(a -> a.substring("treePosition.after=".length()))
            .findFirst()
            .orElse("");
        return new DiscoveryHighlightModel(
            record.originalExpression(),
            discoveredStructure,
            placeholderMappings,
            placeholderOccurrences,
            invalidPlaceholderOccurrences,
            substitutedExpression,
            expandedExpression,
            record.originalExpression(),
            rewrittenAfter,
            sourceEvidence,
            affectedPathKey,
            positionBefore,
            positionAfter
        );
    }

    private String expandPlaceholders(String expression, Map<String, String> placeholderMappings) {
        if (expression == null || expression.isBlank() || placeholderMappings.isEmpty()) {
            return "";
        }
        String expanded = expression;
        List<Map.Entry<String, String>> orderedMappings = placeholderMappings.entrySet().stream()
            .sorted((left, right) -> Integer.compare(right.getKey().length(), left.getKey().length()))
            .toList();
        for (Map.Entry<String, String> entry : orderedMappings) {
            String placeholder = entry.getKey();
            if (placeholder == null || placeholder.isBlank()) {
                continue;
            }
            Pattern tokenPattern = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(placeholder) + "(?![A-Za-z0-9_])");
            Matcher matcher = tokenPattern.matcher(expanded);
            expanded = matcher.replaceAll(Matcher.quoteReplacement("(" + entry.getValue() + ")"));
        }
        return expanded.equals(expression) ? "" : expanded;
    }

    private String abstractedSubexpression(DiscoveryHighlightModel highlightModel) {
        if (highlightModel.placeholderMappings().isEmpty()) {
            return "";
        }
        return highlightModel.placeholderMappings().entrySet().stream()
            .map(entry -> {
                String placeholder = entry.getKey();
                String mapping = placeholder + " -> " + entry.getValue();
                Integer occurrences = highlightModel.placeholderOccurrences().get(placeholder);
                return occurrences == null ? mapping : mapping + " (occurrences=" + occurrences + ")";
            })
            .findFirst()
            .orElse("");
    }

    private String renderPlaceholderMappings(DiscoveryHighlightModel highlightModel) {
        if (highlightModel.placeholderMappings().isEmpty()) {
            return "no substitution evidence recorded";
        }
        return highlightModel.placeholderMappings().entrySet().stream()
            .map(entry -> {
                String placeholder = entry.getKey();
                Integer occurrences = highlightModel.placeholderOccurrences().get(placeholder);
                return occurrences == null
                    ? placeholder + " -> " + entry.getValue()
                    : placeholder + " -> " + entry.getValue() + " (occurrences=" + occurrences + ")";
            })
            .reduce((left, right) -> left + "; " + right)
            .orElse("no substitution evidence recorded");
    }

    private String renderSourceEvidence(DiscoveryHighlightModel highlightModel) {
        List<String> evidence = new ArrayList<>(highlightModel.sourceEvidence());
        if (!highlightModel.invalidPlaceholderOccurrences().isEmpty()) {
            String invalid = highlightModel.invalidPlaceholderOccurrences().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
            evidence.add("ignored.invalid.occurrences=" + invalid);
        }
        if (evidence.isEmpty()) {
            return "none";
        }
        return String.join("; ", evidence);
    }

    private PromotionDashboard buildDashboard(List<PromotionRecord> records) {
        long observed = records.size();
        long candidate = records.stream().filter(record -> record.stage().atLeast(PromotionStage.CANDIDATE)).count();
        long validated = records.stream().filter(record -> record.stage().atLeast(PromotionStage.VALIDATED)).count();
        long promoted = records.stream().filter(record -> record.stage().atLeast(PromotionStage.PROMOTED)).count();
        long reused = records.stream().filter(record -> record.stage().atLeast(PromotionStage.REUSED)).count();
        long oracleContradictions = records.stream()
            .filter(record -> "DISAGREE".equalsIgnoreCase(record.oracleStatus()))
            .count();
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
        List<CampaignMetric> campaignProgress = campaignMetrics(records);
        return new PromotionDashboard(
            observed,
            candidate,
            validated,
            promoted,
            reused,
            conversionRates(observed, candidate, validated, promoted, reused),
            topPromoted,
            unresolved,
            oracleContradictions,
            campaignProgress
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
                    out.append("- ").append(escapeMarkdownInline(candidate.candidateId()))
                    .append(" [").append(candidate.stage().name().toLowerCase(Locale.ROOT)).append("]")
                        .append(" oracle=").append(escapeMarkdownInline(candidate.oracleStatus()))
                        .append(" ablation=").append(escapeMarkdownInline(candidate.ablationStatus()))
                    .append(" reuseImprovement=").append(candidate.measuredImprovement() ? "yes" : "no")
                    .append('\n');
            }
        }
        out.append("\n## Unresolved blockers\n\n");
        if (dashboard.unresolvedBlockers().isEmpty()) {
            out.append("- none\n");
        } else {
            for (UnresolvedBlocker blocker : dashboard.unresolvedBlockers()) {
                out.append("- ").append(escapeMarkdownInline(blocker.candidateId()))
                    .append(" [").append(blocker.stage().name().toLowerCase(Locale.ROOT)).append("]")
                    .append(": ").append(escapeMarkdownInline(String.join(", ", blocker.blockers())))
                    .append('\n');
            }
        }
        out.append("\n## Oracle contradictions\n\n");
        out.append("- oracle-disagree count: ").append(dashboard.oracleContradictions()).append('\n');
        out.append("\n## Campaign progress\n\n");
        if (dashboard.campaignProgress().isEmpty()) {
            out.append("- none\n");
        } else {
            out.append("| Campaign | Observed | Candidate | Validated | Promoted | Reused |\n")
                .append("| --- | ---: | ---: | ---: | ---: | ---: |\n");
            for (CampaignMetric m : dashboard.campaignProgress()) {
                out.append("| ").append(escapeMarkdownTableCell(m.campaign()))
                    .append(" | ").append(m.observed())
                    .append(" | ").append(m.candidate())
                    .append(" | ").append(m.validated())
                    .append(" | ").append(m.promoted())
                    .append(" | ").append(m.reused())
                    .append(" |\n");
            }
        }
        return out.toString();
    }

    String renderGallery(List<PromotionRecord> records) {
        StringBuilder out = new StringBuilder("# Gallery 2.0\n\n");
        out.append("## Selection policy\n\n")
            .append("- Candidate is selected only if `fallbackUsed=false` and `curatedPathPresent=false`.\n")
            .append("- Additionally, candidate must be `promotionEligible=true` or have stage `reused` (or higher).\n\n");
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
            out.append("| ").append(escapeMarkdownTableCell(record.candidateId()))
                .append(" | ").append(record.stage().name().toLowerCase(Locale.ROOT))
                .append(" | ").append(escapeMarkdownTableCell(inlineCodeOrDash(record.originalExpression())))
                .append(" | ").append(escapeMarkdownTableCell(inlineCodeOrDash(record.discoveredStructure())))
                .append(" | ").append(escapeMarkdownTableCell(inlinePath(record.rulePath())))
                .append(" | ").append(escapeMarkdownTableCell(
                    record.reusedMacroIds().isEmpty() ? "—" : String.join(", ", record.reusedMacroIds())))
                .append(" |\n");
        }
        out.append("\n## Entry details\n\n");
        for (PromotionRecord record : selected) {
            DiscoveryHighlightModel highlightModel = highlightModel(record);
            out.append("### ").append(escapeMarkdownInline(record.candidateId())).append("\n\n");
            out.append("- **Why interesting?** ").append(escapeMarkdownInline(galleryInterestReason(record))).append('\n');
            out.append("- **Detected structure:** ").append(inlineCodeOrDash(highlightModel.discoveredStructure())).append('\n');
            if (!highlightModel.placeholderMappings().isEmpty()) {
                out.append("- **Hidden structure abstraction:** ").append(escapeMarkdownInline(renderPlaceholderMappings(highlightModel))).append('\n');
            }
            out.append("- **Bridge/operator used:** ").append(escapeMarkdownInline(orDash(timelineMiddle(record)))).append('\n');
            String pathKey = highlightModel.affectedPathKey().isBlank() ? "root" : highlightModel.affectedPathKey();
            out.append("- **Affected TreePosition:** ").append(inlineCodeOrDash(pathKey)).append('\n');
            out.append("- **Why path works:** ").append(escapeMarkdownInline(galleryPathReason(record))).append('\n');
            out.append("- **Ablation:** ").append(escapeMarkdownInline(record.ablationStatus())).append('\n');
            out.append('\n');
        }
        return out.toString();
    }

    private String galleryInterestReason(PromotionRecord record) {
        return markdownExplanationRenderer.renderReasons(
            explanationFactory.buildInterestReasons(record),
            "gallery-eligible by stage and promotion criteria"
        );
    }

    private String galleryPathReason(PromotionRecord record) {
        return markdownExplanationRenderer.renderReasons(explanationFactory.buildPathReasons(record), "—");
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
            out.append("- ").append(escapeMarkdownInline(record.candidateId()))
                .append(" [").append(record.stage().name().toLowerCase(Locale.ROOT)).append("]")
                .append(": ").append(escapeMarkdownInline(record.promotionBlockers().isEmpty()
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
            out.append("- ").append(escapeMarkdownInline(record.candidateId()))
                .append(": investigate operator support for family ")
                .append(escapeMarkdownInline(record.family()))
                .append(" (campaign=").append(escapeMarkdownInline(record.sourceCampaign())).append(")\n");
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
            out.append("- ").append(escapeMarkdownInline(record.candidateId()))
                .append(": ").append(escapeMarkdownInline(String.join(" -> ", record.rulePath())))
                .append(" (stage=").append(record.stage().name().toLowerCase(Locale.ROOT)).append(")\n");
        }
        return out.toString();
    }

    String renderOperatorImpactView(List<PromotionRecord> records) {
        StringBuilder out = new StringBuilder("# Operator impact\n\n");

        Map<String, Long> helpingCounts = records.stream()
            .filter(record -> record.stage().atLeast(PromotionStage.PROMOTED))
            .filter(record -> !record.sourceOperator().isBlank())
            .collect(Collectors.groupingBy(PromotionRecord::sourceOperator, Collectors.counting()));
        Map<String, Long> blockingCounts = records.stream()
            .filter(PromotionRecord::unresolved)
            .filter(record -> !record.sourceOperator().isBlank())
            .collect(Collectors.groupingBy(PromotionRecord::sourceOperator, Collectors.counting()));
        Map<String, Long> improvingCounts = records.stream()
            .filter(PromotionRecord::measuredImprovement)
            .filter(record -> !record.sourceOperator().isBlank())
            .collect(Collectors.groupingBy(PromotionRecord::sourceOperator, Collectors.counting()));

        out.append("## Operators that help (appear in promoted or reused records)\n\n");
        if (helpingCounts.isEmpty()) {
            out.append("- none\n");
        } else {
            helpingCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                    .thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> out.append("- ").append(escapeMarkdownInline(entry.getKey()))
                    .append(": promoted-or-reused=").append(entry.getValue()).append('\n'));
        }

        out.append("\n## Operators that block (appear in unresolved records)\n\n");
        if (blockingCounts.isEmpty()) {
            out.append("- none\n");
        } else {
            blockingCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                    .thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> out.append("- ").append(escapeMarkdownInline(entry.getKey()))
                    .append(": blocked=").append(entry.getValue()).append('\n'));
        }

        out.append("\n## Operators with measured improvement\n\n");
        if (improvingCounts.isEmpty()) {
            out.append("- none\n");
        } else {
            improvingCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                    .thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> out.append("- ").append(escapeMarkdownInline(entry.getKey()))
                    .append(": measured-improvement=").append(entry.getValue()).append('\n'));
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

    private String uniqueSlug(String baseSlug, Map<String, Integer> slugCounts) {
        int occurrence = slugCounts.merge(baseSlug, 1, Integer::sum);
        return occurrence == 1 ? baseSlug : baseSlug + "-" + occurrence;
    }

    private String promotionDecision(PromotionRecord record) {
        if (record.promotionEligible()) {
            return "eligible: oracle and ablation checks are satisfied for promotion";
        }
        if (!record.promotionBlockers().isEmpty()) {
            return "not eligible: " + String.join(", ", record.promotionBlockers());
        }
        return "not eligible: promotion evidence is incomplete";
    }

    private String missingPieces(PromotionRecord record) {
        if (record.promotionEligible()) {
            return "none";
        }
        if (!record.promotionBlockers().isEmpty()) {
            return String.join(", ", record.promotionBlockers());
        }
        return orDash(record.rationale());
    }

    private String inlineCodeOrDash(String value) {
        String normalized = normalizeMarkdownText(orDash(value));
        if (normalized.equals("—")) {
            return "—";
        }
        int longestRun = longestBacktickRun(normalized);
        String fence = "`".repeat(longestRun + 1);
        return fence + normalized + fence;
    }

    private int longestBacktickRun(String value) {
        int longest = 0;
        int current = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '`') {
                current++;
                longest = Math.max(longest, current);
            } else {
                current = 0;
            }
        }
        return longest;
    }

    private String normalizeMarkdownText(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\n', ' ');
    }

    private String escapeMarkdownInline(String value) {
        return normalizeMarkdownText(value);
    }

    private String escapeMarkdownTableCell(String value) {
        return normalizeMarkdownText(value).replace("|", "\\|");
    }

    record PipelineReport(
        List<PromotionRecord> promotionRecords,
        PromotionRegistry.Registry registry,
        DiscoveryCampaignFiveRunner.CampaignReport campaignFive,
        DiscoveryCampaignFourRunner.CampaignReport campaignFour,
        DiscoveryCampaignSevenRunner.CampaignReport campaignSeven,
        DiscoveryCampaignEightRunner.CampaignReport campaignEight,
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
        List<UnresolvedBlocker> unresolvedBlockers,
        long oracleContradictions,
        List<CampaignMetric> campaignProgress
    ) {
        PromotionDashboard {
            conversionRates = conversionRates == null ? Map.of() : Map.copyOf(conversionRates);
            topPromotedCandidates = topPromotedCandidates == null ? List.of() : List.copyOf(topPromotedCandidates);
            unresolvedBlockers = unresolvedBlockers == null ? List.of() : List.copyOf(unresolvedBlockers);
            campaignProgress = campaignProgress == null ? List.of() : List.copyOf(campaignProgress);
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

    record DiscoveryHighlightModel(
        String originalExpression,
        String discoveredStructure,
        Map<String, String> placeholderMappings,
        Map<String, Integer> placeholderOccurrences,
        Map<String, String> invalidPlaceholderOccurrences,
        String substitutedExpression,
        String expandedExpression,
        String rewrittenBefore,
        String rewrittenAfter,
        List<String> sourceEvidence,
        String affectedPathKey,
        String positionBefore,
        String positionAfter
    ) {
        DiscoveryHighlightModel {
            placeholderMappings = placeholderMappings == null ? Map.of() : Map.copyOf(placeholderMappings);
            placeholderOccurrences = placeholderOccurrences == null ? Map.of() : Map.copyOf(placeholderOccurrences);
            invalidPlaceholderOccurrences = invalidPlaceholderOccurrences == null
                ? Map.of()
                : Map.copyOf(invalidPlaceholderOccurrences);
            sourceEvidence = sourceEvidence == null ? List.of() : List.copyOf(sourceEvidence);
            affectedPathKey = affectedPathKey == null ? "" : affectedPathKey;
            positionBefore = positionBefore == null ? "" : positionBefore;
            positionAfter = positionAfter == null ? "" : positionAfter;
        }
    }
}
