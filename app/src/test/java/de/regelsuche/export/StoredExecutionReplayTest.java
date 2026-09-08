package de.regelsuche.export;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.api.PathReplayDto;
import de.regelsuche.api.TransformationStepDto;
import de.regelsuche.api.searchgraph.*;
import de.regelsuche.api.searchgraph.semantic.*;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.evolution.*;
import de.regelsuche.evolution.ExactFinitePolynomialPlanCandidateEvidenceVerifier.VerifiedCandidateEvidence;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.LoadedArtifact;
import de.regelsuche.graph.*;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
import de.regelsuche.mining.MacroMoveExpansion;
import de.regelsuche.mining.MacroMoveStatistics;
import de.regelsuche.persistence.JsonFileExpressionGraphStore;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.program.RewritePrograms;
import de.regelsuche.search.strategy.SearchExpansionSource;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy;
import de.regelsuche.transform.*;
import de.regelsuche.validation.CandidateProofStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class StoredExecutionReplayTest {
    private static final ExpressionScorer SCORER = new ExpressionScorer();

    @Test void realTheorySurvivesExportAndRestartAndRequiresFreshVerifiedReplay(@TempDir Path directory) {
        var first = evidence("stored-theory");
        var path = path(first);
        String json = new DefaultTransformationExportService(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
            .exportJson(List.of(path), List.of());
        var imported = new DefaultTransformationImportService().importJson(json).transformations().getFirst();
        assertEquals(path, imported);
        assertEquals(path.steps().getFirst().execution(), TransformationStepDto.from(imported.steps().getFirst()).execution());
        var store = new JsonFileExpressionGraphStore(directory);
        store.saveDiscoveredTransformation(imported);
        var reloaded = new JsonFileExpressionGraphStore(directory).discoveredTransformations().getFirst();
        assertEquals(path, reloaded);
        var replay = RecordedPathReplay.verify(reloaded, () -> problem(evidence("stored-theory")));
        assertEquals(new ExecutionWork(0, 1, first.data().canonicalWork().totalWorkUnits()), replay.reachedState().executionWork());
        assertThrows(IllegalArgumentException.class, () -> RecordedPathReplay.verify(reloaded, () -> problem(evidence("substituted-run"))));
        assertThrows(IllegalArgumentException.class, () -> ExactTheoryEvidence.fromVerified(reloaded.steps().getFirst().execution()));
        var withoutEvidence = new TransformationStep(0, path.originalExpression(), path.improvedExpression(),
            path.steps().getFirst().ruleId(), RewriteKind.NORMALIZE, path.originalScore().weightedTotal(),
            path.improvedScore().weightedTotal(), true, "display only");
        var incomplete = new DiscoveredTransformation(path.id(), path.originalExpression(), path.improvedExpression(),
            List.of(withoutEvidence), path.originalScore(), path.improvedScore(), path.totalImprovement(),
            path.validationStatus(), path.discoveredAt(), path.canonicalHash());
        var calls = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> RecordedPathReplay.verify(incomplete, () -> { calls.incrementAndGet(); return problem(first); }));
        assertEquals(0, calls.get());
    }

    @Test void graphReplayAndSemanticViewsRetainDistinctTheoryEvidence(@TempDir Path directory) {
        var first = path(evidence("graph-theory-a"));
        var second = path(evidence("graph-theory-b"));
        var edges = List.of(edge(first), edge(second));
        var graph = new SearchGraphAssembler().assemble(new GraphSnapshot(
            List.of(first.originalExpression(), first.improvedExpression()), edges), List.of());
        assertEquals(2, graph.edges().stream().map(SearchGraphEdgeDto::sourceEdgeId).distinct().count());
        var replayStep = new PathReplayDto.ReplayStep(0, first.originalExpression(), first.originalExpression(),
            first.improvedExpression(), first.improvedExpression(), first.steps().getFirst().ruleId(), "verified theory", 0,
            true, false, List.of(), List.of(), null, first.steps().getFirst().execution());
        var record = new SearchGraphRecord("theory-session", Instant.EPOCH, "EXPLICIT_THEORY", List.of("polynomial"),
            graph, List.of(new PathReplayDto(first.id(), List.of(replayStep))), List.of(), List.of(), Map.of());
        var repositoryFile = directory.resolve("graphs.json");
        new JsonFileSearchGraphRepository(repositoryFile).save(record);
        var loaded = new JsonFileSearchGraphRepository(repositoryFile).findById(record.id()).orElseThrow();
        assertEquals(graph.edges(), loaded.graph().edges());
        assertEquals(replayStep.execution(), loaded.replays().getFirst().steps().getFirst().execution());
        assertTrue(SearchGraphJsonSerializer.toJson(graph).contains("EXACT_THEORY_STEP"));
        var semantic = new SemanticSearchGraphAssembler().assemble(graph, List.of(first, second), List.of(),
            SemanticGraphViewMode.SEMANTIC, true, true, false, 12, 8);
        assertEquals(2, semantic.executionObservations().size());
        assertTrue(SemanticSearchGraphJsonSerializer.toJson(semantic).contains("EXACT_THEORY_STEP"));
        var changed = SearchGraphRecordCodec.toJson(record).replace("\\\"exactTheorySteps\\\":1", "\\\"exactTheorySteps\\\":0");
        assertNotEquals(SearchGraphRecordCodec.toJson(record), changed);
        assertThrows(IllegalArgumentException.class, () -> SearchGraphRecordCodec.fromJson(changed));
    }

    @Test void macroAtomicEvidenceAndAssumptionsSurviveSessionCodec() {
        var primitive = new Transformation("cancel", "1", RewriteKind.SIMPLIFY, false, -2, true,
            "cancel-at-root", List.of("x != 0"));
        var execution = RecordedExecution.capture("x/x", List.of(primitive));
        var step = new TransformationStep(0, "x/x", "1", "cancel", RewriteKind.SIMPLIFY, 3, 1, true,
            "nonzero cancellation", primitive.assumptions(), execution);
        var macro = new MacroMoveExpansion("macro", "x/x", "1", List.of(step), List.of("source-path"),
            primitive.assumptions(), 2, true, new MacroMoveStatistics(5, 4, 3, 2, List.of("SIMPLIFY")));
        var edge = new SearchGraphEdgeDto("x/x", "1", "macro", RewriteKind.SIMPLIFY, -2,
            primitive.assumptions(), List.of("p"), true, macro);
        var record = new SearchGraphRecord("macro-session", Instant.EPOCH, "PRIMITIVE", List.of(),
            new SearchGraphDto(List.of(), List.of(edge), List.of(), null), List.of(), List.of(), List.of(), Map.of());
        var loaded = SearchGraphRecordCodec.fromJson(SearchGraphRecordCodec.toJson(record));
        assertEquals(macro, loaded.graph().edges().getFirst().macroMoveExpansion());
    }

    @Test void displayDtosCannotSubstituteStepIdentityOrEraseAssumptions() {
        var primitive = new Transformation("cancel", "1", RewriteKind.SIMPLIFY, false, -2, true,
            "cancel-at-root", List.of("x != 0"));
        var execution = RecordedExecution.capture("x/x", List.of(primitive));
        assertThrows(IllegalArgumentException.class, () -> new TransformationStepDto(0, "x/x", "1", "cancel",
            "SIMPLIFY", 3, 1, true, "display", List.of(), execution));
        assertThrows(IllegalArgumentException.class, () -> new PathReplayDto.ReplayStep(0, "x/x", "x/x", "1", "1",
            "substituted", "display", 2, true, false, List.of(), List.of(), null, execution));
        assertThrows(IllegalArgumentException.class, () -> new PathReplayDto.ReplayStep(0, "x/x", "x/x", "1", "1",
            "cancel", "display", 2, false, false, List.of(), List.of(), null, execution));
    }

    private static DiscoveredTransformation path(VerifiedCandidateEvidence evidence) {
        var transformation = Transformation.exactTheory(ExactTheoryEvidence.fromVerified(evidence));
        String source = evidence.data().sourceExpression(), output = evidence.data().transformedExpression();
        var before = SCORER.score(source); var after = SCORER.score(output);
        var step = new TransformationStep(0, source, output, transformation.rule(), transformation.kind(),
            before.weightedTotal(), after.weightedTotal(), true, "verified exact theory", List.of(),
            RecordedExecution.capture(source, List.of(transformation)));
        return new DiscoveredTransformation(evidence.evidenceHash(), source, output, List.of(step), before, after,
            before.weightedTotal() - after.weightedTotal(), CandidateProofStatus.OBSERVED, Instant.EPOCH,
            new ExpressionCanonicalizer().stableHash(output));
    }

    private static GraphEdge edge(DiscoveredTransformation path) {
        var step = path.steps().getFirst();
        return new GraphEdge(step.beforeExpression(), step.afterExpression(), step.ruleId(), 1,
            path.totalImprovement(), path.id(), path.canonicalHash(), step.scoreBefore(), step.scoreAfter(), step.ruleKind(),
            true, 0, true, CandidateProofStatus.OBSERVED, null, step.execution());
    }

    private static WorkBudgetBestFirstSearchStrategy.Problem problem(VerifiedCandidateEvidence evidence) {
        return new WorkBudgetBestFirstSearchStrategy.Problem(evidence.data().sourceExpression(), evidence.data().transformedExpression(),
            new SearchExpansionSource.Program(RewritePrograms.source("verified", new VerifiedFinitePolynomialTransformationEngine(evidence))),
            SCORER, new ExpressionCanonicalizer(), new WorkBudgetBestFirstSearchStrategy.Budget(
                0, evidence.data().canonicalWork().totalWorkUnits(), 10, 10, 10, 100_000));
    }

    /** Independent solver, receipt-byte verification, complete replay confirmation and evidence issuance. */
    private static VerifiedCandidateEvidence evidence(String id) {
        String source = "x*x", ansatz = "(${unit}*x)^2";
        var domains = List.of(HoleDomain.integerRange("unit", 1, 1));
        var resolver = new ExactFinitePolynomialPlanResolver();
        var plan = resolver.createPlan(id, source, ansatz, domains, 1, new SchematicProofPlan.Limits(8, 8, 4, 200_000));
        var run = resolver.resolve(plan, source, ansatz, domains, 1);
        var receipt = new ExactFinitePolynomialPlanReplayVerifier().verify(plan, source, ansatz, domains, 1, run);
        var bytes = new ExactFinitePolynomialPlanReplayArtifactVerifier();
        var receiptRef = bytes.describeReceipt(receipt); var runRef = bytes.describePlanRun(run);
        var checkedReceipt = bytes.verifyReceipt(receiptRef,
            ignored -> new LoadedArtifact(receiptRef.artifactId(), receipt.toCanonicalJson().getBytes(StandardCharsets.UTF_8)));
        var checkedRun = bytes.verifyPlanRun(runRef,
            ignored -> new LoadedArtifact(runRef.artifactId(), run.toCanonicalJson().getBytes(StandardCharsets.UTF_8)));
        var confirmed = new ExactFinitePolynomialPlanReplayConfirmationVerifier().verify(
            new ExactFinitePolynomialPlanReplayReceiptArtifactVerifier().verify(checkedReceipt), checkedRun,
            run, plan, source, ansatz, domains, 1);
        return new ExactFinitePolynomialPlanCandidateEvidenceVerifier().verify(confirmed, plan, run, run.candidates().getFirst().contentHash());
    }
}
