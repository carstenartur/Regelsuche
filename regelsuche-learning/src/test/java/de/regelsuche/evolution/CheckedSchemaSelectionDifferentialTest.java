package de.regelsuche.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.ast.Expr;
import de.regelsuche.search.moves.MoveVerifier;
import de.regelsuche.search.moves.NativeMoveProof;
import de.regelsuche.search.program.AstTransportObservation;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.ExactTheoryEvidence;
import de.regelsuche.transform.TransformationProvenance;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.ExprIncrementalProvider;
import de.regelsuche.search.moves.MoveContext;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.NativeMoveProvider;
import de.regelsuche.search.moves.NativeSearchMove;
import de.regelsuche.search.moves.SearchMove;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.transform.Transformation;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Differential checks on two independently trained identities outside the local-trace panel. */
class CheckedSchemaSelectionDifferentialTest {
    private static final TypedMoveSearch.Context CONTEXT =
        TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION);
    private static TypedLearnedMoveInventory inventory;
    private static CheckedLearnedSchemaModel model;
    private static ExprIncrementalProvider exhaustive;
    private static ExprIncrementalProvider selected;

    @BeforeAll static void train() {
        var genome = TraceStrategyTransferExample.inventory();
        inventory = TypedLearnedMoveInventory.primitives(genome);
        var solver = inventory.newTaskSolver();
        var initial = CheckedLearnedSchemaModel.empty(genome.contentHash());
        var cancellation = CheckedLearnedSchemaModel.extendFromSolutions(initial, List.of(
            solver.solve(task("((x-y)+y)+0", 1)), solver.solve(task("((a*b-c)+c)+0", 3))));
        var combined = CheckedLearnedSchemaModel.extendFromSolutions(cancellation, List.of(
            solver.solve(task("(v+0)*1", 1)), solver.solve(task("(r*s+0)*1", 3))));
        model = CheckedLearnedSchemaModel.load(combined.toCanonicalJson(), genome.contentHash());
        assertEquals(2, model.schemas().size());
        var ids = model.schemas().stream().map(CheckedLearnedSchemaModel.Schema::id).collect(Collectors.toSet());
        exhaustive = CheckedSchemaMatcherPlan.prepare(model, 2, Map.of(), ids).nativeProvider();
        selected = CheckedSchemaMatcherPlan.prepareSelected(model, 2, Map.of(), ids).nativeProvider();
    }

    @Test void preservesEveryOccurrenceAndExactProofAcrossBindingsAndContexts() {
        for (String x : List.of("r", "(p/3)", "(r+s)")) {
            for (String y : List.of("w", "(u-v)", "(j*k)")) {
                String cancel = "((" + x + "-" + y + ")+" + y + ")+0";
                String product = "(" + x + "*" + y + ")";
                String unit = "(" + product + "+0)*1";
                for (String context : List.of("(%s)", "(%s)-17", "17-(%s)")) {
                    // Domain-valid rational coefficients, repeated subtrees and mirrored operand positions.
                    check(context.formatted(cancel), List.of(context.formatted(x)));
                    check(context.formatted(unit), List.of(context.formatted(product)));
                    check(context.formatted("((" + x + "-" + y + ")+other)+0"), List.of());
                    check(context.formatted("0+((" + x + "-" + y + ")+" + y + ")"), List.of());
                    check(context.formatted("1*(" + product + "+0)"), List.of());
                    check(context.formatted("(" + product + "+1)*1"), List.of());
                }
                check("(" + cancel + ")-(" + unit + ")", List.of(x + "-(" + unit + ")", "(" + cancel + ")-" + product));
            }
        }
    }

    private static void check(String source, List<String> expected) {
        var state = new TypedMoveSearch.State(parse(source), 0, 0, "", List.of(), Set.of(), 0);
        var control = exhaustive.candidates(state, CONTEXT);
        var actual = selected.candidates(state, CONTEXT);
        assertTrue(actual.complete() && control.complete(), source);
        assertEquals(expected.stream().map(CheckedSchemaSelectionDifferentialTest::parse).toList(),
            actual.moves().stream().map(NativeSearchMove::targetExpression).toList(), source);
        assertEquals(proofs(control), proofs(actual), source);
        for (var move : actual.moves()) assertTrue(model.nativeVerifier().verify(state, move, CONTEXT).accepted(), source);
    }

    private static List<Transformation> proofs(NativeMoveProvider.Batch batch) {
        return batch.moves().stream().map(move -> move.proof().exportLegacy()).toList();
    }

    @Test void preservesMixedLeftAndRightEdgesAtTheMaximumAcceptedDepth() {
        String source = "(r+0)*1", target = "r";
        // The deepest leaf of the initial source reaches the model's exact depth bound.
        for (int depth = 0; depth < model.bounds().maximumDepth() - 2; depth++) {
            String context = depth % 3 == 0 ? "(%s)-9" : "9-(%s)";
            source = context.formatted(source);
            target = context.formatted(target);
        }
        check(source, List.of(target));
    }

    @Test void aMatchingPrefixInAnUnsupportedDomainCannotEmitEvidence() {
        var state = new TypedMoveSearch.State(parse("((r+0)*1)+(1/z)"), 0, 0, "", List.of(), Set.of(), 0);
        for (var provider : List.of(exhaustive, selected)) {
            var result = provider.candidates(state, CONTEXT);
            assertFalse(result.complete());
            assertTrue(result.moves().isEmpty());
        }
    }

    @Test void reevaluatesSelectionWhenAPrimitiveStepCreatesTheFirstApplicableSchema() {
        var query = task("(r*1)+(0*1)", 1);
        check("(r*1)+(0*1)", List.of());
        var original = inventory.newTaskSolver(model).solve(query);
        var result = inventory.newTaskSolverWithSelection(model).solve(query);
        var routed = inventory.newTaskSolverWithSelection(model, true).solve(query);
        for (var solution : List.of(original, result, routed)) {
            assertTrue(solution.solved());
            assertEquals(parse("r"), solution.evidence().incumbent().expression());
            assertEquals(List.of(SearchMove.SourceKind.PRIMITIVE, SearchMove.SourceKind.LEARNED),
                solution.evidence().witness().stream().map(step -> step.move().sourceKind()).toList());
            assertEquals(parse("(r+0)*1"), solution.evidence().witness().getFirst().target().expression());
            assertTrue(solution.evidence().replayWork() > 0);
        }
    }

    @Test void sourceDerivedReplayDropsOnlyTheSecondBindingReadAndPreservesTheExactWitness() {
        Expr source = parse("(((a/7+b)-y)+y)+0");
        var state = state(source);
        var move = selected.candidates(state, CONTEXT).moves().getFirst().exportLegacy();
        var context = TypedMoveSearch.Context.frozen(parse("a/7+b"));
        MoveVerifier.Verification previous;
        long decodes;
        long encodes;
        long evidenceWrites;
        try (var transport = AstTransportObservation.open()) {
            previous = model.verifier().verify(state, move, context);
            decodes = transport.count(AstTransportObservation.Operation.EXPRESSION_DECODE);
            encodes = transport.count(AstTransportObservation.Operation.EXPRESSION_ENCODE);
            evidenceWrites = transport.count(AstTransportObservation.Operation.EVIDENCE_JSON_WRITE);
        }
        assertTrue(previous.accepted());
        assertTrue(decodes > 0 && evidenceWrites > 0);
        try (var transport = AstTransportObservation.open()) {
            var checked = model.verifier(true).verify(state, move, context);
            assertTrue(checked.accepted());
            assertEquals(previous.receipts(), checked.receipts());
            assertTrue(checked.work() > 0 && checked.work() < previous.work());
            assertEquals(0, transport.count(AstTransportObservation.Operation.EXPRESSION_DECODE));
            assertEquals(encodes, transport.count(AstTransportObservation.Operation.EXPRESSION_ENCODE));
            assertEquals(evidenceWrites, transport.count(AstTransportObservation.Operation.EVIDENCE_JSON_WRITE));
        }
        assertFalse(model.verifier(true).verify(state(parse("(((a/7+b)-y)+z)+0")), move, context).accepted());
    }

    @Test void sourceDerivedImportRejectsTamperedMissingExtraAndNoncanonicalBindings() throws Exception {
        Expr source = parse("((x-y)+y)+0");
        var move = selected.candidates(state(source), CONTEXT).moves().getFirst().exportLegacy();
        var binding = ((TransformationProvenance.ExactTheoryStep) move.provenance()).evidence().binding();
        var context = TypedMoveSearch.Context.frozen(parse("x"));
        var accepted = model.replayApplicationFromSource(state(source), binding, context);
        assertTrue(accepted.accepted());
        var proof = assertInstanceOf(NativeMoveProof.Exact.class, accepted.checkedProof());
        assertEquals(binding, proof.evidence().exportLegacy().binding());

        var canonical = (ObjectNode) new ObjectMapper().readTree(binding.canonicalEvidenceJson());
        var codec = new CompiledAstReplayCodec();
        var changed = canonical.deepCopy();
        ((ObjectNode) changed.get("bindings").get(0)).put("expression", codec.encodeExpression(parse("y+7")));
        var extra = canonical.deepCopy();
        extra.withArray("bindings").addObject().put("name", "unexpected").put("expression", codec.encodeExpression(parse("u")));
        var missing = canonical.deepCopy();
        missing.withArray("bindings").remove(0);
        for (String mutation : List.of(changed.toString(), extra.toString(), missing.toString(), canonical.toPrettyString())) {
            var forged = new ExactTheoryEvidence.Binding(binding.sourceExpression(), binding.transformedExpression(),
                binding.theoryStepId(), SchematicProofPlan.hash(mutation), binding.receiptArtifactId(), binding.runArtifactId(),
                binding.canonicalWorkUnits(), mutation);
            for (var check : List.of(model.replayApplicationFromSource(state(source), forged, context),
                    model.replayApplication(state(source), forged, context))) {
                assertFalse(check.accepted());
                assertTrue(check.work() > 0);
                assertNull(check.checkedProof());
            }
        }
    }

    @Test void regeneratedWitnessIsRecheckedOnBothPassesAndTheTotalBudgetIncludesFinalReplay() {
        var query = task("(((r+s)/7)+0)*1", 5);
        var reference = inventory.newTaskSolverWithSelection(model, true);
        var optimized = inventory.newTaskSolverWithRegeneratedWitness(model);
        assertNotEquals(reference.preparationReceipt(), optimized.preparationReceipt());
        var previous = reference.solve(query);
        var actual = optimized.solve(query);
        assertTrue(previous.solved() && actual.solved());
        assertEquals(previous.evidence().witness().stream().map(step -> step.move()).toList(),
            actual.evidence().witness().stream().map(step -> step.move()).toList());
        assertTrue(actual.learnedApplications() > 0);
        assertTrue(actual.evidence().queryWork() < previous.evidence().queryWork());
        assertTrue(actual.evidence().replayWork() > 0 && actual.evidence().replayWork() < previous.evidence().replayWork());

        var budget = query.budget();
        long total = actual.evidence().totalWork();
        for (long limit : List.of(1L, total - 1, total)) {
            var bounded = optimized.solve(new CheckedSchemaTaskSolver.Task(query.source(), query.assumptions(), query.goal(),
                new MoveSearch.Budget(budget.maxPrimitiveSteps(), budget.maxSearchDepth(), budget.maxTheoryWork(),
                    budget.maxStates(), limit, budget.maxComplexityDebt())));
            assertEquals(limit == total, bounded.solved(), "work budget " + limit);
            if (limit == total - 1) assertTrue(bounded.evidence().totalWork() > limit);
        }
    }

    @Test void anAbsentOperatorCanExcludeAllLearnedRulesOnlyIfPrimitivesCannotIntroduceIt() {
        var query = task("r^1", 1);
        var original = inventory.newTaskSolverWithSelection(model, true);
        assertTrue(original.problem(query).providers().stream()
            .noneMatch(provider -> provider.descriptor().sourceKind() == SearchMove.SourceKind.LEARNED));
        assertFalse(original.solve(query).solved());

        var genome = TraceStrategyTransferExample.inventory();
        // This exact primitive introduces both ADD and SUB, which do not occur in the query.
        var extended = genome.withRewrites(genome.rewrites().stream().map(gene -> gene.geneId().equals("double-term")
            ? gene.withPatterns("?A^1", "((?A-2)+2)+0") : gene).toList());
        var alternate = TypedLearnedMoveInventory.primitives(extended);
        var primitive = alternate.newTaskSolver();
        var cancellation = CheckedLearnedSchemaModel.extendFromSolutions(CheckedLearnedSchemaModel.empty(extended.contentHash()),
            List.of(primitive.solve(task("((x-y)+y)+0", 1)), primitive.solve(task("((a*b-c)+c)+0", 3))));
        var routed = alternate.newTaskSolverWithSelection(cancellation, true).solve(query);
        assertTrue(routed.solved());
        assertEquals(List.of(SearchMove.SourceKind.PRIMITIVE, SearchMove.SourceKind.LEARNED),
            routed.evidence().witness().stream().map(step -> step.move().sourceKind()).toList());
        assertEquals(parse("r"), routed.evidence().incumbent().expression());
        assertTrue(routed.evidence().replayWork() > 0);
    }

    private static TypedMoveSearch.State state(Expr source) {
        return new TypedMoveSearch.State(source, 0, 0, "", List.of(), Set.of(), 0);
    }

    private static Expr parse(String source) {
        return new ExpressionParser().parseExactTerm(source).expression();
    }

    private static CheckedSchemaTaskSolver.Task task(String source, int nodes) {
        return new CheckedSchemaTaskSolver.Task(parse(source), List.of(), CheckedSchemaTaskSolver.Goal.atMostNodes(nodes),
            new MoveSearch.Budget(24, 24, 100_000, 256, 100_000));
    }
}
