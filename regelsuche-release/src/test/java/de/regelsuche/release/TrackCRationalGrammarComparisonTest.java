package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.math.algorithms.equivalence.RationalAssumptionRewriteAdapter;
import de.regelsuche.math.algorithms.equivalence.RationalAssumptionRewriteAdapter.EvaluationTask;
import de.regelsuche.math.algorithms.equivalence.RationalAssumptionRewriteAdapter.FormationResult;
import de.regelsuche.math.algorithms.equivalence.RationalAssumptionRewriteAdapter.FormationSeed;
import de.regelsuche.math.algorithms.equivalence.RationalAssumptionRewriteAdapter.FormationStatus;
import de.regelsuche.math.algorithms.equivalence.RationalAssumptionRewriteAdapter.ResourceBudget;
import de.regelsuche.math.algorithms.equivalence.RationalAssumptionRewriteAdapter.SearchStatus;
import de.regelsuche.transform.RationalNormalizationHypothesisOperator.Forms;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrackCRationalGrammarComparisonTest {
    private static final ResourceBudget BUDGET = new ResourceBudget(5, 60, 60);
    @TempDir Path directory;

    @Test
    void bothRestrictedOperatorsRequireCoveredGuardsAndStrictSavings() {
        var cancel = new RationalAssumptionRewriteAdapter(Forms.CANCELLATION);
        var together = new RationalAssumptionRewriteAdapter(Forms.EQUAL_DENOMINATOR);
        var full = new RationalAssumptionRewriteAdapter();
        List<String> guarded = List.of("z != 0");
        for (String expression : List.of("a/z+b/z", "a/z-b/z")) {
            var selected = form(together, expression, guarded);
            assertEquals(FormationStatus.SELECTED, selected.status());
            var witness = selected.evidence().getFirst();
            assertEquals(guarded, witness.candidateAssumptions());
            assertTrue(witness.inputAstNodes() > witness.selectedAstNodes());
            assertEquals(FormationStatus.NO_CANDIDATE, form(together, expression, List.of()).status());
            assertEquals(FormationStatus.NO_CANDIDATE, form(cancel, expression, guarded).status());
            assertEquals(FormationStatus.SELECTED, form(full, expression, guarded).status());
            var task = new EvaluationTask("addition-or-subtraction", expression,
                expression.contains("+") ? "(a+b)/z" : "(a-b)/z", guarded);
            assertEquals(SearchStatus.REACHED_AND_CONFIRMED, together.evaluate(task, selected, BUDGET).status());
            assertThrows(IllegalArgumentException.class, () -> full.evaluate(task, selected, BUDGET));
        }
        String quotient = "(a*z)/z";
        assertEquals(FormationStatus.SELECTED, form(cancel, quotient, guarded).status());
        assertEquals(FormationStatus.NO_CANDIDATE, form(cancel, quotient, List.of()).status());
        assertEquals(FormationStatus.NO_CANDIDATE, form(together, quotient, guarded).status());
        assertEquals(FormationStatus.NO_CANDIDATE, form(together, "a/z+b/w", List.of("z != 0", "w != 0")).status());
    }

    @Test
    void emptyFormationDoesZeroEvaluationWork() {
        var together = new RationalAssumptionRewriteAdapter(Forms.EQUAL_DENOMINATOR);
        var empty = form(together, "(a*z)/z", List.of("z != 0"));
        var result = together.evaluate(new EvaluationTask("empty", "a/z+b/z", "(a+b)/z", List.of("z != 0")),
            empty, BUDGET);
        assertEquals(SearchStatus.CANDIDATE_NOT_FORMED, result.status());
        assertEquals(0, result.resourceUse().executedExploredStates());
        assertEquals(0, result.resourceUse().executedCandidateEvaluations());
    }

    @Test
    void changingEveryEvaluationAndHeldOutSeedDoesNotAffectTheFormationProjection() throws Exception {
        var json = new ObjectMapper();
        var corpus = (ObjectNode) json.readTree(data().resolve("case-corpus.json").toFile());
        var expected = TrackCRationalGrammarComparison.projectTrain(corpus);
        for (var item : corpus.required("cases")) {
            var row = (ObjectNode) item;
            row.put("evaluationInput", "poison");
            row.put("caseId", "poison");
            if (!row.required("split").asText().equals("TRAIN")) {
                row.put("formationInput", "poison");
            }
        }
        assertEquals(expected, TrackCRationalGrammarComparison.projectTrain(corpus));
    }

    @Test
    void reproducesAllProspectivelyFrozenRowsByteForByte() throws Exception {
        Path output = directory.resolve("replay.json");
        TrackCRationalGrammarComparison.main(new String[]{data().toString(), output.toString()});
        try (var input = new GZIPInputStream(Files.newInputStream(
                data().resolve("track-c-rational-grammar/comparison-result.json.gz")))) {
            assertArrayEquals(input.readAllBytes(), Files.readAllBytes(output));
        }
    }

    private static FormationResult form(RationalAssumptionRewriteAdapter adapter, String expression, List<String> assumptions) {
        return adapter.formCandidate(List.of(new FormationSeed("seed", expression, assumptions, "seed")), BUDGET);
    }

    private static Path data() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            Path data = p.resolve("research/benchmarks/candidate-independent");
            if (Files.isDirectory(data)) return data;
        }
        throw new IllegalStateException("public frozen corpus is missing");
    }
}
