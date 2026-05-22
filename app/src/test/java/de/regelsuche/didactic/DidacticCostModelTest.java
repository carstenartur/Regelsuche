package de.regelsuche.didactic;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.scoring.cost.CostModel;
import org.junit.jupiter.api.Test;

class DidacticCostModelTest {

    private final ExpressionScorer scorer = new ExpressionScorer();
    private final ExpressionCanonicalizer canon = new ExpressionCanonicalizer();

    @Test
    void didacticCostModelPrefersSimpleSchoolPath() {
        // Spec example (item 1): the school-book result `x = 4` should be
        // strictly preferred over the algebraically equivalent but
        // didactically denser `x - 4 = 0` (which expects the student to
        // re-shuffle terms again to read the solution off).
        CostModel model = new DidacticCostModel(DifficultyLevel.MITTELSTUFE,
            PedagogyProfile.SCHOOL);

        // Single-side school form vs. moved-to-zero form
        assertBetter(model, "x", "x - 4");
        assertBetter(model, "x - 4", "x + 3 - 7");

        // Small integer coefficients beat huge ones
        assertBetter(model, "(x + 3) * (x + 5)", "x^2 + 8*x + 15000");

        // Shallow form beats deeply nested form
        assertBetter(model, "x + 1", "((x + 1) + 0) + 0");
    }

    @Test
    void difficultyLevelAffectsCostMonotonically() {
        // A deeply nested expression should be penalised more strictly
        // for a beginner than for a researcher.
        String deep = "((((x + 1) + 2) + 3) + 4)";
        ExpressionScore score = scorer.score(deep);

        int beginner = new DidacticCostModel(DifficultyLevel.GRUNDSCHULE,
            PedagogyProfile.SCHOOL).cost(deep, canon, score);
        int expert = new DidacticCostModel(DifficultyLevel.EXPERTE,
            PedagogyProfile.SCHOOL).cost(deep, canon, score);

        assertTrue(beginner > expert,
            () -> "GRUNDSCHULE cost (" + beginner + ") must exceed EXPERTE cost (" + expert + ")");
    }

    @Test
    void idIsStable() {
        // The UI dropdown and JSON exports key off this id.
        assertNotNull(new DidacticCostModel().id());
        assertTrue(new DidacticCostModel().id().equals("didactic"));
    }

    private void assertBetter(CostModel model, String better, String worse) {
        int b = model.cost(better, canon, scorer.score(better));
        int w = model.cost(worse, canon, scorer.score(worse));
        assertTrue(b < w, () -> String.format(
            "expected '%s' (cost=%d) to be strictly better than '%s' (cost=%d)",
            better, b, worse, w));
    }
}
