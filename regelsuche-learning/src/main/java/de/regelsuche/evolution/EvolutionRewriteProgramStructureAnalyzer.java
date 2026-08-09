package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Choice;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.FirstApplicable;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Node;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Prioritize;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Prune;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Repeat;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Require;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Sequence;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Source;
import java.util.List;
import java.util.Objects;

/**
 * Computes representation-level facts for an executable rewrite-program plan.
 *
 * <p>The facts are descriptive only: they do not assign fitness, freeze a
 * candidate, prove a mathematical statement or inspect VALIDATION/FINAL TEST
 * evidence. Keeping the analysis generic lets TRAIN diagnostics and showcase
 * selection use the same structural semantics without hard-coding a showcase
 * acceptance predicate into the population engine.</p>
 */
public final class EvolutionRewriteProgramStructureAnalyzer {
    private EvolutionRewriteProgramStructureAnalyzer() {
    }

    public static ProgramFacts analyze(EvolutionRewriteProgramPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return analyze(plan.root());
    }

    public static ProgramFacts analyze(Node node) {
        Objects.requireNonNull(node, "node");
        if (node instanceof Source) {
            return new ProgramFacts(1, false, false, 1);
        }
        if (node instanceof Sequence sequence) {
            List<ProgramFacts> children = sequence.steps().stream()
                .map(EvolutionRewriteProgramStructureAnalyzer::analyze)
                .toList();
            return new ProgramFacts(
                onePlusNodeCounts(children),
                true,
                children.stream().anyMatch(
                    ProgramFacts::containsDecisionTopology),
                children.stream()
                    .mapToInt(
                        ProgramFacts::minimumStructuralPrimitivePathSteps)
                    .reduce(0, EvolutionRewriteProgramStructureAnalyzer::safeAdd));
        }
        if (node instanceof Repeat repeat) {
            ProgramFacts child = analyze(repeat.body());
            return new ProgramFacts(
                safeAdd(1, child.nodeCount()),
                true,
                child.containsDecisionTopology(),
                safeMultiply(
                    repeat.minIterations(),
                    child.minimumStructuralPrimitivePathSteps()));
        }
        if (node instanceof Choice choice) {
            return decisionAlternatives(choice.alternatives());
        }
        if (node instanceof FirstApplicable firstApplicable) {
            return decisionAlternatives(firstApplicable.alternatives());
        }
        if (node instanceof Require require) {
            return decisionWrapper(analyze(require.body()));
        }
        if (node instanceof Prioritize prioritize) {
            return decisionWrapper(analyze(prioritize.body()));
        }
        if (node instanceof Prune prune) {
            return decisionWrapper(analyze(prune.body()));
        }
        throw new IllegalArgumentException(
            "unsupported rewrite-program node: " + node.getClass().getName());
    }

    private static ProgramFacts decisionAlternatives(List<Node> alternatives) {
        List<ProgramFacts> children = alternatives.stream()
            .map(EvolutionRewriteProgramStructureAnalyzer::analyze)
            .toList();
        return new ProgramFacts(
            onePlusNodeCounts(children),
            children.stream().anyMatch(
                ProgramFacts::containsCompositionTopology),
            true,
            children.stream()
                .mapToInt(
                    ProgramFacts::minimumStructuralPrimitivePathSteps)
                .min()
                .orElseThrow());
    }

    private static ProgramFacts decisionWrapper(ProgramFacts child) {
        return new ProgramFacts(
            safeAdd(1, child.nodeCount()),
            child.containsCompositionTopology(),
            true,
            child.minimumStructuralPrimitivePathSteps());
    }

    private static int onePlusNodeCounts(List<ProgramFacts> children) {
        int result = 1;
        for (ProgramFacts child : children) {
            result = safeAdd(result, child.nodeCount());
        }
        return result;
    }

    private static int safeAdd(int left, int right) {
        long result = (long) left + right;
        return result > Integer.MAX_VALUE
            ? Integer.MAX_VALUE
            : Math.toIntExact(result);
    }

    private static int safeMultiply(int left, int right) {
        long result = (long) left * right;
        return result > Integer.MAX_VALUE
            ? Integer.MAX_VALUE
            : Math.toIntExact(result);
    }

    public record ProgramFacts(
        int nodeCount,
        boolean containsCompositionTopology,
        boolean containsDecisionTopology,
        int minimumStructuralPrimitivePathSteps
    ) {
        public ProgramFacts {
            if (nodeCount < 1 || minimumStructuralPrimitivePathSteps < 1) {
                throw new IllegalArgumentException(
                    "program facts require positive structural counters");
            }
        }
    }
}
