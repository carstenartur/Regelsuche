package de.regelsuche.export;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.api.searchgraph.*;
import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.mining.MacroMoveExpansion;
import de.regelsuche.mining.MacroMoveStatistics;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.validation.CandidateProofStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Uses pre-migration APIs too, so both losses can be reproduced on the unchanged base. */
class StoredPathRoundTripRegressionTest {
    @Test void discoveryExportMustNotLoseStepAssumptions() {
        var scorer = new ExpressionScorer();
        var step = new TransformationStep(0, "x/x", "1", "cancel", RewriteKind.SIMPLIFY,
            3, 1, true, "requires nonzero x", List.of("x != 0"));
        var path = new DiscoveredTransformation("p", "x/x", "1", List.of(step), scorer.score("x/x"),
            scorer.score("1"), 2, CandidateProofStatus.OBSERVED, Instant.EPOCH, "canonical");
        var exported = new DefaultTransformationExportService().exportJson(List.of(path), List.of());
        var imported = new DefaultTransformationImportService().importJson(exported).transformations().getFirst();
        assertEquals(step.assumptions(), imported.steps().getFirst().assumptions());
    }

    @Test void graphSessionMustNotDiscardMacroExpansionOnReload() {
        var step = new TransformationStep(0, "x/x", "1", "cancel", RewriteKind.SIMPLIFY,
            3, 1, true, "requires nonzero x", List.of("x != 0"));
        var macro = new MacroMoveExpansion("macro", "x/x", "1", List.of(step), List.of("training-p"),
            List.of("x != 0"), 2, true, new MacroMoveStatistics(5, 4, 3, 2, List.of("SIMPLIFY")));
        var edge = new SearchGraphEdgeDto("x/x", "1", "macro", RewriteKind.SIMPLIFY, -2,
            List.of("x != 0"), List.of("p"), true, macro);
        var record = new SearchGraphRecord("session", Instant.EPOCH, "test", List.of(),
            new SearchGraphDto(List.of(), List.of(edge), List.of(), null), List.of(), List.of(), List.of(), Map.of());
        var loaded = SearchGraphRecordCodec.fromJson(SearchGraphRecordCodec.toJson(record));
        assertEquals(macro, loaded.graph().edges().getFirst().macroMoveExpansion());
    }
}
