package de.regelsuche.evolution;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.evolution.StructuralConstructionPrograms.PreparedResidual;
import de.regelsuche.evolution.StructuralConstructionPrograms.Work;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialResidualComposer.Effect;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.program.BudgetedTransformationSource;
import de.regelsuche.search.program.RewriteProgram;
import de.regelsuche.solver.ir.SolverIr;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Compiles only completely re-derived, source-local, four-component residual compositions. */
public final class ResidualPlanCompiler {
    static final String CHECKER = "residual-composition/v1";
    static final String REVISION = SolverIr.sha256(CHECKER + "|source-effects|exact-cover|sum-zero|exact-equality|four-prepared-components");
    private static final List<String> HOLES = List.of("left-pair", "left-sign", "right-pair", "right-sign");
    private static final List<SchematicProofPlan.ObligationKind> CHECKS = List.of(
        SchematicProofPlan.ObligationKind.DISJOINT_OCCURRENCES, SchematicProofPlan.ObligationKind.COMPLETE_SOURCE_COVER,
        SchematicProofPlan.ObligationKind.RESIDUAL_SUM_IS_ZERO, SchematicProofPlan.ObligationKind.EQUIVALENT);

    private ResidualPlanCompiler() { }

    public record Bound(String source, SchematicProofPlan plan, SchematicProofPlanResolution resolution,
                        List<String> effects, String expression, List<String> primitiveRules, List<String> applicationKeys,
                        List<String> assumptions, String proofRoot, long checkWork) {
        public Bound {
            effects = List.copyOf(effects);
            primitiveRules = List.copyOf(primitiveRules);
            applicationKeys = List.copyOf(applicationKeys);
            assumptions = List.copyOf(assumptions);
        }
    }

    public static Bound bind(String source, List<String> selected, long workLimit) {
        source = source.strip().replaceAll("\\s+", " ");
        if (source.isEmpty()) throw new IllegalArgumentException("source must contain an expression");
        var ids = List.copyOf(selected);
        if (ids.size() != 2 || ids.getFirst().equals(ids.getLast())) {
            throw new IllegalArgumentException("two distinct source effect IDs required");
        }
        var work = new Work(workLimit);
        try {
            work.add(source.length() + 1L);
            var prepared = StructuralConstructionPrograms.prepareResidual(source, work, true);
            if (prepared == null || prepared.parts().size() != 4) throw new IllegalArgumentException("four source components required");
            var effects = prepared.effects().stream().filter(e -> ids.contains(e.id())).toList();
            if (effects.size() != 2) throw new IllegalArgumentException("unknown source effect");
            return discharge(source, prepared, effects, work);
        } catch (StructuralConstructionPrograms.Limit exhausted) {
            throw new IllegalArgumentException("residual plan check exhausted its work limit", exhausted);
        }
    }

    private static Bound discharge(String source, PreparedResidual prepared, List<Effect> effects, Work work) {
        var results = prepared.composer().compose(prepared.expression(), prepared.parts(), effects, 2, 1);
        if (results.size() != 1) throw new IllegalArgumentException("source effects do not cover and cancel");
        var composition = results.getFirst();
        ExactPolynomialAnalysis.withProductWork(work::add).requireEquivalent(source, composition.candidateExpression());
        var c = ResidualPlanController.checkedCandidate(source, prepared.expression(), composition);
        var plan = plan(source, prepared.expression());
        var bindings = bindings(prepared, composition.effects(), c.root(), work);
        var outcomes = plan.obligations().stream().map(o -> new SchematicProofPlanResolution.ObligationOutcome(
            o.id(), SchematicProofPlanResolution.OutcomeStatus.CONFIRMED, CHECKER, REVISION,
            SolverIr.sha256(c.root() + "|" + o.id()), "SOURCE_REPLAY_CONFIRMED")).toList();
        var resolution = SchematicProofPlanResolution.create(plan, bindings, outcomes);
        return new Bound(source, plan, resolution, c.effects(), c.expression(), c.primitiveRules(), c.applications(),
            c.assumptions(), c.root(), work.units());
    }

    private static SchematicProofPlan plan(String source, String prepared) {
        var holes = HOLES.stream().map(id -> {
            boolean sign = id.endsWith("sign");
            var kind = sign ? SchematicProofPlan.HoleKind.SIGN : SchematicProofPlan.HoleKind.DISJOINT_TERM_PAIR;
            return new SchematicProofPlan.Hole(id, kind, kind.requiredSort(), "source-additive-components", "residual/v1",
                new SchematicProofPlan.HoleBudget(12, 128, 2, 4));
        }).toList();
        var obligations = CHECKS.stream().map(k -> new SchematicProofPlan.Obligation(
            k.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'), k, "compose", HOLES, List.of(), CHECKER, REVISION,
            SchematicProofPlan.InitialObligationStatus.OPEN)).toList();
        var ids = obligations.stream().map(SchematicProofPlan.Obligation::id).toList();
        var steps = List.of(new SchematicProofPlan.Step("form", SchematicProofPlan.StepAction.FORM_CANDIDATES, HOLES, List.of()),
            new SchematicProofPlan.Step("compose", SchematicProofPlan.StepAction.COMPOSE, HOLES, ids),
            new SchematicProofPlan.Step("discharge", SchematicProofPlan.StepAction.DISCHARGE_OBLIGATIONS, List.of(), ids),
            new SchematicProofPlan.Step("emit", SchematicProofPlan.StepAction.EMIT_CANDIDATE, List.of(), ids));
        return SchematicProofPlan.create("signed-residual-pairs", SchematicProofPlan.InformationBoundary.TARGET_FREE_FORMATION,
            SolverIr.sha256(source.length() + ":" + source + prepared), steps, holes, obligations,
            new SchematicProofPlan.Limits(4, 4, 4, 16_384));
    }

    private static List<SchematicProofPlanResolution.HoleBinding> bindings(PreparedResidual prepared, List<Effect> effects,
                                                                         String root, Work work) {
        work.add(prepared.expression().length());
        var ordered = new ArrayList<String>();
        occurrencePaths(new ExpressionParser().parseExactTerm(prepared.expression()).expression(), "", ordered, work);
        var indexed = new HashMap<String,String>();
        for (int i = 0; i < ordered.size(); i++) indexed.put(prepared.parts().get(i).id(), ordered.get(i));
        var bindings = new ArrayList<SchematicProofPlanResolution.HoleBinding>();
        for (int i = 0; i < effects.size(); i++) {
            var e = effects.get(i);
            var pair = e.components().stream().map(c -> indexed.get(c.id())).sorted().toList();
            bindings.add(new SchematicProofPlanResolution.HoleBinding(HOLES.get(i*2), SchematicProofPlan.HoleSort.OCCURRENCE_PAIR,
                String.join("|", pair), root));
            bindings.add(new SchematicProofPlanResolution.HoleBinding(HOLES.get(i*2+1), SchematicProofPlan.HoleSort.SIGN,
                e.id().startsWith("reflected-") ? "-1" : "1", root));
        }
        return bindings;
    }

    private static void occurrencePaths(Expr expression, String path, List<String> out, Work work) {
        work.add(1);
        if (expression instanceof BinaryExpr binary && binary.operator() == BinaryOperator.ADD) {
            String prefix = path.isEmpty() ? "" : path + ".";
            occurrencePaths(binary.left(), prefix + "0", out, work);
            occurrencePaths(binary.right(), prefix + "1", out, work);
        } else out.add(path);
    }

    public static RewriteProgram.BudgetedSource compile(Bound offered, long verifierBudget) {
        var verified = bind(offered.source(), offered.effects(), verifierBudget);
        if (!verified.equals(offered)) throw new IllegalArgumentException("offer differs from native replay of bound plan");
        return program(verified, 0, REVISION);
    }

    static RewriteProgram.BudgetedSource compileSearch(String source, List<String> effects, String root,
                                                        long verifierBudget, long acquisitionWork, String acquisition) {
        var verified = bind(source, effects, verifierBudget);
        if (!verified.proofRoot().equals(root)) throw new IllegalArgumentException("bound proof differs from search output");
        return program(verified, acquisitionWork, acquisition);
    }

    private static RewriteProgram.BudgetedSource program(Bound verified, long acquisitionWork, String acquisition) {
        var source = new CheckedSource(verified, Math.addExact(verified.checkWork(), acquisitionWork), acquisition);
        return new RewriteProgram.BudgetedSource(RewriteProgram.NodeMetadata.named("bound-residual-plan"), source);
    }

    private record CheckedSource(Bound bound, long totalWork, String acquisition) implements BudgetedTransformationSource {
        @Override public SourceIdentity identity() {
            return new SourceIdentity(CHECKER, REVISION, SolverIr.sha256(bound.proofRoot() + "|" + bound.resolution().contentHash() + "|" + acquisition));
        }
        @Override public Result transform(String expression, long available) {
            var id = identity();
            if (!expression.equals(bound.source())) return Result.noMatch(id, expression, available, 1, "SOURCE_MISMATCH");
            if (available < totalWork) return Result.budgetInconclusive(id, expression, available, totalWork, 2, "REPLAY_WORK_REQUIRED");
            var edge = ExactTheoryTransition.create(expression, bound.expression(), CHECKER, id.authorityHash(),
                bound.assumptions(), totalWork, bound.proofRoot());
            return Result.candidates(id, expression, available, List.of(edge), 3, "BOUND_RESIDUAL_PLAN");
        }
    }
}
