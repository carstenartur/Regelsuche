package de.regelsuche.learning;

import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.mining.HypothesisCandidate;
import de.regelsuche.mining.HypothesisRepository;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.mining.RuleCandidateMiner;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.mining.SuccessfulTransformationPath;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.CounterexampleSearchService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Orchestrates the full hypothesis promotion pipeline:
 *
 * <pre>
 * Path-Mining (RuleCandidateMiner)
 *   → Generalisation (PatternGeneralizer / anti-unification)
 *   → HypothesisCandidate (stored in HypothesisRepository)
 *   → Counterexample Search (CounterexampleSearchService)
 *   → optional Proof (EquivalenceService via CandidateValidator)
 *   → Promotion → ReusableRule (MacroRuleLearningService / RuleInventoryRepository)
 * </pre>
 *
 * <p>Each step is optional: pass a no-op counterexample service if you don't
 * need that check, or set {@code autoPromote = false} to stop before inventory
 * insertion.</p>
 */
public class HypothesisPromotionPipeline {

    private final RuleCandidateMiner miner;
    private final HypothesisRepository hypothesisRepository;
    private final CounterexampleSearchService counterexampleService;
    private final MacroRuleLearningService learningService;
    private final boolean autoPromote;

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
        this.miner = miner;
        this.hypothesisRepository = hypothesisRepository;
        this.counterexampleService = counterexampleService;
        this.learningService = learningService;
        this.autoPromote = autoPromote;
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
        // Collect supporting paths of non-rejected candidates for the promotion step,
        // so rejected rules cannot be re-mined by learningService.learn().
        Set<String> validatedSupportingIds = new LinkedHashSet<>();
        boolean anyValidatedForPromotion = false;

        for (RuleCandidate candidate : candidates) {
            // Step 2: Compute novelty score (simple heuristic: NEW rules score 1.0,
            // known rules score 0.2).
            double novelty = candidate.status() == RuleStatus.NEW ? 1.0 : 0.2;
            HypothesisCandidate hypothesis = HypothesisCandidate.from(candidate, novelty);

            // Step 3: Counterexample search.
            Optional<CounterexampleSearchService.Counterexample> counterexample =
                counterexampleService.search(candidate.leftPattern(), candidate.rightPattern());

            if (counterexample.isPresent()) {
                hypothesis = hypothesis
                    .withCounterexampleStatus(true)
                    .withProofStatus(CandidateProofStatus.REJECTED);
                // Persist with REJECTED status so downstream readers see the correct state.
                RuleCandidate rejectedCandidate = new RuleCandidate(
                    candidate.leftPattern(), candidate.rightPattern(),
                    candidate.examplesCount(), candidate.averageScoreImprovement(),
                    candidate.maximumScoreImprovement(), candidate.equivalenceVerified(),
                    candidate.generalizationPlausible(), candidate.containsFreeParameters(),
                    candidate.parameterRelations(), candidate.status(),
                    CandidateProofStatus.REJECTED, candidate.canonicalHash(),
                    candidate.supportingTransformationIds()
                );
                hypothesisRepository.save(hypothesis.id(), rejectedCandidate);
                newHypotheses.add(hypothesis);
                continue;
            }

            hypothesis = hypothesis.withCounterexampleStatus(false);

            // Store as a pending hypothesis.
            hypothesisRepository.save(hypothesis.id(), candidate);
            newHypotheses.add(hypothesis);

            // Track which paths back this validated candidate for the promotion step.
            if (autoPromote && candidate.proofStatus().ordinal()
                >= CandidateProofStatus.VALIDATED_BY_EXAMPLES.ordinal()) {
                validatedSupportingIds.addAll(candidate.supportingTransformationIds());
                anyValidatedForPromotion = true;
            }
        }

        // Step 4: Auto-promote validated hypotheses — called once, using only the
        // supporting paths of candidates that passed the counterexample check, so
        // rejected rules cannot be inadvertently re-mined and activated.
        if (anyValidatedForPromotion) {
            List<SuccessfulTransformationPath> validatedPaths = paths.stream()
                .filter(p -> validatedSupportingIds.contains(p.id()))
                .toList();
            if (!validatedPaths.isEmpty()) {
                MacroLearningResult result = learningService.learn(validatedPaths);
                promotedRules.addAll(result.newlyActivated());
            }
        }

        return new PromotionResult(newHypotheses, promotedRules);
    }

    /**
     * Result of one pipeline run.
     *
     * @param newHypotheses  newly created {@link HypothesisCandidate} records
     * @param promotedRules  rules that were promoted to the inventory in this run
     */
    public record PromotionResult(
        List<HypothesisCandidate> newHypotheses,
        List<ReusableRule> promotedRules
    ) {
        public PromotionResult {
            newHypotheses = List.copyOf(newHypotheses);
            promotedRules = List.copyOf(promotedRules);
        }
    }
}
