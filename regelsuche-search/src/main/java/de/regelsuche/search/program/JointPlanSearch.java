package de.regelsuche.search.program;

import de.regelsuche.ast.Expr;
import de.regelsuche.search.moves.*;
import de.regelsuche.transform.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Domain adapter into TypedSourceOnlySearch, not another frontier/search implementation.
 * The domain supplies candidates and independent semantics; the shared DAG cost is assessed
 * and charged while the existing queue is expanded, and again for incumbent selection.
 */
public final class JointPlanSearch {
    public record Proposal(String rule, Expr expression) {
        public Proposal {
            if (rule == null || rule.isBlank()) throw new IllegalArgumentException("rule required");
            Objects.requireNonNull(expression, "expression");
        }
    }
    public record Generation(List<Proposal> proposals, long work, boolean complete) {
        public Generation {
            proposals = List.copyOf(proposals);
            if (work < 1) throw new IllegalArgumentException("positive generation work required");
        }
    }
    public record Verification(boolean accepted, long work) {
        public Verification { if (work < 1) throw new IllegalArgumentException("positive verification work required"); }
    }
    public interface Domain {
        String revision();
        Generation generate(JointComputationPlan source, int maximumCandidates);
        Verification verify(JointComputationPlan source, JointComputationPlan target);
    }
    public record Weights(long operation, long liveStorage, long retainedStorage, long outputBindings) {
        public static final Weights DEFAULT = new Weights(1, 1, 1, 1);
        public Weights {
            if (operation < 0 || liveStorage < 0 || retainedStorage < 0 || outputBindings < 0)
                throw new IllegalArgumentException("negative cost weight");
        }
        long score(PreparedJointComputation.Cost cost) { return cost.weighted(operation, liveStorage, retainedStorage, outputBindings); }
    }
    public record Result(JointComputationPlan plan, PreparedJointComputation prepared,
            TypedSourceOnlySearch.Result search, long setupWork, long workBudget) {
        public long totalWork() { return Math.addExact(search.totalWork(), setupWork); }
        public boolean withinBudget() { return totalWork() <= workBudget; }
    }
    private record Preparation(PreparedJointComputation plan, long work) {}
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private final ComputationBackend backend;
    private final Domain domain;
    private final Weights weights;
    private final int maximumCandidates;

    public JointPlanSearch(ComputationBackend backend, Domain domain, Weights weights, int maximumCandidates) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.domain = Objects.requireNonNull(domain, "domain");
        this.weights = Objects.requireNonNull(weights, "weights");
        if (maximumCandidates < 1) throw new IllegalArgumentException("positive candidate cap required");
        this.maximumCandidates = maximumCandidates;
    }
    public PreparedJointComputation prepareVerified(JointComputationPlan source, JointComputationPlan candidate) {
        return prepare(source, candidate).plan();
    }
    private Preparation prepare(JointComputationPlan source, JointComputationPlan candidate) {
        if (!source.inputs().equals(candidate.inputs()) || !source.outputs().stream().map(o -> List.of(o.name(), o.type())).toList()
                .equals(candidate.outputs().stream().map(o -> List.of(o.name(), o.type())).toList()))
            throw new IllegalArgumentException("input/output bindings differ");
        var verified = domain.verify(source, candidate);
        if (!verified.accepted()) throw new IllegalArgumentException("domain did not prove joint outputs equivalent");
        var prepared = candidate.prepare(backend);
        return new Preparation(prepared, Math.addExact(verified.work(), prepared.cost().inspectionWork()));
    }
    public Result optimize(JointComputationPlan source, MoveSearch.Budget budget) {
        var sourceExpression = source.searchExpression();
        var initialCheck = prepare(source, source);
        var initial = new Preparation(initialCheck.plan(), Math.addExact(initialCheck.work(), sourceExpression.work()));
        long remaining = Math.max(1, budget.totalWork() - initial.work());
        var searchBudget = new MoveSearch.Budget(budget.maxPrimitiveSteps(), budget.maxSearchDepth(), budget.maxTheoryWork(),
            budget.maxStates(), remaining, budget.maxComplexityDebt());
        var descriptor = new MoveProvider.Descriptor("joint-plan-domain", "joint-plan-domain", SearchMove.SourceKind.PRIMITIVE,
            SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, domain.revision());
        TypedMoveSearch.TypedProvider provider = new TypedMoveSearch.TypedProvider() {
            @Override public Descriptor descriptor() { return descriptor; }
            @Override public Batch candidates(MoveState state, MoveContext context) {
                var current = source.withExpression(CODEC.decodeExpression(state.expression()));
                var generated = domain.generate(current, maximumCandidates);
                if (generated.proposals().size() > maximumCandidates) throw new IllegalArgumentException("domain exceeded candidate cap");
                var moves = new ArrayList<SearchMove>();
                for (var proposal : generated.proposals()) {
                    String target = CODEC.encodeExpression(proposal.expression());
                    var transformation = new Transformation(proposal.rule(), target, RewriteKind.SIMPLIFY, true, 0, false,
                        key(state.expression(), target, proposal.rule()), List.of(), domain.revision(), "Apache-2.0");
                    moves.add(SearchMove.from(transformation, descriptor, generated.work()));
                }
                return new Batch(moves, TransformationWorkMetrics.flatEngine(moves.size()).withDelegatedMechanicalWork(generated.work()), generated.complete());
            }
        };
        TypedMoveSearch.Verifier verifier = (state, move, context) -> {
            long work = 1;
            try {
                var current = source.withExpression(state.expression());
                var candidate = source.withExpression(CODEC.decodeExpression(move.transformation().transformedExpression()));
                var proof = domain.verify(current, candidate);
                work = proof.work();
                if (proof.accepted()) work = Math.addExact(work, candidate.prepare(backend).cost().inspectionWork());
                return new MoveVerifier.Verification(proof.accepted(), work,
                    proof.accepted() ? List.of(domain.revision() + ":" + key(CODEC.encodeExpression(state.expression()),
                        move.transformation().transformedExpression(), move.ruleId())) : List.of(),
                    proof.accepted() ? "JOINT_OUTPUTS_PROVED" : "JOINT_OUTPUTS_REJECTED");
            } catch (IllegalArgumentException malformed) {
                return new MoveVerifier.Verification(false, work, List.of(), "JOINT_PLAN_REJECTED");
            }
        };
        var problem = new TypedMoveSearch.Problem(sourceExpression.expression(),
            TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION), List.of(provider),
            MovePriorityPolicy.INVENTORY_ORDER, verifier, state -> 0, MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED,
            searchBudget, (state, context) -> {
                var cost = source.withExpression(state.expression()).prepare(backend).cost();
                return new StateValue.Assessment(cost.operationCount(), -weights.score(cost), cost.inspectionWork(), 0, Map.of());
            });
        var search = new TypedSourceOnlySearch().search(problem, state -> {
            var cost = source.withExpression(state.expression()).prepare(backend).cost();
            return new TypedSourceOnlySearch.Score(weights.score(cost), cost.inspectionWork());
        });
        var candidate = source.withExpression(search.incumbent().expression());
        var checked = prepare(source, candidate);
        return new Result(candidate, checked.plan(), search, Math.addExact(initial.work(), checked.work()), budget.totalWork());
    }
    private static String key(String source, String target, String rule) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((source + "\n" + target + "\n" + rule).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
