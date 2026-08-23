package de.regelsuche.docs;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.docs.GenerationalRuleMiningCampaign.ActivatedRule;
import de.regelsuche.docs.GenerationalRuleMiningCampaign.CampaignReport;
import de.regelsuche.docs.GenerationalRuleMiningCampaign.GenerationReport;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.DynamicOperatorCompiler;
import de.regelsuche.mining.DynamicPatternOperator;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalMetrics;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.HypothesisOperator;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.OccurrenceAwareAstRewriteTransformationEngine;
import de.regelsuche.transform.TransformationEngine;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Independently audits that a later mining generation contributes reachability
 * which the preceding accumulated shadow inventory does not have under the same
 * deliberately tight search-depth budget.
 *
 * <p>The campaign's ordinary report compares its empty and accumulated shadow
 * inventories. This audit is stricter: it recompiles the retained exact rules,
 * compares generations {@code 0+1} with generations {@code 0+1+2}, and requires
 * the successful path to use a generation-2 rule. It also checks the retained
 * campaign paths for actual generation-0 to generation-1 and generation-1 to
 * generation-2 rule reuse.</p>
 */
public final class GenerationalRuleMiningReachabilityAudit {
    public static final String SCHEMA =
        "regelsuche.generational-rule-mining-reachability-audit/v1";

    private static final int PREVIOUS_GENERATION = 1;
    private static final int ACCUMULATED_GENERATION = 2;
    private static final SearchHeuristic AUDIT_HEURISTIC =
        new SearchHeuristic(2, 1_200, 1, 32, 1_200, 1_200);

    private final DynamicOperatorCompiler compiler = new DynamicOperatorCompiler();
    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();
    private final ExpressionScorer scorer = new ExpressionScorer();

    public AuditReport audit(CampaignReport campaign) {
        Objects.requireNonNull(campaign, "campaign");
        if (campaign.generations().size() < 3) {
            throw new IllegalArgumentException(
                "cumulative audit requires at least three generations");
        }

        GenerationReport generation0 = generation(campaign, 0);
        GenerationReport generation1 = generation(campaign, 1);
        GenerationReport generation2 = generation(campaign, 2);

        Set<String> generation0RuleIds = ruleIds(generation0.activatedRules());
        Set<String> generation1RuleIds = ruleIds(generation1.activatedRules());
        boolean generation1ReusedGeneration0 = generationUsesAny(
            generation1,
            generation0RuleIds);
        boolean generation2ReusedGeneration1 = generationUsesAny(
            generation2,
            generation1RuleIds);

        List<ActivatedRule> previousRules = campaign.finalRules().stream()
            .filter(rule -> rule.generation() <= PREVIOUS_GENERATION)
            .sorted(Comparator.comparing(ActivatedRule::candidateHash))
            .toList();
        List<ActivatedRule> accumulatedRules = campaign.finalRules().stream()
            .filter(rule -> rule.generation() <= ACCUMULATED_GENERATION)
            .sorted(Comparator.comparing(ActivatedRule::candidateHash))
            .toList();
        if (previousRules.isEmpty() || accumulatedRules.size() <= previousRules.size()) {
            throw new IllegalArgumentException(
                "campaign does not contain a later accumulated rule generation");
        }

        CompiledInventory previous = compileInventory(
            campaign,
            PREVIOUS_GENERATION,
            previousRules);
        CompiledInventory accumulated = compileInventory(
            campaign,
            ACCUMULATED_GENERATION,
            accumulatedRules);

        String input = repeatWrap("x", 3);
        String target = "x";
        SearchOutcome previousOutcome = search(previous.engine(), input, target);
        SearchOutcome accumulatedOutcome = search(accumulated.engine(), input, target);
        Set<String> generation2AuditRuleIds = accumulated.ruleIdsByGeneration()
            .getOrDefault(ACCUMULATED_GENERATION, Set.of());
        boolean accumulatedPathUsesGeneration2 = intersects(
            accumulatedOutcome.appliedRuleIds(),
            generation2AuditRuleIds);
        boolean newlyReachableByGeneration2 =
            !previousOutcome.reached()
                && accumulatedOutcome.reached()
                && accumulatedPathUsesGeneration2;

        return new AuditReport(
            SCHEMA,
            campaign.campaignId(),
            campaign.repositoryRevision(),
            PREVIOUS_GENERATION,
            ACCUMULATED_GENERATION,
            AUDIT_HEURISTIC.maxDepth(),
            sha256(input),
            sha256(target),
            generation1ReusedGeneration0,
            generation2ReusedGeneration1,
            accumulatedPathUsesGeneration2,
            newlyReachableByGeneration2,
            previous.ruleIdsByGeneration(),
            accumulated.ruleIdsByGeneration(),
            previousOutcome,
            accumulatedOutcome,
            "Development audit only: cumulative shadow reachability is not production promotion or external mathematical novelty.");
    }

    public Path write(Path output, AuditReport report) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(report, "report");
        try {
            Path normalized = output.toAbsolutePath().normalize();
            Path parent = normalized.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(normalized, report.toJson(), StandardCharsets.UTF_8);
            return output;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private CompiledInventory compileInventory(
        CampaignReport campaign,
        int maximumGeneration,
        List<ActivatedRule> rules
    ) {
        List<CompiledRule> compiled = rules.stream()
            .map(rule -> compileRule(campaign, maximumGeneration, rule))
            .toList();
        List<HypothesisOperator> operators = compiled.stream()
            .map(CompiledRule::operator)
            .map(operator -> (HypothesisOperator) operator)
            .toList();
        TransformationEngine base =
            new OccurrenceAwareAstRewriteTransformationEngine(List.of());
        TransformationEngine engine = new HypothesisTransformationEngine(
            base,
            operators,
            Math.max(16, operators.size() * 8));
        Map<Integer, Set<String>> byGeneration = new LinkedHashMap<>();
        compiled.stream()
            .sorted(Comparator
                .comparingInt((CompiledRule rule) -> rule.source().generation())
                .thenComparing(rule -> rule.source().candidateHash()))
            .forEach(rule -> byGeneration.computeIfAbsent(
                rule.source().generation(),
                ignored -> new java.util.LinkedHashSet<>()).add(
                    rule.operator().ruleId()));
        Map<Integer, Set<String>> immutable = new LinkedHashMap<>();
        byGeneration.forEach((generation, ids) ->
            immutable.put(generation, Set.copyOf(ids)));
        return new CompiledInventory(engine, Map.copyOf(immutable));
    }

    private CompiledRule compileRule(
        CampaignReport campaign,
        int maximumGeneration,
        ActivatedRule rule
    ) {
        String suffix = rule.candidateHash().substring("sha256:".length(), 20);
        DynamicOperatorCompiler.CompilationResult result = compiler.compile(
            "cumulative-audit-g" + maximumGeneration + "-" + suffix,
            campaign.finalInventoryHash(),
            rule.leftPattern(),
            rule.rightPattern());
        if (!result.isSuccess()) {
            throw new IllegalStateException(
                "retained exact rule could not be recompiled: "
                    + rule.candidateHash() + ": " + result.rejectionReason());
        }
        return new CompiledRule(rule, result.operator().orElseThrow());
    }

    private SearchOutcome search(
        TransformationEngine engine,
        String input,
        String target
    ) {
        SearchProblem problem = new SearchProblem(
            input,
            engine,
            scorer,
            canonicalizer,
            AUDIT_HEURISTIC).withTarget(syntaxTarget(target));
        GoalSearchResult result = new BestFirstSearchStrategy()
            .searchWithDiagnostics(problem);
        SearchState reached = result.reachedState();
        return new SearchOutcome(
            result.reached(),
            result.status().name(),
            result.metrics(),
            reached == null ? List.of() : reached.path(),
            reached == null ? List.of() : reached.appliedRuleIds());
    }

    private SearchTarget syntaxTarget(String expression) {
        return SearchTarget.syntaxExact(
            ExpressionFormatter.format(parser.parseTerm(expression)));
    }

    private static GenerationReport generation(
        CampaignReport campaign,
        int number
    ) {
        return campaign.generations().stream()
            .filter(generation -> generation.generation() == number)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "missing generation " + number));
    }

    private static boolean generationUsesAny(
        GenerationReport generation,
        Set<String> previousRuleIds
    ) {
        if (previousRuleIds.isEmpty()) {
            return false;
        }
        return generation.tasks().stream()
            .anyMatch(task -> intersects(task.appliedRuleIds(), previousRuleIds));
    }

    private static Set<String> ruleIds(List<ActivatedRule> rules) {
        return rules.stream()
            .map(ActivatedRule::operatorRuleId)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static boolean intersects(
        List<String> appliedRuleIds,
        Set<String> expectedRuleIds
    ) {
        if (appliedRuleIds.isEmpty() || expectedRuleIds.isEmpty()) {
            return false;
        }
        Set<String> intersection = new HashSet<>(appliedRuleIds);
        intersection.retainAll(expectedRuleIds);
        return !intersection.isEmpty();
    }

    private static String repeatWrap(String expression, int count) {
        String result = expression;
        for (int i = 0; i < count; i++) {
            result = "(((" + result + ") + 0) * 1) * 1 + 0";
        }
        return result;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record CompiledRule(
        ActivatedRule source,
        DynamicPatternOperator operator
    ) {
    }

    private record CompiledInventory(
        TransformationEngine engine,
        Map<Integer, Set<String>> ruleIdsByGeneration
    ) {
    }

    public record SearchOutcome(
        boolean reached,
        String status,
        GoalMetrics metrics,
        List<String> path,
        List<String> appliedRuleIds
    ) {
        public SearchOutcome {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(metrics, "metrics");
            path = List.copyOf(path);
            appliedRuleIds = List.copyOf(appliedRuleIds);
        }
    }

    public record AuditReport(
        String schema,
        String campaignId,
        String repositoryRevision,
        int previousGeneration,
        int accumulatedGeneration,
        int maximumSearchDepth,
        String inputFingerprint,
        String targetFingerprint,
        boolean generation1ReusedGeneration0,
        boolean generation2ReusedGeneration1,
        boolean accumulatedPathUsesGeneration2,
        boolean newlyReachableByGeneration2,
        Map<Integer, Set<String>> previousRuleIdsByGeneration,
        Map<Integer, Set<String>> accumulatedRuleIdsByGeneration,
        SearchOutcome previousOutcome,
        SearchOutcome accumulatedOutcome,
        String claimBoundary
    ) {
        public AuditReport {
            Objects.requireNonNull(schema, "schema");
            Objects.requireNonNull(campaignId, "campaignId");
            Objects.requireNonNull(repositoryRevision, "repositoryRevision");
            Objects.requireNonNull(inputFingerprint, "inputFingerprint");
            Objects.requireNonNull(targetFingerprint, "targetFingerprint");
            previousRuleIdsByGeneration = immutableMap(previousRuleIdsByGeneration);
            accumulatedRuleIdsByGeneration = immutableMap(
                accumulatedRuleIdsByGeneration);
            Objects.requireNonNull(previousOutcome, "previousOutcome");
            Objects.requireNonNull(accumulatedOutcome, "accumulatedOutcome");
            Objects.requireNonNull(claimBoundary, "claimBoundary");
        }

        public boolean passed() {
            return generation1ReusedGeneration0
                && generation2ReusedGeneration1
                && accumulatedPathUsesGeneration2
                && newlyReachableByGeneration2;
        }

        public String toJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("campaignId", campaignId)
                .property("repositoryRevision", repositoryRevision)
                .property("claimBoundary", claimBoundary)
                .object("generationBoundary", object -> object
                    .property("previousGeneration", previousGeneration)
                    .property("accumulatedGeneration", accumulatedGeneration)
                    .property("maximumSearchDepth", maximumSearchDepth))
                .object("reuse", object -> object
                    .property("generation1ReusedGeneration0",
                        generation1ReusedGeneration0)
                    .property("generation2ReusedGeneration1",
                        generation2ReusedGeneration1))
                .object("subject", object -> object
                    .property("inputFingerprint", inputFingerprint)
                    .property("targetFingerprint", targetFingerprint))
                .object("decision", object -> object
                    .property("accumulatedPathUsesGeneration2",
                        accumulatedPathUsesGeneration2)
                    .property("newlyReachableByGeneration2",
                        newlyReachableByGeneration2)
                    .property("passed", passed()))
                .object("previousInventory", object -> object
                    .array("ruleGenerations", array ->
                        writeRuleGenerations(array, previousRuleIdsByGeneration))
                    .object("search", search ->
                        writeSearchOutcome(search, previousOutcome)))
                .object("accumulatedInventory", object -> object
                    .array("ruleGenerations", array ->
                        writeRuleGenerations(array, accumulatedRuleIdsByGeneration))
                    .object("search", search ->
                        writeSearchOutcome(search, accumulatedOutcome)))
                .endObject()
                .toString();
        }

        private static Map<Integer, Set<String>> immutableMap(
            Map<Integer, Set<String>> input
        ) {
            Objects.requireNonNull(input, "input");
            Map<Integer, Set<String>> copy = new LinkedHashMap<>();
            input.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> copy.put(
                    entry.getKey(),
                    Set.copyOf(entry.getValue())));
            return Map.copyOf(copy);
        }

        private static void writeRuleGenerations(
            JsonWriter array,
            Map<Integer, Set<String>> ruleIdsByGeneration
        ) {
            ruleIdsByGeneration.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> array.objectValue(object -> object
                    .property("generation", entry.getKey())
                    .stringArray("ruleIds", entry.getValue().stream()
                        .sorted()
                        .toList())));
        }

        private static void writeSearchOutcome(
            JsonWriter json,
            SearchOutcome outcome
        ) {
            json.property("reached", outcome.reached())
                .property("status", outcome.status())
                .object("metrics", metrics -> metrics
                    .property("exploredStates", outcome.metrics().exploredStates())
                    .property("expandedStates", outcome.metrics().expandedStates())
                    .property("generatedTransformations",
                        outcome.metrics().generatedTransformations())
                    .property("enqueuedStates", outcome.metrics().enqueuedStates())
                    .property("duplicatePrunes", outcome.metrics().duplicatePrunes())
                    .property("transpositionPrunes",
                        outcome.metrics().transpositionPrunes())
                    .property("depthPrunes", outcome.metrics().depthPrunes())
                    .property("candidateBudgetPrunes",
                        outcome.metrics().candidateBudgetPrunes()))
                .stringArray("path", outcome.path())
                .stringArray("appliedRuleIds", outcome.appliedRuleIds());
        }
    }
}
