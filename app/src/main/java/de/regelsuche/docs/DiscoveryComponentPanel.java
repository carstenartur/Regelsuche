package de.regelsuche.docs;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.mining.DiscoveryDemos;
import de.regelsuche.mining.DiscoverySettings;
import de.regelsuche.mining.HypothesisCandidate;
import de.regelsuche.mining.HypothesisRefinementLoop;
import de.regelsuche.mining.HypothesisRevision;
import de.regelsuche.mining.InterestingnessRankingStrategy;
import de.regelsuche.mining.KnownRuleRepository;
import de.regelsuche.mining.RefinementStrategy;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.mining.RuleCandidateMiner;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.mining.SuccessfulTransformationPath;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.validation.CounterexampleSearchService;
import de.regelsuche.validation.CounterexampleSearchService.CounterexampleBudget;
import de.regelsuche.validation.CounterexampleSearchService.CounterexampleSearchResult;
import de.regelsuche.validation.CounterexampleSearchService.HypothesisInput;
import de.regelsuche.validation.DeterministicCounterexampleSearchService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Four preregistered component arms on the same public historical observations. */
public final class DiscoveryComponentPanel {
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private static final String PLAN = "67b8eb0345324b024adad2bfa92265ed3f556ba4";
    enum Arm { PRODUCTION, NO_FAN_IN, NO_REFINEMENT, NO_NOVELTY_SCHEDULING }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected destination JSON file");
        Path output = Path.of(args[0]).toAbsolutePath();
        var bytes = JSON.writeValueAsBytes(run());
        Files.createDirectories(output.getParent());
        Path temporary = Files.createTempFile(output.getParent(), "components-", ".json");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    static ObjectNode run() throws Exception {
        var report = JSON.createObjectNode();
        report.put("schema", "regelsuche.public-discovery-component-panel/v1");
        report.put("protocolCommit", PLAN);
        report.put("fixtureRevision", "03b58d1dfdd9c5076622f1f9bf35dd9c618bf897");
        var observations = observations();
        var training = new ReferenceCampaignRunner().runTraining();
        var control = ReferenceCampaignRunner.initialLogChallenge(training);
        var inputs = report.putObject("inputs");
        inputs.set("formationObservations", JSON.valueToTree(observations));
        inputs.set("knownRuleCatalogue", JSON.valueToTree(new KnownRuleRepository().all()));
        inputs.set("miningSettings", JSON.valueToTree(DiscoverySettings.defaults()));
        inputs.set("historicalChallengeTraining", JSON.valueToTree(training));
        inputs.set("historicalChallenge", hypothesis(control));
        inputs.set("budgetPerChallenge", JSON.valueToTree(CounterexampleBudget.defaultBudget()));
        // No arm can consume more slots than there are public observation clusters (two per observation).
        int candidateSlots = observations.size() * 2;
        inputs.put("candidateSlots", candidateSlots);
        inputs.put("separatePositiveControlSlots", 1);
        inputs.put("maxChallengesPerSlot", HypothesisRefinementLoop.DEFAULT_MAX_REVISIONS);
        inputs.put("terminalRechecksPerOccupiedSlot", 1);
        String inputHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(inputs)));
        var arms = report.putObject("arms");
        for (Arm arm : Arm.values()) {
            var row = arms.putObject(arm.name());
            row.put("inputSha256", inputHash);
            var miner = new RuleCandidateMiner(new KnownRuleRepository());
            var dispatches = row.putArray("miningDispatches");
            List<RuleCandidate> candidates = new ArrayList<>();
            List<List<SuccessfulTransformationPath>> groups = arm == Arm.NO_FAN_IN
                ? observations.stream().map(List::of).toList() : List.of(observations);
            for (var group : groups) {
                var mined = miner.mine(group);
                var dispatch = dispatches.addObject();
                dispatch.set("observationIds", JSON.valueToTree(group.stream().map(SuccessfulTransformationPath::id).toList()));
                dispatch.set("rawCandidates", JSON.valueToTree(mined));
                candidates.addAll(mined);
            }
            var similarities = new LinkedHashMap<String, Double>();
            var domains = new LinkedHashMap<String, Set<String>>();
            List<HypothesisCandidate> nominees = new ArrayList<>();
            for (RuleCandidate candidate : candidates) {
                var hypothesis = HypothesisCandidate.from(candidate, 0, observations);
                nominees.add(hypothesis);
                similarities.put(hypothesis.id(), arm != Arm.NO_NOVELTY_SCHEDULING
                    && candidate.status() != RuleStatus.NEW ? 1.0 : 0.0);
                var tags = new TreeSet<String>();
                observations.stream().filter(p -> candidate.supportingTransformationIds().contains(p.id()))
                    .forEach(p -> {
                        for (String key : List.of("domain", "category")) {
                            String tag = p.variableStructure().get(key);
                            if (tag != null && !tag.isBlank()) tags.add(tag);
                        }
                    });
                domains.put(hypothesis.id(), tags);
            }
            var ranking = new InterestingnessRankingStrategy().rank(nominees, similarities, domains);
            if (ranking.size() > candidateSlots) throw new IllegalStateException("Candidate ceiling exceeded");
            row.set("projectSimilarityUsed", JSON.valueToTree(similarities));
            row.set("domainTagsUsed", JSON.valueToTree(domains));
            var queue = row.putArray("frozenSchedule");
            for (var ranked : ranking) {
                var item = queue.addObject();
                item.put("rank", queue.size());
                item.set("inputHypothesis", hypothesis(ranked.hypothesis()));
                item.set("productionScore", JSON.valueToTree(ranked.score()));
            }
            var prefixes = row.putArray("selectionAtSlotCeiling");
            var ids = ranking.stream().map(item -> item.hypothesis().id()).toList();
            for (int limit = 0; limit <= candidateSlots; limit++) {
                var prefix = prefixes.addObject();
                prefix.put("ceiling", limit);
                prefix.set("selectedCandidateIds", JSON.valueToTree(ids.subList(0, Math.min(limit, ids.size()))));
            }
            var measured = row.putArray("challengesInFrozenOrder");
            for (var ranked : ranking) measured.add(challenge(ranked.hypothesis(), arm));
            row.put("unusedCandidateSlots", candidateSlots - ranking.size());
            row.set("separateHistoricalChallenge", challenge(control, arm));
        }
        return report;
    }

    private static ObjectNode hypothesis(HypothesisCandidate candidate) {
        var value = JSON.createObjectNode();
        value.put("id", candidate.id()).put("left", candidate.leftPattern()).put("right", candidate.rightPattern());
        value.set("assumptions", JSON.valueToTree(candidate.assumptions()));
        value.set("supportingPaths", JSON.valueToTree(candidate.supportingPaths()));
        value.set("witnesses", JSON.valueToTree(candidate.supportingExpressions()));
        value.set("parameterRelations", JSON.valueToTree(candidate.parameterRelations()));
        value.set("expressionPlaceholders", JSON.valueToTree(candidate.expressionPlaceholders()));
        value.put("initialNoveltyScore", candidate.noveltyScore());
        value.put("initialProofStatus", candidate.proofStatus().name());
        return value;
    }

    private static ObjectNode challenge(HypothesisCandidate candidate, Arm arm) {
        var result = JSON.createObjectNode();
        result.put("candidateId", candidate.id());
        var records = result.putArray("challenges");
        var validator = new DeterministicCounterexampleSearchService();
        CounterexampleSearchService recorded = (input, budget) -> {
            var observed = validator.search(input, budget);
            var record = records.addObject();
            record.set("input", JSON.valueToTree(input));
            record.set("budget", JSON.valueToTree(budget));
            record.set("result", verdict(observed));
            return observed;
        };
        var strategies = arm == Arm.NO_REFINEMENT ? List.<RefinementStrategy>of() : HypothesisRefinementLoop.defaultStrategies();
        var outcome = new HypothesisRefinementLoop(recorded, strategies, HypothesisRefinementLoop.DEFAULT_MAX_REVISIONS).refine(candidate);
        var history = result.putArray("revisions");
        for (HypothesisRevision revision : outcome.revisionHistory()) {
            var row = history.addObject();
            row.put("id", revision.id()).put("parent", revision.parentId()).put("revision", revision.revisionIndex());
            row.put("left", revision.leftPattern()).put("right", revision.rightPattern());
            row.set("assumptions", JSON.valueToTree(revision.assumptions()));
            row.set("trigger", JSON.valueToTree(revision.triggerEvidence()));
            row.put("strategy", revision.refinementStrategyName()).put("status", revision.status().name());
        }
        var terminal = outcome.terminalRevision();
        var terminalInput = new HypothesisInput(terminal.id(), terminal.leftPattern(), terminal.rightPattern(), terminal.assumptions());
        result.put("unusedChallengeSlots", HypothesisRefinementLoop.DEFAULT_MAX_REVISIONS - records.size());
        result.set("terminalRecheck", verdict(validator.search(terminalInput, CounterexampleBudget.defaultBudget())));
        return result;
    }

    private static ObjectNode verdict(CounterexampleSearchResult result) {
        var value = JSON.createObjectNode();
        value.put("status", result.status().name());
        value.put("explanation", result.explanation());
        value.set("counterexample", JSON.valueToTree(result.counterexample().orElse(null)));
        value.set("sources", JSON.valueToTree(result.attemptedSources()));
        value.set("inferredAssumptions", JSON.valueToTree(result.inferredAssumptions()));
        var assumptions = value.putArray("typedAssumptions");
        for (var inferred : result.typedAssumptions()) {
            ObjectNode entry = JSON.valueToTree(inferred);
            // The validator collects variable names from a set; only membership is evidential.
            entry.set("affectedVariables", JSON.valueToTree(inferred.affectedVariables().stream().sorted().toList()));
            assumptions.add(entry);
        }
        return value;
    }

    private static List<SuccessfulTransformationPath> observations() {
        var paths = new ArrayList<SuccessfulTransformationPath>();
        var scorer = new ExpressionScorer();
        var validation = new SymPyEquivalenceService();
        for (int offset : List.of(1, 3, 5)) {
            int b = 2 * offset, c = offset * offset;
            var examples = List.of(
                List.of("x^2 + " + b + "*x + " + c, "(x + " + offset + ")^2"),
                List.of("x^2 - " + b + "*x + " + c, "(x - " + offset + ")^2"),
                List.of("(x + " + offset + ")*(x - " + offset + ")", "x^2 - " + c),
                List.of("x^2 + " + b + "*x", "(x + " + offset + ")^2 - " + c));
            for (var pair : examples) {
                String left = pair.getFirst(), right = pair.getLast();
                boolean verified = validation.areEquivalent(left, right);
                if (!verified) throw new IllegalStateException("Public polynomial observation failed validation: " + pair);
                paths.add(new SuccessfulTransformationPath(null, left, right, pair, List.of(),
                    scorer.score(left), scorer.score(right), true, validation.evidence(left, right), Map.of("variable", "x")));
            }
        }
        paths.addAll(DiscoveryDemos.rationalSimplificationExamples());
        return List.copyOf(paths);
    }
}
