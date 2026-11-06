package de.regelsuche.evolution;

import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveContext;
import de.regelsuche.search.moves.MoveProvider;
import de.regelsuche.search.moves.MoveState;
import de.regelsuche.search.moves.MoveVerifier;
import de.regelsuche.search.moves.SearchMove;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.ExactTheoryEvidence;
import de.regelsuche.transform.ExecutionWork;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Typed adapter for bounded existing construction procedures, with independent source-bound replay. */
final class StructuralConstructionProvider implements TypedMoveSearch.TypedProvider {
    private static final String REVISION = "regelsuche.structural-construction/v1";
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private final StructuralConstructionPrograms.Operation operation;
    private final Descriptor descriptor;

    StructuralConstructionProvider(StructuralConstructionPrograms.Operation operation) {
        this.operation = java.util.Objects.requireNonNull(operation);
        descriptor = new Descriptor(REVISION + ":" + operation.name(), "structural-construction",
            SearchMove.SourceKind.PREPARATION, SearchMove.ProofStrength.VERIFIED, List.of(),
            SearchMove.ValueEvidence.UNKNOWN, SchematicProofPlan.hash(REVISION + ":" + operation.name()));
    }
    @Override public Descriptor descriptor() { return descriptor; }

    @Override public Batch candidates(MoveState state, MoveContext context) {
        var source = CODEC.decodeExpression(state.expression());
        var generated = StructuralConstructionPrograms.generate(operation, source);
        long[] work = {generated.work()};
        java.util.function.LongConsumer account = units -> work[0] = Math.addExact(work[0], units);
        var exact = operation == StructuralConstructionPrograms.Operation.BINARY_QUARTIC
                || operation == StructuralConstructionPrograms.Operation.RESIDUAL_EXPANDED
                || operation == StructuralConstructionPrograms.Operation.AUXILIARY_COMPLEMENT
                || operation == StructuralConstructionPrograms.Operation.AUXILIARY_FACTOR
                || operation == StructuralConstructionPrograms.Operation.RECOGNIZE_SQUARE
                || operation == StructuralConstructionPrograms.Operation.RECOGNIZE_METERED_SQUARE
                || operation == StructuralConstructionPrograms.Operation.RECOGNIZE_RATIONAL_SQUARE
                || operation == StructuralConstructionPrograms.Operation.RATIONAL_EXPOSE_SQUARE
            ? ExactPolynomialAnalysis.withProductWork(account) : new ExactPolynomialAnalysis(account);
        String input = ExpressionFormatter.format(source); work[0] = Math.addExact(work[0], input.length());
        var proofs = new ArrayList<Transformation>();
        var seen = new java.util.HashSet<String>();
        for (String output : generated.candidates()) {
            long before = work[0];
            try {
                var target = new ExpressionParser().parseExactTerm(output).expression();
                String encoded = CODEC.encodeExpression(target);
                work[0] = Math.addExact(work[0], output.length() + (long) encoded.length() + 1);
                if (target.equals(source) || !seen.add(encoded)) continue;
                exact.requireEquivalent(input, output);
                var verified = new VerifiedConstruction(state.expression(), encoded, descriptor.id(), work[0] - before);
                proofs.add(Transformation.exactTheory(ExactTheoryEvidence.fromVerified(verified)));
            } catch (IllegalArgumentException unsupported) {
                // Failed mathematical checks remain charged and cannot issue an edge.
            }
        }
        var math = proofs.stream().map(Transformation::executionWork).reduce(ExecutionWork.ZERO, ExecutionWork::plus);
        long mechanics = Math.subtractExact(work[0], math.canonicalWorkUnits());
        var metrics = TransformationWorkMetrics.flatEngine(proofs.size()).withCandidateWork(math)
            .withDelegatedMechanicalWork(mechanics);
        return new Batch(proofs.stream().map(proof -> SearchMove.from(proof, descriptor, metrics.totalWorkUnits())).toList(), metrics, false);
    }

    MoveProvider incremental() {
        var definition = new de.regelsuche.search.moves.IncrementalProviderContract.Definition(
            de.regelsuche.search.moves.IncrementalProviderContract.REVISION, descriptor.id(),
            de.regelsuche.search.moves.IncrementalProviderContract.Kind.REGISTERED_SCHEMA,
            descriptor.provenanceId(), REVISION,
            de.regelsuche.search.moves.IncrementalProviderContract.Transport.TYPED_AST_JSON,
            de.regelsuche.search.moves.IncrementalProviderContract.Mathematics.EXACT, null);
        var registration = new de.regelsuche.search.moves.IncrementalProviderContract.Registration(definition,
            (state, context, meter) -> new AtomicTypedBatchCursor(this, state, context, meter));
        return new de.regelsuche.search.moves.RegisteredIncrementalMoveProvider(descriptor, definition,
            new de.regelsuche.search.moves.IncrementalProviderContract.Registry(List.of(registration)));
    }

    TypedMoveSearch.Verifier verifier() {
        return (source, move, context) -> {
            var encoded = new MoveState(CODEC.encodeExpression(source.expression()), source.searchDepth(), source.primitiveDepth(),
                source.previousRule(), source.assumptions(), source.capabilities(), source.complexityDebt());
            var batch = candidates(encoded, new MoveContext("", context.initialAssumptions(), context.phase()));
            boolean accepted = move.sourceKind() == descriptor.sourceKind() && batch.moves().stream()
                .anyMatch(expected -> SearchMove.from(expected.transformation(), descriptor, move.generationCost()).equals(move));
            return new MoveVerifier.Verification(accepted, batch.work().totalWorkUnitsV2(),
                accepted ? List.of("construction-replayed:" + move.provenance().contentHash()) : List.of(),
                accepted ? "EXACT_CONSTRUCTION_REPLAYED" : "CONSTRUCTION_REPLAY_REJECTED");
        };
    }

    static final class VerifiedConstruction {
        private final ExactTheoryEvidence.Binding binding;
        private VerifiedConstruction(String source, String target, String procedure, long work) {
            var content = new TreeMap<String, Object>();
            content.put("revision", REVISION); content.put("source", source); content.put("target", target);
            content.put("procedure", procedure); content.put("work", work);
            String json = CheckedSchemaSupport.JSON.valueToTree(content).toString();
            String hash = SchematicProofPlan.hash(json);
            binding = new ExactTheoryEvidence.Binding(source, target, procedure, hash, hash,
                SchematicProofPlan.hash(REVISION + source), work, json);
        }
        ExactTheoryEvidence.Binding binding() { return binding; }
    }
}
