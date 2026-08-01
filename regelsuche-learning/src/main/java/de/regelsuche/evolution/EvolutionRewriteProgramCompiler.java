package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Choice;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.FirstApplicable;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Node;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Prune;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Prioritize;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.PriorityKind;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Repeat;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Require;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Requirement;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Sequence;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Source;
import de.regelsuche.search.program.ProgrammedTransformationEngine;
import de.regelsuche.search.program.RewriteCandidate;
import de.regelsuche.search.program.RewriteProgram;
import de.regelsuche.search.program.RewriteProgram.NodeMetadata;
import de.regelsuche.search.program.RewritePrograms;
import de.regelsuche.transform.AstRewriteTransformationEngines;
import de.regelsuche.transform.RewriteRule;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Resolves a canonical evolution-side topology against one accepted genome and
 * exposes the result through the ordinary rewrite-program interpreter.
 */
public final class EvolutionRewriteProgramCompiler {
    private final EvolutionGenomeCompiler genomeCompiler;
    private final AstRewriteTransformationEngines.Backend astRewriteBackend;

    public EvolutionRewriteProgramCompiler() {
        this(
            new EvolutionGenomeCompiler(),
            AstRewriteTransformationEngines.productionBackend()
        );
    }

    public EvolutionRewriteProgramCompiler(EvolutionGenomeCompiler genomeCompiler) {
        this(
            genomeCompiler,
            AstRewriteTransformationEngines.productionBackend()
        );
    }

    public EvolutionRewriteProgramCompiler(
        EvolutionGenomeCompiler genomeCompiler,
        AstRewriteTransformationEngines.Backend astRewriteBackend
    ) {
        this.genomeCompiler = Objects.requireNonNull(
            genomeCompiler,
            "genomeCompiler"
        );
        this.astRewriteBackend = Objects.requireNonNull(
            astRewriteBackend,
            "astRewriteBackend"
        );
    }

    public CompiledRewriteProgram compile(
        EvolutionGenome genome,
        EvolutionRewriteProgramPlan plan
    ) {
        Objects.requireNonNull(genome, "genome");
        Objects.requireNonNull(plan, "plan");
        if (!genome.contentHash().equals(plan.genomeHash())) {
            throw new IllegalArgumentException(
                "program plan is bound to a different genome");
        }
        validateProgramBudget(genome, plan);

        EvolutionGenomeCompiler.CompiledProgram compiledGenome =
            genomeCompiler.compile(genome);
        Map<String, RewriteRule> rulesByGeneId = rulesByGeneId(
            genome, compiledGenome);
        for (String geneId : plan.referencedGeneIds()) {
            if (!rulesByGeneId.containsKey(geneId)) {
                throw new IllegalArgumentException(
                    "program source references unknown genome gene: " + geneId);
            }
        }

        CompilationContext context = new CompilationContext(
            genome,
            rulesByGeneId,
            astRewriteBackend);
        RewriteProgram program = compileNode(plan.root(), context);
        ProgrammedTransformationEngine engine =
            new ProgrammedTransformationEngine(program);
        return new CompiledRewriteProgram(
            genome.contentHash(),
            genome.alphaStructuralHash(),
            plan.contentHash(),
            plan.alphaStructuralHash(),
            program,
            engine,
            plan.toReadableProgram(),
            plan.referencedGeneIds());
    }

    private static void validateProgramBudget(
        EvolutionGenome genome,
        EvolutionRewriteProgramPlan plan
    ) {
        EvolutionGenome.ResourceBudget budget = genome.budget();
        if (plan.maxNodes() > budget.maxProgramLength()
                || plan.nodeCount() > budget.maxProgramLength()) {
            throw new IllegalArgumentException(
                "program topology exceeds genome maxProgramLength");
        }
        if (plan.maxDepth() > budget.maxProgramLength()
                || plan.actualDepth() > budget.maxProgramLength()) {
            throw new IllegalArgumentException(
                "program topology exceeds genome depth budget");
        }
        validateNodeBudget(plan.root(), budget);
    }

    private static void validateNodeBudget(
        Node node,
        EvolutionGenome.ResourceBudget budget
    ) {
        if (node instanceof Repeat repeat
                && repeat.maxIterations() > budget.maxApplicationsPerPath()) {
            throw new IllegalArgumentException(
                "repeat exceeds genome maxApplicationsPerPath");
        }
        if (node instanceof Prune prune
                && prune.maxCandidates() > budget.maxCandidatesPerState()) {
            throw new IllegalArgumentException(
                "prune exceeds genome maxCandidatesPerState");
        }
        if (node instanceof Require require
                && require.requirement().kind()
                    == EvolutionRewriteProgramPlan.RequirementKind.MAX_PRIMITIVE_STEPS
                && require.requirement().threshold()
                    > budget.maxApplicationsPerPath()) {
            throw new IllegalArgumentException(
                "primitive-step guard exceeds genome maxApplicationsPerPath");
        }
        for (Node child : children(node)) {
            validateNodeBudget(child, budget);
        }
    }

    private static Map<String, RewriteRule> rulesByGeneId(
        EvolutionGenome genome,
        EvolutionGenomeCompiler.CompiledProgram compiled
    ) {
        if (genome.rewrites().size() != compiled.rules().size()) {
            throw new IllegalStateException(
                "compiled genome rule count does not match gene count");
        }
        LinkedHashMap<String, RewriteRule> result = new LinkedHashMap<>();
        for (int index = 0; index < genome.rewrites().size(); index++) {
            EvolutionGenome.RewriteGene gene = genome.rewrites().get(index);
            RewriteRule rule = compiled.rules().get(index);
            if (!rule.id().endsWith("_" + gene.geneId())) {
                throw new IllegalStateException(
                    "compiled rule identity is not bound to gene " + gene.geneId());
            }
            result.put(gene.geneId(), rule);
        }
        return Map.copyOf(result);
    }

    private static RewriteProgram compileNode(
        Node node,
        CompilationContext context
    ) {
        NodeMetadata metadata = NodeMetadata.named(node.nodeId());
        if (node instanceof Source source) {
            List<RewriteRule> rules = source.geneIds().stream()
                .map(context::rule)
                .toList();
            return new RewriteProgram.Source(
                metadata,
                AstRewriteTransformationEngines.create(
                    context.astRewriteBackend(),
                    rules,
                    context.genome().budget().maxAstGrowthPerStep(),
                    context.genome().budget().maxCandidatesPerState()));
        }
        if (node instanceof Choice choice) {
            return new RewriteProgram.Choice(
                metadata,
                compileChildren(choice.alternatives(), context));
        }
        if (node instanceof FirstApplicable firstApplicable) {
            return new RewriteProgram.FirstApplicable(
                metadata,
                compileChildren(firstApplicable.alternatives(), context));
        }
        if (node instanceof Sequence sequence) {
            return new RewriteProgram.Sequence(
                metadata,
                compileChildren(sequence.steps(), context));
        }
        if (node instanceof Repeat repeat) {
            return new RewriteProgram.Repeat(
                metadata,
                compileNode(repeat.body(), context),
                repeat.minIterations(),
                repeat.maxIterations());
        }
        if (node instanceof Require require) {
            Requirement requirement = require.requirement();
            return new RewriteProgram.Require(
                metadata,
                compileNode(require.body(), context),
                requirementDescription(requirement),
                requirementPredicate(requirement));
        }
        if (node instanceof Prioritize prioritize) {
            return new RewriteProgram.Prioritize(
                metadata,
                compileNode(prioritize.body(), context),
                priorityDescription(prioritize),
                priorityComparator(prioritize, context));
        }
        Prune prune = (Prune) node;
        return new RewriteProgram.Prune(
            metadata,
            compileNode(prune.body(), context),
            prune.maxCandidates(),
            prune.reason());
    }

    private static List<RewriteProgram> compileChildren(
        List<Node> nodes,
        CompilationContext context
    ) {
        return nodes.stream()
            .map(node -> compileNode(node, context))
            .toList();
    }

    private static Predicate<RewriteCandidate> requirementPredicate(
        Requirement requirement
    ) {
        return switch (requirement.kind()) {
            case EQUIVALENCE_PRESERVING_BY_CONSTRUCTION ->
                RewritePrograms.equivalencePreserving();
            case ASSUMPTION_FREE -> candidate -> candidate.steps().stream()
                .allMatch(step -> step.assumptions().isEmpty());
            case MAX_ESTIMATED_COST_DELTA -> candidate ->
                candidate.toTransformation().estimatedCostDelta()
                    <= requirement.threshold();
            case MAX_PRIMITIVE_STEPS -> candidate ->
                candidate.steps().size() <= requirement.threshold();
        };
    }

    private static String requirementDescription(Requirement requirement) {
        return requirement.kind().name() + "(" + requirement.threshold() + ")";
    }

    private static Comparator<RewriteCandidate> priorityComparator(
        Prioritize prioritize,
        CompilationContext context
    ) {
        if (prioritize.priority().kind()
                == PriorityKind.ESTIMATED_COST_THEN_RULE) {
            return RewritePrograms.byEstimatedCostThenRule();
        }
        List<String> ruleIds = prioritize.priority().preferredGeneIds().stream()
            .map(geneId -> context.rule(geneId).id())
            .toList();
        return RewritePrograms.preferRuleOrder(ruleIds);
    }

    private static String priorityDescription(Prioritize prioritize) {
        return prioritize.priority().kind().name()
            + prioritize.priority().preferredGeneIds();
    }

    private static List<Node> children(Node node) {
        if (node instanceof Choice choice) {
            return choice.alternatives();
        }
        if (node instanceof FirstApplicable firstApplicable) {
            return firstApplicable.alternatives();
        }
        if (node instanceof Sequence sequence) {
            return sequence.steps();
        }
        if (node instanceof Repeat repeat) {
            return List.of(repeat.body());
        }
        if (node instanceof Require require) {
            return List.of(require.body());
        }
        if (node instanceof Prioritize prioritize) {
            return List.of(prioritize.body());
        }
        if (node instanceof Prune prune) {
            return List.of(prune.body());
        }
        return List.of();
    }

    public record CompiledRewriteProgram(
        String genomeHash,
        String genomeAlphaStructuralHash,
        String planHash,
        String planAlphaStructuralHash,
        RewriteProgram program,
        ProgrammedTransformationEngine engine,
        String readableProgram,
        List<String> referencedGeneIds
    ) {
        public CompiledRewriteProgram {
            EvolutionGenome.requireSha256(genomeHash, "genomeHash");
            EvolutionGenome.requireSha256(
                genomeAlphaStructuralHash, "genomeAlphaStructuralHash");
            EvolutionGenome.requireSha256(planHash, "planHash");
            EvolutionGenome.requireSha256(
                planAlphaStructuralHash, "planAlphaStructuralHash");
            Objects.requireNonNull(program, "program");
            Objects.requireNonNull(engine, "engine");
            if (readableProgram == null || readableProgram.isBlank()) {
                throw new IllegalArgumentException(
                    "readableProgram must not be blank");
            }
            referencedGeneIds = List.copyOf(referencedGeneIds);
        }
    }

    private record CompilationContext(
        EvolutionGenome genome,
        Map<String, RewriteRule> rulesByGeneId,
        AstRewriteTransformationEngines.Backend astRewriteBackend
    ) {
        private CompilationContext {
            Objects.requireNonNull(genome, "genome");
            rulesByGeneId = Map.copyOf(rulesByGeneId);
            Objects.requireNonNull(astRewriteBackend, "astRewriteBackend");
        }

        private RewriteRule rule(String geneId) {
            RewriteRule rule = rulesByGeneId.get(geneId);
            if (rule == null) {
                throw new IllegalArgumentException(
                    "unknown genome gene: " + geneId);
            }
            return rule;
        }
    }
}
