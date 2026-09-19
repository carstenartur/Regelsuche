package de.regelsuche.search.moves;

import de.regelsuche.ast.Expr;
import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.AstRewriteTransport;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * Opt-in structural-state adapter over the existing MoveSearch algorithm.
 * The legacy String frontier stays unchanged; this adapter stores canonical tagged AST JSON
 * in that frontier so producer grouping, exact numeric leaves and scoped symbol identity survive.
 */
public final class TypedMoveSearch {
    public static final String REVISION = "regelsuche.typed-move-search/v1";
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();

    public static final class Policy {
        public static final MovePriorityPolicy INVENTORY_ORDER = MovePriorityPolicy.INVENTORY_ORDER;
        private Policy() {}
    }

    /** Marker for providers that consume the canonical tagged AST transport used by this adapter. */
    public interface TypedProvider extends MoveProvider {}

    public record Context(Expr goal, List<String> initialAssumptions, MoveContext.Phase phase) {
        public Context {
            Objects.requireNonNull(goal, "goal");
            initialAssumptions = AssumptionSignature.ofExpressions(
                Objects.requireNonNull(initialAssumptions, "initialAssumptions")).normalizedAssumptions();
            Objects.requireNonNull(phase, "phase");
        }
        public static Context frozen(Expr goal) {
            return new Context(goal, List.of(), MoveContext.Phase.FROZEN_EVALUATION);
        }
        private MoveContext encoded() {
            return new MoveContext(CODEC.encodeExpression(goal), initialAssumptions, phase);
        }
    }

    public record State(Expr expression, int searchDepth, int primitiveDepth, String previousRule,
            List<String> assumptions, Set<String> capabilities, int complexityDebt) {
        public State {
            Objects.requireNonNull(expression, "expression");
            assumptions = List.copyOf(assumptions);
            capabilities = Set.copyOf(capabilities);
        }
        private static State decode(MoveState state) {
            return new State(CODEC.decodeExpression(state.expression()), state.searchDepth(), state.primitiveDepth(),
                state.previousRule(), state.assumptions(), state.capabilities(), state.complexityDebt());
        }
    }

    @FunctionalInterface
    public interface Verifier {
        MoveVerifier.Verification verify(State source, SearchMove move, Context context);
    }

    public record Problem(Expr source, Context context, List<MoveProvider> providers, MovePriorityPolicy policy,
            Verifier verifier, ToDoubleFunction<State> stateScore, MoveSearch.Mode mode,
            MoveSearch.Scheduling scheduling, MoveSearch.Budget budget) {
        public Problem {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(context, "context");
            providers = List.copyOf(Objects.requireNonNull(providers, "providers"));
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(verifier, "verifier");
            Objects.requireNonNull(stateScore, "stateScore");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(scheduling, "scheduling");
            Objects.requireNonNull(budget, "budget");
            if (policy != Policy.INVENTORY_ORDER) {
                throw new IllegalArgumentException("typed move search v1 supports inventory-order policy only");
            }
            if (providers.stream().anyMatch(provider -> !(provider instanceof TypedProvider))) {
                throw new IllegalArgumentException("typed move search requires typed providers");
            }
        }
    }

    public record WitnessStep(State source, State target, SearchMove move, MoveVerifier.Verification verification) {}

    public record Result(MoveSearch.Outcome outcome, List<WitnessStep> witness, Set<State> reachedStates,
            List<State> deadEndStates, MoveSearch.Metrics metrics, boolean completeBoundedRelation,
            MoveSearch.Result encodedResult) {
        public Result {
            witness = List.copyOf(witness);
            reachedStates = Set.copyOf(reachedStates);
            deadEndStates = List.copyOf(deadEndStates);
            Objects.requireNonNull(metrics, "metrics");
            Objects.requireNonNull(encodedResult, "encodedResult");
        }
        public boolean reached() { return outcome == MoveSearch.Outcome.TARGET_REACHED; }
    }

    public Result search(Problem problem) {
        Objects.requireNonNull(problem, "problem");
        MoveContext encodedContext = problem.context().encoded();
        MoveVerifier verifier = (source, move, ignored) ->
            problem.verifier().verify(State.decode(source), move, problem.context());
        ToDoubleFunction<MoveState> score = state -> problem.stateScore().applyAsDouble(State.decode(state));
        var encoded = new MoveSearch().search(new MoveSearch.Problem(CODEC.encodeExpression(problem.source()),
            encodedContext, problem.providers(), problem.policy(), verifier, score, problem.mode(),
            problem.scheduling(), problem.budget()));
        var witness = encoded.witness().stream()
            .map(step -> new WitnessStep(State.decode(step.source()), State.decode(step.target()),
                step.move(), step.verification())).toList();
        var reached = new LinkedHashSet<State>();
        encoded.reachedStates().forEach(state -> reached.add(State.decode(state)));
        var deadEnds = encoded.deadEndStates().stream().map(State::decode).toList();
        return new Result(encoded.outcome(), witness, reached, deadEnds, encoded.metrics(),
            encoded.completeBoundedRelation(), encoded);
    }

    /**
     * Primitive AST source for this typed adapter. It deliberately reports an incomplete relation:
     * AstRewriteTransport has a finite candidate cap but no explicit truncation receipt.
     */
    public static TypedProvider primitiveProvider(MoveProvider.Descriptor descriptor, AstRewriteTransport transport) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(transport, "transport");
        if (descriptor.sourceKind() != SearchMove.SourceKind.PRIMITIVE) {
            throw new IllegalArgumentException("typed primitive provider requires PRIMITIVE source kind");
        }
        return new TypedProvider() {
            @Override public Descriptor descriptor() { return descriptor; }

            @Override public Batch candidates(MoveState state, MoveContext context) {
                if (!context.carries(descriptor.requiredAssumptions(), state)) {
                    return new Batch(List.of(),
                        new TransformationWorkMetrics(0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0), true);
                }
                Expr source = CODEC.decodeExpression(state.expression());
                var generated = transport.generate(source);
                var work = TransformationWorkMetrics.flatEngine(generated.size());
                var moves = new ArrayList<SearchMove>(generated.size());
                for (var step : generated) {
                    String target = CODEC.encodeExpression(step.target());
                    String applicationKey = applicationKey(state.expression(), target, step.rule());
                    var transformation = new Transformation(step.rule(), target, step.kind(), step.mayIncreaseComplexity(),
                        step.estimatedCostDelta(), step.equivalencePreservingByConstruction(), applicationKey,
                        step.assumptions(), step.packId(), step.license());
                    moves.add(SearchMove.from(transformation, descriptor, work.totalWorkUnits()));
                }
                return new Batch(moves, work, false);
            }
        };
    }

    /** Replays the claimed primitive against the retained producer AST and exact metadata. */
    public static Verifier primitiveReplay(AstRewriteTransport transport) {
        Objects.requireNonNull(transport, "transport");
        return (source, move, context) -> {
            Expr target;
            try {
                target = CODEC.decodeExpression(move.transformation().transformedExpression());
            } catch (IllegalArgumentException exception) {
                return new MoveVerifier.Verification(false, 1, List.of(), "TYPED_TARGET_DECODE_REJECTED");
            }
            String encodedSource = CODEC.encodeExpression(source.expression());
            String encodedTarget = CODEC.encodeExpression(target);
            String expectedApplicationKey = applicationKey(encodedSource, encodedTarget, move.transformation().rule());
            var generated = transport.generate(source.expression());
            boolean primitiveProvenance = move.transformation().provenance()
                    instanceof de.regelsuche.transform.TransformationProvenance.PrimitiveRewriteSequence
                && move.transformation().primitiveRuleIds().equals(List.of(move.transformation().rule()))
                && move.transformation().primitiveStepCount() == 1;
            boolean accepted = primitiveProvenance
                && move.transformation().applicationKey().equals(expectedApplicationKey)
                && generated.stream().anyMatch(step ->
                step.target().equals(target)
                    && step.rule().equals(move.transformation().rule())
                    && step.kind() == move.transformation().kind()
                    && step.mayIncreaseComplexity() == move.transformation().mayIncreaseComplexity()
                    && step.estimatedCostDelta() == move.transformation().estimatedCostDelta()
                    && step.equivalencePreservingByConstruction()
                        == move.transformation().equivalencePreservingByConstruction()
                    && step.assumptions().equals(move.transformation().assumptions())
                    && step.packId().equals(move.transformation().packId())
                    && step.license().equals(move.transformation().license()));
            long work = TransformationWorkMetrics.flatEngine(generated.size()).totalWorkUnits();
            return new MoveVerifier.Verification(accepted, work,
                accepted ? List.of("typed-primitive-replay:" + sha256(encodedSource
                    + "\n" + encodedTarget + "\n" + move.ruleId())) : List.of(),
                accepted ? "TYPED_PRIMITIVE_REPLAYED" : "TYPED_PRIMITIVE_REPLAY_REJECTED");
        };
    }

    private static String applicationKey(String encodedSource, String encodedTarget, String rule) {
        return "typed:" + sha256(encodedSource + "\n" + encodedTarget + "\n" + rule);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
