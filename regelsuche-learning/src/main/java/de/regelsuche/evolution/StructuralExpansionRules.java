package de.regelsuche.evolution;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.Expr;
import de.regelsuche.knowledge.RuleDescriptor;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.search.moves.MoveProvider;
import de.regelsuche.search.moves.MoveVerifier;
import de.regelsuche.search.moves.IncrementalProviderContract;
import de.regelsuche.search.moves.RegisteredIncrementalMoveProvider;
import de.regelsuche.search.moves.SearchMove;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.AstRewriteTransport;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Existing general AST rules, bound to the normal typed frontier and source-bound primitive replay. */
final class StructuralExpansionRules {
    private static final String REVISION = "structural-expansion/v1:";
    private static final Set<String> RULE_IDS = Set.of("ast_power_two_to_product", "ast_distribute_left_add",
        "ast_distribute_right_add", "ast_distribute_left_subtract", "ast_distribute_right_subtract",
        "ast_canonical_normalize", "ast_signed_additive_normalize", "ast_double_term");

    private StructuralExpansionRules() { }

    static boolean isExpansionProvider(MoveProvider.Descriptor provider) {
        String id = provider.id();
        return id.startsWith(REVISION) && RULE_IDS.contains(id.substring(REVISION.length()));
    }

    static TypedLearnedMoveInventory.SearchSession attach(TypedLearnedMoveInventory.SearchSession session) {
        return attach(session, false);
    }

    static TypedLearnedMoveInventory.SearchSession attach(TypedLearnedMoveInventory.SearchSession session, boolean incremental) {
        var providers = new ArrayList<>(session.providers());
        var verifiers = new HashMap<String, TypedMoveSearch.Verifier>();
        for (var builtin : AstRewriteTransformationEngine.allBuiltInRules()) {
            if (!RULE_IDS.contains(builtin.id())) continue;
            var rule = new Alias(builtin);
            if (providers.stream().anyMatch(provider -> provider.descriptor().id().equals(rule.id())))
                throw new IllegalArgumentException("reserved expansion rule identifier: " + rule.id());
            var transport = new AstRewriteTransport(List.of(rule), 64, 64);
            var descriptor = new MoveProvider.Descriptor(rule.id(), "structural-expansion", SearchMove.SourceKind.BRIDGE,
                SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN,
                SchematicProofPlan.hash(REVISION + builtin.id()));
            var provider = meteredProvider(descriptor, transport);
            providers.add(incremental ? incremental(provider) : provider);
            verifiers.put(rule.id(), meteredReplay(transport));
        }
        if (verifiers.size() != RULE_IDS.size()) throw new IllegalStateException("built-in expansion inventory changed");
        return new TypedLearnedMoveInventory.SearchSession(providers, session.primitiveCaches(),
            (source, move, context) -> verifiers.getOrDefault(move.ruleId(), session.verifier()).verify(source, move, context));
    }

    private static TypedMoveSearch.TypedProvider meteredProvider(MoveProvider.Descriptor descriptor, AstRewriteTransport transport) {
        var primitive = new MoveProvider.Descriptor(descriptor.id(), descriptor.ruleFamily(), SearchMove.SourceKind.PRIMITIVE,
            descriptor.proofStrength(), descriptor.requiredAssumptions(), descriptor.valueEvidence(), descriptor.provenanceId());
        var delegate = TypedMoveSearch.primitiveProvider(primitive, transport);
        return new TypedMoveSearch.TypedProvider() {
            @Override public Descriptor descriptor() { return descriptor; }
            @Override public Batch candidates(de.regelsuche.search.moves.MoveState state, de.regelsuche.search.moves.MoveContext context) {
                var measured = observe(() -> delegate.candidates(state, context));
                var batch = measured.value();
                var metrics = batch.work().withDelegatedMechanicalWork(
                    Math.addExact(batch.work().delegatedMechanicalWorkUnits(), measured.work()));
                var moves = batch.moves().stream().map(move -> SearchMove.from(move.transformation(), descriptor, metrics.totalWorkUnits())).toList();
                return new Batch(moves, metrics, batch.complete());
            }
        };
    }

    private static TypedMoveSearch.Verifier meteredReplay(AstRewriteTransport transport) {
        var delegate = TypedMoveSearch.primitiveReplay(transport);
        return (source, move, context) -> {
            if (move.sourceKind() != SearchMove.SourceKind.BRIDGE)
                return new MoveVerifier.Verification(false, 1, List.of(), "EXPANSION_SOURCE_KIND_REJECTED");
            var measured = observe(() -> delegate.verify(source, move, context));
            var result = measured.value();
            return new MoveVerifier.Verification(result.accepted(), Math.addExact(result.work(), measured.work()),
                result.receipts(), result.reason());
        };
    }

    private static MoveProvider incremental(TypedMoveSearch.TypedProvider provider) {
        var descriptor = provider.descriptor();
        var definition = new IncrementalProviderContract.Definition(IncrementalProviderContract.REVISION, descriptor.id(),
            IncrementalProviderContract.Kind.REGISTERED_SCHEMA, descriptor.provenanceId(), REVISION,
            IncrementalProviderContract.Transport.TYPED_AST_JSON, IncrementalProviderContract.Mathematics.PRIMITIVE, null);
        var registration = new IncrementalProviderContract.Registration(definition,
            (state, context, meter) -> new AtomicTypedBatchCursor(provider, state, context, meter));
        return new RegisteredIncrementalMoveProvider(descriptor, definition,
            new IncrementalProviderContract.Registry(List.of(registration)));
    }

    static <T> Observation<T> observe(Supplier<T> operation) {
        return observe(operation, Long.MAX_VALUE);
    }

    static <T> Observation<T> observe(Supplier<T> operation, long maximumWork) {
        if (maximumWork < 0) throw new IllegalArgumentException("negative observation work limit");
        if (RetainedOperation.isObserved())
            throw new IllegalStateException("nested retained-operation accounting is not supported");
        var meter = new Meter(maximumWork);
        try (var scope = RetainedOperation.open(meter)) {
            return new Observation<>(operation.get(), meter, false);
        } catch (ProcedureWorkLimitExceeded exhausted) {
            return new Observation<>(null, meter, true);
        } catch (RuntimeException failure) {
            throw new CheckedSchemaTaskSolver.ProviderFailure(meter.total(), failure);
        }
    }

    /** Carries the mutable meter until the observation scope has charged its close. */
    record Observation<T>(T value, Meter meter, boolean exhausted) {
        long work() { return meter.total(); }
    }

    private static final class Meter implements RetainedOperation.Sink {
        private final long maximumWork;
        private long total;
        private boolean limitSignalled;
        private Meter(long maximumWork) { this.maximumWork = maximumWork; }
        @Override public void executionWork(long units) { add(units); }
        @Override public void validationWork(long units) { add(units); }
        @Override public void checkpoint() { checkLimit(); }
        @Override public long observedWork() { return total; }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { }
        long total() { return total; }
        private void add(long units) {
            if (units < 0) throw new IllegalArgumentException("negative observed work");
            total = Math.addExact(total, units);
            checkLimit();
        }
        private void checkLimit() {
            if (total > maximumWork && !limitSignalled) {
                limitSignalled = true;
                throw new ProcedureWorkLimitExceeded();
            }
        }
    }

    private static final class ProcedureWorkLimitExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /** A private namespace keeps external inventory identifiers from granting authority to another rule. */
    private record Alias(RewriteRule delegate) implements RewriteRule {
        @Override public String id() { return REVISION + delegate.id(); }
        @Override public RewriteKind kind() { return delegate.kind(); }
        @Override public boolean mayIncreaseComplexity() { return delegate.mayIncreaseComplexity(); }
        @Override public int estimatedCostDelta() { return delegate.estimatedCostDelta(); }
        @Override public boolean isEquivalencePreservingByConstruction() { return delegate.isEquivalencePreservingByConstruction(); }
        @Override public boolean matches(Expr subtree) { return delegate.matches(subtree); }
        @Override public Expr apply(Expr subtree) { return delegate.apply(subtree); }
        @Override public List<Assumption> assumptions(Expr subtree) { return delegate.assumptions(subtree); }
        @Override public boolean mayEmitAssumptions() { return delegate.mayEmitAssumptions(); }
        @Override public RuleDescriptor descriptor() { return delegate.descriptor(); }
    }
}
