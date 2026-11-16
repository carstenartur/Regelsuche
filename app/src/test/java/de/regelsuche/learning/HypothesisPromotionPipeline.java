package de.regelsuche.learning;

import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.mining.HypothesisCandidate;
import de.regelsuche.mining.HypothesisRankingStrategy;
import de.regelsuche.mining.HypothesisRefinementLoop;
import de.regelsuche.mining.HypothesisRevision;
import de.regelsuche.mining.InterestingnessScore;
import de.regelsuche.mining.InterestingnessRankingStrategy;
import de.regelsuche.mining.AssumptionMinimizer;
import de.regelsuche.mining.HypothesisRepository;
import de.regelsuche.mining.RefinementStrategy;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.mining.RuleCandidateMiner;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.mining.SuccessfulTransformationPath;
import de.regelsuche.mining.SymbolicRegressionHypothesisSource;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.CounterexampleSearchService;
import de.regelsuche.validation.OracleValidator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Orchestrates the full hypothesis promotion pipeline:
 *
 * <pre>
 * Path-Mining (RuleCandidateMiner)
 *   → Generalisation (PatternGeneralizer / anti-unification)
 *   → HypothesisCandidate (stored in HypothesisRepository)
 *   → Counterexample Search + Refinement Loop (HypothesisRefinementLoop)
 *   → optional Proof (EquivalenceService via CandidateValidator)
 *   → Promotion → ReusableRule (MacroRuleLearningService / RuleInventoryRepository)
 * </pre>
 *
 * <p>Each step is optional: pass a no-op counterexample service if you don't
 * need that check, or set {@code autoPromote = false} to stop before inventory
 * insertion.</p>
 *
 * <p>When a counterexample is found, the pipeline hands off to a
 * {@link HypothesisRefinementLoop} which attempts to refine the hypothesis
 * (e.g. by adding constraints) until it either survives a fresh challenge
 * ({@code VALIDATED_WITHIN_BUDGET}), exhausts the revision budget
 * ({@code REJECTED}), or ends inconclusively ({@code INCONCLUSIVE}).
 * The full revision history is available in the {@link PromotionResult}.</p>
 */
public class HypothesisPromotionPipeline {

    private final RuleCandidateMiner miner;
    private final HypothesisRepository hypothesisRepository;
    private final CounterexampleSearchService counterexampleService;
    private final MacroRuleLearningService learningService;
    private final boolean autoPromote;
    private final List<SymbolicRegressionHypothesisSource> symbolicRegressionSources;
    private final HypothesisRankingStrategy rankingStrategy;
    private final OracleValidator oracleValidator;
    private final HypothesisRefinementLoop refinementLoop;

    /**
     * Full-featured constructor.
     *
     * @param miner                  mines rule candidates from transformation paths
     * @param hypothesisRepository   stores candidates as pending hypotheses
     * @param counterexampleService  searches for counterexamples; may be a no-op
     * @param learningService        handles confidence accumulation and inventory updates
     * @param autoPromote            if {@code true}, confirmed hypotheses are automatically
     *                               promoted to the inventory; otherwise they stay as
     *                               {@link HypothesisCandidate} records for manual review
     */
    public HypothesisPromotionPipeline(
        RuleCandidateMiner miner,
        HypothesisRepository hypothesisRepository,
        CounterexampleSearchService counterexampleService,
        MacroRuleLearningService learningService,
        boolean autoPromote
    ) {
        this(miner, hypothesisRepository, counterexampleService, learningService, autoPromote, List.of());
    }

    public HypothesisPromotionPipeline(
        RuleCandidateMiner miner,
        HypothesisRepository hypothesisRepository,
        CounterexampleSearchService counterexampleService,
        MacroRuleLearningService learningService,
        boolean autoPromote,
        List<SymbolicRegressionHypothesisSource> symbolicRegressionSources
    ) {
        this(miner, hypothesisRepository, counterexampleService, learningService, autoPromote,
            symbolicRegressionSources, new InterestingnessRankingStrategy());
    }

    public HypothesisPromotionPipeline(
        RuleCandidateMiner miner,
        HypothesisRepository hypothesisRepository,
        CounterexampleSearchService counterexampleService,
        MacroRuleLearningService learningService,
        boolean autoPromote,
        List<SymbolicRegressionHypothesisSource> symbolicRegressionSources,
        HypothesisRankingStrategy rankingStrategy
    ) {
        this(miner, hypothesisRepository, counterexampleService, learningService, autoPromote,
            symbolicRegressionSources, rankingStrategy,
            (leftExpression, rightExpression) -> OracleValidator.OracleValidation.unavailable("no oracle configured"));
    }

    public HypothesisPromotionPipeline(
        RuleCandidateMiner miner,
        HypothesisRepository hypothesisRepository,
        CounterexampleSearchService counterexampleService,
        MacroRuleLearningService learningService,
        boolean autoPromote,
        List<SymbolicRegressionHypothesisSource> symbolicRegressionSources,
        HypothesisRankingStrategy rankingStrategy,
        OracleValidator oracleValidator
    ) {
        this(miner, hypothesisRepository, counterexampleService, learningService, autoPromote,
            symbolicRegressionSources, rankingStrategy, oracleValidator, null);
    }

    HypothesisPromotionPipeline(
        RuleCandidateMiner miner,
        HypothesisRepository hypothesisRepository,
        CounterexampleSearchService counterexampleService,
        MacroRuleLearningService learningService,
        boolean autoPromote,
        List<SymbolicRegressionHypothesisSource> symbolicRegressionSources,
        HypothesisRankingStrategy rankingStrategy,
        OracleValidator oracleValidator,
        HypothesisRefinementLoop refinementLoop
    ) {
        this.miner = miner;
        this.hypothesisRepository = hypothesisRepository;
        this.counterexampleService = counterexampleService;
        this.learningService = learningService;
        this.autoPromote = autoPromote;
        this.symbolicRegressionSources = symbolicRegressionSources == null
            ? List.of()
            : List.copyOf(symbolicRegressionSources);
        this.rankingStrategy = rankingStrategy == null ? new InterestingnessRankingStrategy() : rankingStrategy;
        this.oracleValidator = oracleValidator == null
            ? (leftExpression, rightExpression) -> OracleValidator.OracleValidation.unavailable("no oracle configured")
            : oracleValidator;
        this.refinementLoop = refinementLoop == null
            ? new HypothesisRefinementLoop(counterexampleService)
            : refinementLoop;
    }

    /**
     * Run the pipeline for the given transformation paths.
     *
     * @return {@link PromotionResult} summarising newly created hypotheses and
     *         promoted rules.
     */
    public PromotionResult run(List<SuccessfulTransformationPath> paths) {
        if (paths == null || paths.isEmpty()) {
            return new PromotionResult(List.of(), List.of());
        }

        // Step 1: Mine candidates via anti-unification.
        List<RuleCandidate> candidates = miner.mine(paths);

        List<HypothesisCandidate> newHypotheses = new ArrayList<>();
        List<ReusableRule> promotedRules = new ArrayList<>();
        List<HypothesisRevision> allRevisions = new ArrayList<>();
        // Collect supporting paths of non-rejected candidates for the promotion step,
        // so rejected rules cannot be re-mined by learningService.learn().
        // Track assumptions per path-id so each path only receives the assumptions
        // inferred from the specific candidate(s) it supports — not assumptions from
        // unrelated candidates that the path never contributed to.
        Map<String, List<String>> pathAssumptions = new LinkedHashMap<>();
        boolean anyValidatedForPromotion = false;

        Map<String, Set<String>> domainsByPath = domainsByPath(paths);
        List<ScoredCandidate> prioritizedCandidates = score(candidates, paths, domainsByPath);

        for (ScoredCandidate scoredCandidate : prioritizedCandidates) {
            RuleCandidate candidate = scoredCandidate.candidate();
            HypothesisCandidate hypothesis = scoredCandidate.hypothesis();

            // Step 3: Counterexample search with refinement loop.
            HypothesisRefinementLoop.RefinementOutcome outcome = refinementLoop.refine(hypothesis);
            allRevisions.addAll(outcome.revisionHistory());

            CounterexampleSearchService.CounterexampleSearchResult counterexampleResult =
                outcome.lastSearchResult();

            hypothesis = applyTerminalRevision(hypothesis, outcome.terminalRevision(), counterexampleResult);

            if (outcome.isRejected()) {
                hypothesis = hypothesis.withProofStatus(CandidateProofStatus.REJECTED);
                // Persist with REJECTED status so downstream readers see the correct state.
                hypothesisRepository.save(hypothesis.id(), hypothesis);
                newHypotheses.add(hypothesis);
                continue;
            }
            if (outcome.isInconclusive()) {
                hypothesis = hypothesis.withProofStatus(CandidateProofStatus.OBSERVED);
            }
            if (hypothesis.proofStatus().atLeast(CandidateProofStatus.VALIDATED_BY_EXAMPLES)
                && oracleValidator.validateEquivalence(hypothesis.leftPattern(), hypothesis.rightPattern()).status()
                == OracleValidator.OracleValidationStatus.DISAGREE) {
                hypothesis = hypothesis.withProofStatus(CandidateProofStatus.OBSERVED);
            }

            // Store as a pending hypothesis.
            hypothesisRepository.save(hypothesis.id(), hypothesis);
            newHypotheses.add(hypothesis);

            // Track which paths back this validated candidate for the promotion step.
            if (autoPromote
                && outcome.isAccepted()
                && hypothesis.proofStatus().ordinal() >= CandidateProofStatus.VALIDATED_BY_EXAMPLES.ordinal()) {
                List<String> candidateAssumptions = hypothesis.assumptions();
                for (String pathId : candidate.supportingTransformationIds()) {
                    pathAssumptions.computeIfAbsent(pathId, k -> new ArrayList<>())
                        .addAll(candidateAssumptions);
                }

                anyValidatedForPromotion = true;
            }
        }

        for (SymbolicRegressionHypothesisSource source : symbolicRegressionSources) {
            if (!source.enabled()) {
                continue;
            }
            for (HypothesisCandidate proposal : source.propose(paths)) {
                HypothesisCandidate hypothesis = proposal.withNoveltyScore(
                    normalized(InterestingnessScore.from(proposal, 0.0, domainsFor(proposal, domainsByPath)).total())
                );
                HypothesisRefinementLoop.RefinementOutcome outcome = refinementLoop.refine(hypothesis);
                allRevisions.addAll(outcome.revisionHistory());
                CounterexampleSearchService.CounterexampleSearchResult counterexampleResult =
                    outcome.lastSearchResult();
                hypothesis = applyTerminalRevision(hypothesis, outcome.terminalRevision(), counterexampleResult);
                if (outcome.isRejected()) {
                    hypothesis = hypothesis.withProofStatus(CandidateProofStatus.REJECTED);
                } else if (outcome.isInconclusive()) {
                    hypothesis = hypothesis.withProofStatus(CandidateProofStatus.OBSERVED);
                }
                hypothesisRepository.save(hypothesis.id(), hypothesis);
                newHypotheses.add(hypothesis);
            }
        }

        // Step 4: Auto-promote validated hypotheses — called once, using only the
        // supporting paths of candidates that passed the counterexample check, so
        // rejected rules cannot be inadvertently re-mined and activated.
        if (anyValidatedForPromotion) {
            List<SuccessfulTransformationPath> validatedPaths = paths.stream()
                .filter(p -> pathAssumptions.containsKey(p.id()))
                .map(p -> p.withAssumptions(pathAssumptions.get(p.id())))
                .toList();
            if (!validatedPaths.isEmpty()) {
                MacroLearningResult result = learningService.learn(validatedPaths);
                promotedRules.addAll(result.newlyActivated());
            }
        }

        return new PromotionResult(newHypotheses, promotedRules, List.copyOf(allRevisions));
    }

    private HypothesisCandidate applyTerminalRevision(
        HypothesisCandidate hypothesis,
        HypothesisRevision terminal,
        CounterexampleSearchService.CounterexampleSearchResult counterexampleResult
    ) {
        HypothesisCandidate updated = hypothesis.withCounterexampleResult(counterexampleResult);
        if (!terminal.leftPattern().equals(updated.leftPattern())
            || !terminal.rightPattern().equals(updated.rightPattern())) {
            updated = updated.withPatterns(terminal.leftPattern(), terminal.rightPattern());
        }
        List<String> mergedAssumptions = new ArrayList<>(terminal.assumptions());
        counterexampleResult.inferredAssumptions().stream()
            .filter(assumption -> !mergedAssumptions.contains(assumption))
            .forEach(mergedAssumptions::add);
        if (!mergedAssumptions.equals(updated.assumptions())) {
            updated = updated.withAssumptions(mergedAssumptions);
        }
        if (counterexampleResult.status() == CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND) {
            List<String> minimizableAssumptions = updated.assumptions().stream()
                .filter(HypothesisPromotionPipeline::isMinimizableAssumption)
                .toList();
            if (minimizableAssumptions.size() > 1) {
                List<String> preservedAssumptions = updated.assumptions().stream()
                    .filter(assumption -> !isMinimizableAssumption(assumption))
                    .toList();
                HypothesisCandidate minimized = AssumptionMinimizer.analyze(
                    updated.withAssumptions(minimizableAssumptions),
                    challengedCandidate -> counterexampleService.search(
                new CounterexampleSearchService.HypothesisInput(
                    challengedCandidate.id(),
                    challengedCandidate.leftPattern(),
                    challengedCandidate.rightPattern(),
                    challengedCandidate.assumptions()
                ),
                CounterexampleSearchService.CounterexampleBudget.defaultBudget()
                    )).minimizedCandidate();
                List<String> finalAssumptions = new ArrayList<>(preservedAssumptions);
                finalAssumptions.addAll(minimized.assumptions());
                updated = updated.withAssumptions(finalAssumptions);
            }
        }
        return updated;
    }

    private static boolean isMinimizableAssumption(String assumption) {
        if (assumption == null) {
            return false;
        }
        return assumption.contains(" != 0")
            || assumption.contains(" > 0")
            || assumption.contains(" >= 0")
            || assumption.contains(" ∈ ");
    }

    /**
     * Result of one pipeline run.
     *
     * @param newHypotheses  newly created {@link HypothesisCandidate} records
     * @param promotedRules  rules that were promoted to the inventory in this run
     * @param revisionHistory all {@link HypothesisRevision} records created during
     *                        the counterexample-guided refinement loop
     */
    public record PromotionResult(
        List<HypothesisCandidate> newHypotheses,
        List<ReusableRule> promotedRules,
        List<HypothesisRevision> revisionHistory
    ) {
        public PromotionResult(List<HypothesisCandidate> newHypotheses, List<ReusableRule> promotedRules) {
            this(newHypotheses, promotedRules, List.of());
        }

        public PromotionResult {
            newHypotheses = List.copyOf(newHypotheses);
            promotedRules = List.copyOf(promotedRules);
            revisionHistory = revisionHistory == null ? List.of() : List.copyOf(revisionHistory);
        }
    }

    private List<ScoredCandidate> score(
        List<RuleCandidate> candidates,
        List<SuccessfulTransformationPath> paths,
        Map<String, Set<String>> domainsByPath
    ) {
        Map<String, RuleCandidate> candidatesByHypothesisId = new LinkedHashMap<>();
        Map<String, Double> similarities = new LinkedHashMap<>();
        Map<String, Set<String>> domains = new LinkedHashMap<>();
        List<HypothesisCandidate> hypotheses = new ArrayList<>();
        for (RuleCandidate candidate : candidates) {
            HypothesisCandidate base = HypothesisCandidate.from(candidate, 0.0, paths);
            candidatesByHypothesisId.put(base.id(), candidate);
            similarities.put(base.id(), candidate.status() == RuleStatus.NEW ? 0.0 : 1.0);
            domains.put(base.id(), domainsFor(base, domainsByPath));
            hypotheses.add(base);
        }
        return rankingStrategy.rank(hypotheses, similarities, domains).stream()
            .map(ranked -> new ScoredCandidate(
                candidatesByHypothesisId.get(ranked.hypothesis().id()),
                ranked.hypothesis().withNoveltyScore(normalized(ranked.score().total())),
                ranked.score()
            ))
            .toList();
    }

    private static double normalized(double score) {
        return Math.max(0.0, Math.min(1.0, score / 10.0));
    }

    private static Map<String, Set<String>> domainsByPath(List<SuccessfulTransformationPath> paths) {
        return paths.stream().collect(Collectors.toMap(
            SuccessfulTransformationPath::id,
            HypothesisPromotionPipeline::domainsOf,
            (left, right) -> {
                java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(left);
                merged.addAll(right);
                return Set.copyOf(merged);
            },
            LinkedHashMap::new
        ));
    }

    private static Set<String> domainsFor(HypothesisCandidate candidate, Map<String, Set<String>> domainsByPath) {
        return candidate.supportingPaths().stream()
            .flatMap(path -> domainsByPath.getOrDefault(path, Set.of()).stream())
            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static Set<String> domainsOf(SuccessfulTransformationPath path) {
        java.util.LinkedHashSet<String> domains = new java.util.LinkedHashSet<>();
        String domain = path.variableStructure().get("domain");
        if (domain != null && !domain.isBlank()) {
            domains.add(domain);
        }
        String category = path.variableStructure().get("category");
        if (category != null && !category.isBlank()) {
            domains.add(category);
        }
        return Set.copyOf(domains);
    }

    private record ScoredCandidate(
        RuleCandidate candidate,
        HypothesisCandidate hypothesis,
        InterestingnessScore score
    ) implements Comparable<ScoredCandidate> {
        @Override
        public int compareTo(ScoredCandidate other) {
            return score.compareTo(other.score);
        }
    }
}
