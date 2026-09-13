package de.regelsuche.evolution;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.equivalence.AssumptionAwareEquivalenceService;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.RevealCase;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainFitnessEvidence.PathCorrectness;
import de.regelsuche.evolution.EvolutionRewriteProgramValidationEvidence.Measurement;
import de.regelsuche.evolution.EvolutionRewriteProgramValidationEvidence.PairedCase;
import de.regelsuche.evolution.EvolutionRewriteProgramValidationPlan.Configuration;
import de.regelsuche.math.algorithms.equivalence.RationalFunctionNormalFormEquivalencePortAdapter;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.strategy.SearchExpansionSource;
import de.regelsuche.search.strategy.SearchWorkMetrics;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Budget;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Result;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.MeasuredTransformationEngine;
import de.regelsuche.transform.MeasuredTransformationEngines;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Current production compiler/search/audit adapters; no caller-supplied result acceptor. */
final class NativeEvolutionRewriteProgramValidationEvaluator {
    private final AssumptionAwareEquivalenceService equivalence;

    NativeEvolutionRewriteProgramValidationEvaluator() {
        this(new RationalFunctionNormalFormEquivalencePortAdapter());
    }

    NativeEvolutionRewriteProgramValidationEvaluator(AssumptionAwareEquivalenceService equivalence) {
        this.equivalence = Objects.requireNonNull(equivalence, "equivalence");
    }

    List<PairedCase> evaluate(Configuration configuration, List<RevealCase> cases) {
        var candidate = configuration.candidate();
        var genome = candidate.genome();
        Budget budget = configuration.effectiveBudget();
        MeasuredTransformationEngine baseline;
        MeasuredTransformationEngine selected;
        try {
            var flat = new EvolutionGenomeCompiler().compile(genome);
            var program = new EvolutionRewriteProgramCompiler().compile(genome, candidate.plan());
            var ordinary = MeasuredTransformationEngines.counting(new AstRewriteTransformationEngine(
                AstRewriteTransformationEngine.defaultRules(), genome.budget().maxAstGrowthPerStep(),
                budget.maxCandidatesPerState()));
            var rules = MeasuredTransformationEngines.counting(new AstRewriteTransformationEngine(
                flat.rules(), genome.budget().maxAstGrowthPerStep(), budget.maxCandidatesPerState()));
            baseline = MeasuredTransformationEngines.union(ordinary, rules);
            selected = MeasuredTransformationEngines.union(ordinary, rules, program.engine());
        } catch (RuntimeException exception) {
            String reason = "COMPILATION_FAILED:" + exception.getClass().getSimpleName();
            return cases.stream().map(item -> new PairedCase(item.caseId(), item.familyId(),
                Measurement.unavailable(reason), Measurement.unavailable(reason))).toList();
        }
        return cases.stream().map(item -> new PairedCase(item.caseId(), item.familyId(),
            evaluateSide(baseline, item.inputExpression(), item.targetExpression(), item.assumptions(), budget),
            evaluateSide(selected, item.inputExpression(), item.targetExpression(), item.assumptions(), budget))).toList();
    }

    Measurement evaluateSide(MeasuredTransformationEngine engine, String inputExpression, String targetExpression,
        List<String> assumptions, Budget budget) {
        Result result;
        try {
            result = new WorkBudgetBestFirstSearchStrategy().search(
                new WorkBudgetBestFirstSearchStrategy.Problem(inputExpression, targetExpression,
                    new SearchExpansionSource.Measured(engine), new ExpressionScorer(),
                    new ExpressionCanonicalizer(), budget));
        } catch (RuntimeException exception) {
            return Measurement.unavailable("SEARCH_FAILED:" + exception.getClass().getSimpleName());
        }
        var work = result.metrics();
        var searchWork = new SearchWorkMetrics(work.exploredStates(), work.expandedStates(),
            work.generatedTransformations(), work.enqueuedStates(), work.duplicatePrunes(),
            work.repeatedApplicationPrunes(), work.sameExpressionPrunes(), work.expansionBudgetPrunes(),
            work.primitiveBudgetPrunes(), work.candidateBudgetPrunes(), work.statesWithoutTransformations(),
            work.engineBatches());
        var reached = result.reachedState();
        var path = reached == null ? List.<String>of() : reached.path();
        PathCorrectness correctness = PathCorrectness.NOT_EVALUATED;
        long auditCalls = 0;
        String failure = "";
        try {
            if (result.reached()) {
                Set<String> declared = Set.copyOf(AssumptionSignature.ofExpressions(assumptions)
                    .normalizedAssumptions());
                correctness = declared.containsAll(reached.assumptions())
                    ? PathCorrectness.CONFIRMED : PathCorrectness.MISSING_ASSUMPTION;
                for (int index = 0; correctness == PathCorrectness.CONFIRMED && index + 1 < path.size(); index++) {
                    auditCalls++;
                    correctness = PathCorrectness.valueOf(equivalence.evaluate(
                        path.get(index), path.get(index + 1), assumptions).status().name());
                }
            }
        } catch (RuntimeException exception) {
            failure = "AUDIT_FAILED:" + exception.getClass().getSimpleName();
            correctness = PathCorrectness.NOT_EVALUATED;
        }
        if (failure.isEmpty() && (result.status() == WorkBudgetBestFirstSearchStrategy.Status.INCOMPLETE_EXPANSION
                || result.expansions().stream().anyMatch(itemExpansion -> !itemExpansion.execution().complete()))) {
            failure = "INCOMPLETE_EXPANSION";
        }
        if (failure.isEmpty() && EvolutionRewriteProgramValidationEvidence.sum(
                searchWork.totalWorkUnits(), work.transformationWork().totalWorkUnits())
                != work.chargedSearchWorkUnits()) {
            failure = "UNREPRESENTED_SEARCH_WORK";
        }
        Long total = failure.isEmpty() ? Long.valueOf(EvolutionRewriteProgramValidationEvidence.sum(
            searchWork.totalWorkUnits(), work.transformationWork().totalWorkUnits(), auditCalls)) : null;
        return new Measurement(result.status().name(), result.reached(),
            reached == null ? -1 : reached.edgeDepth(), reached == null ? 0 : reached.primitiveDepth(),
            path, reached != null && reached.programUsed(), correctness, searchWork,
            work.transformationWork(), auditCalls, total, failure);
    }
}
