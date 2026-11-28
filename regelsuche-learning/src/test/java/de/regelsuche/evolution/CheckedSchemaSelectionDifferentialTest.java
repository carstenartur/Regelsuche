package de.regelsuche.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.search.moves.MoveVerifier;
import de.regelsuche.search.moves.NativeMoveProof;
import de.regelsuche.search.program.AstTransportObservation;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.ExactTheoryEvidence;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.TransformationProvenance;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.ExprIncrementalProvider;
import de.regelsuche.search.moves.MoveContext;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.NativeMoveProvider;
import de.regelsuche.search.moves.NativeSearchMove;
import de.regelsuche.search.moves.SearchMove;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.moves.TypedSourceOnlySearch;
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
        for (var move : actual.moves()) {
            assertTrue(model.nativeVerifier().verify(state, move, CONTEXT).accepted(), source);
            var evidence = move.exportLegacy();
            var reference = model.verifier(true).verify(state, evidence, CONTEXT);
            var shared = model.verifierWithSharedContext().verify(state, evidence, CONTEXT);
            assertTrue(shared.accepted(), source);
            assertEquals(reference.receipts(), shared.receipts(), source);
        }
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
        var wrongPath = canonical.deepCopy();
        wrongPath.withArray("path").add(1);
        var wrongCost = canonical.deepCopy().put("applicationWork", binding.canonicalWorkUnits() + 1);
        var wrongTarget = canonical.deepCopy().put("target", codec.encodeExpression(parse("y")));
        for (String mutation : List.of(changed.toString(), extra.toString(), missing.toString(), canonical.toPrettyString(),
                wrongPath.toString(), wrongCost.toString(), wrongTarget.toString())) {
            var contents = new ObjectMapper().readTree(mutation);
            var forged = new ExactTheoryEvidence.Binding(binding.sourceExpression(), contents.get("target").textValue(),
                binding.theoryStepId(), SchematicProofPlan.hash(mutation), binding.receiptArtifactId(), binding.runArtifactId(),
                contents.get("applicationWork").longValue(), mutation);
            for (var check : List.of(model.replayApplicationFromSource(state(source), forged, context),
                    model.replayApplication(state(source), forged, context),
                    model.replayApplicationWithSharedContext(state(source), forged, context))) {
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

    @Test void sharedNodeGoalRetainsTheProofAndItsIndependentReplayAtTheBudgetBoundary() {
        var query = task("(((r+s)/7)+0)*1", 5);
        var previous = inventory.newTaskSolverWithRegeneratedWitness(model);
        var shared = inventory.newTaskSolverWithDomainReceipts(model);
        assertNotEquals(previous.preparationReceipt(), shared.preparationReceipt());
        var control = previous.solve(query);
        var actual = shared.solve(query);
        assertTrue(control.solved() && actual.solved());
        assertTrue(actual.learnedApplications() > 0);
        assertEquals(control.evidence().witness(), actual.evidence().witness());
        assertEquals(control.evidence().inputScore(), actual.evidence().inputScore());
        assertEquals(control.evidence().outputScore(), actual.evidence().outputScore());
        assertEquals(control.evidence().replayWork(), actual.evidence().replayWork());
        assertTrue(actual.evidence().queryWork() < control.evidence().queryWork());

        // Scoring an unrelated expression can only reuse a count in the same problem.
        var first = shared.scopedTask(query);
        var second = shared.scopedTask(query);
        var unrelated = new TypedMoveSearch.State(parse("a*b+3"), 1, 1, "", List.of(), Set.of(), 0);
        first.problem().stateValue().evaluate(unrelated, CONTEXT);
        assertEquals(1, first.objective().evaluate(unrelated).work());
        assertEquals(6, second.objective().evaluate(unrelated).work());

        TypedSourceOnlySearch.Objective custom = ignored -> new TypedSourceOnlySearch.Score(7, 12);
        var customTask = new CheckedSchemaTaskSolver.Task(query.source(), List.of(),
            new CheckedSchemaTaskSolver.Goal(custom, 7), query.budget());
        assertSame(custom, shared.scopedTask(customTask).objective());
        var budget = query.budget();
        long total = actual.evidence().totalWork();
        for (long limit : List.of(total - 1, total)) {
            var bounded = shared.solve(new CheckedSchemaTaskSolver.Task(query.source(), query.assumptions(), query.goal(),
                new MoveSearch.Budget(budget.maxPrimitiveSteps(), budget.maxSearchDepth(), budget.maxTheoryWork(),
                    budget.maxStates(), limit, budget.maxComplexityDebt())));
            assertEquals(limit == total, bounded.solved(), "work budget " + limit);
        }
    }

    @Test void sharedContextRechecksEveryUntouchedSiblingAndReconstructsTheOriginalProposalCost() {
        Expr sibling = parse("(a+b)*(c+d)+(u-v)*(w+z)");
        for (int i = 0; i < 3; i++) sibling = new BinaryExpr(sibling, BinaryOperator.ADD, sibling);
        var input = new BinaryExpr(sibling, BinaryOperator.SUB, parse("(r+0)*1"));
        var state = state(input);
        var move = selected.candidates(state, CONTEXT).moves().getFirst().exportLegacy();
        var binding = ((TransformationProvenance.ExactTheoryStep) move.provenance()).evidence().binding();
        var prior = model.replayApplicationFromSource(state, binding, CONTEXT);
        var actual = model.replayApplicationWithSharedContext(state, binding, CONTEXT);
        assertTrue(actual.accepted());
        assertEquals(prior.checkedProof().exportLegacy(), actual.checkedProof().exportLegacy());
        assertTrue(actual.work() < prior.work());
        var invalid = new BinaryExpr(parse("a/z"), BinaryOperator.SUB, parse("(r+0)*1"));
        assertThrows(CheckedSchemaSupport.DomainRejected.class,
            () -> model.replayApplicationWithSharedContext(state(invalid), binding, CONTEXT));
    }

    @Test void regeneratedExpansionPreservesAbsoluteSizeAndDepthLimitsAcrossReusedSubtrees() throws Exception {
        var expanded = CheckedLearnedSchemaModelTest.checkedExpansionModel(model).schemas().getFirst();
        var b = model.bounds();
        var five = new CheckedLearnedSchemaModel.Bounds(5, b.maximumPatternNodes(), 2, b.maximumCoefficientBits(),
            b.maximumExponent(), b.maximumExamples(), b.maximumPairAttempts(), b.maximumSchemas(),
            b.maximumMatchAttempts(), b.maximumCandidates());
        var exact = new CheckedSubstitutionReplay(expanded, five, List.of(), new CheckedSchemaSupport.Work());
        assertEquals(parse("(a+0)+0"), exact.rewrite(parse("a+0")));
        assertThrows(CheckedSchemaSupport.DomainRejected.class, () ->
            new CheckedSubstitutionReplay(expanded, five, List.of(1), new CheckedSchemaSupport.Work()).rewrite(parse("b-(a+0)")));
        var depthOnly = new CheckedLearnedSchemaModel.Bounds(64, b.maximumPatternNodes(), 2, b.maximumCoefficientBits(),
            b.maximumExponent(), b.maximumExamples(), b.maximumPairAttempts(), b.maximumSchemas(),
            b.maximumMatchAttempts(), b.maximumCandidates());
        assertThrows(CheckedSchemaSupport.DomainRejected.class, () ->
            new CheckedSubstitutionReplay(expanded, depthOnly, List.of(), new CheckedSchemaSupport.Work()).rewrite(parse("(a+b)+0")));
    }

    @Test void sharedContextSolverChargesBothIndependentChecksAtTheExactBoundary() {
        var query = task("(((r+s)/7)+0)*1", 5);
        var original = inventory.newTaskSolverWithDomainReceipts(model).solve(query);
        var optimized = inventory.newTaskSolverWithSharedContext(model);
        var actual = optimized.solve(query);
        assertTrue(original.solved() && actual.solved());
        assertTrue(actual.learnedApplications() > 0);
        assertEquals(original.evidence().witness().stream().map(TypedMoveSearch.WitnessStep::move).toList(),
            actual.evidence().witness().stream().map(TypedMoveSearch.WitnessStep::move).toList());
        var proofWork = actual.evidence().witness().stream().mapToLong(step -> step.verification().work()).sum();
        assertTrue(proofWork > 0);
        assertEquals(proofWork, actual.evidence().replayWork());
        long total = actual.evidence().totalWork();
        var budget = query.budget();
        for (long limit : List.of(total - 1, total)) {
            var bounded = optimized.solve(new CheckedSchemaTaskSolver.Task(query.source(), query.assumptions(), query.goal(),
                new MoveSearch.Budget(budget.maxPrimitiveSteps(), budget.maxSearchDepth(), budget.maxTheoryWork(),
                    budget.maxStates(), limit, budget.maxComplexityDebt())));
            assertEquals(limit == total, bounded.solved(), "work budget " + limit);
        }
    }

    @Test void independentlyRebuiltAncestorsRecheckTheNewDenominatorAndExponent() throws Exception {
        var p = PatternExpr.var("P0");
        var expansion = CheckedLearnedSchemaModelTest.checkedModel(model, p, PatternExpr.op(BinaryOperator.ADD, p, PatternExpr.num(0)));
        var schema = expansion.schemas().getFirst();
        for (String expression : List.of("x/2", "x^2")) {
            var work = new CheckedSchemaSupport.Work();
            assertThrows(CheckedSchemaSupport.DomainRejected.class, () ->
                new CheckedSubstitutionReplay(schema, expansion.bounds(), List.of(1), work).rewrite(parse(expression)));
            assertTrue(work.units > 0);
        }
        var ok = new CheckedSubstitutionReplay(schema, expansion.bounds(), List.of(0), new CheckedSchemaSupport.Work());
        assertEquals(parse("(x+0)/2"), ok.rewrite(parse("x/2")));
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
