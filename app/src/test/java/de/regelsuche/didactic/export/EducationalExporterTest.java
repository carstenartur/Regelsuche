package de.regelsuche.didactic.export;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class EducationalExporterTest {

    private final EducationalExporter exporter = new EducationalExporter();

    @Test
    void worksheetContainsPromptButNotSolution() {
        String md = exporter.worksheet(sampleDerivation());
        assertTrue(md.contains("# Arbeitsblatt"), md);
        assertTrue(md.contains("a*(b + c)"), md);
        // The worksheet must not reveal the result expression.
        assertFalse(md.contains("a*b + a*c"), md);
        assertTrue(md.contains("________"), "must leave blank lines");
    }

    @Test
    void solutionContainsEveryStep() {
        String md = exporter.solution(sampleDerivation());
        assertTrue(md.contains("# Musterlösung"), md);
        assertTrue(md.contains("a*(b + c)"), md);
        assertTrue(md.contains("a*b + a*c"), md);
        assertTrue(md.contains("Distributivgesetz angewandt"), md);
    }

    @Test
    void teacherModeContainsDiffMarkers() {
        String md = exporter.teacherMode(sampleDerivation());
        assertTrue(md.contains("# Lehrermodus"), md);
        // The diff for `a*(b + c)` → `a*b + a*c` adds an `a` and a `*`,
        // and removes the parentheses — markdown bold/strikethrough must
        // appear in the diff line.
        assertTrue(md.contains("**") || md.contains("~~"),
            "teacher mode must mark token-level diff with markdown");
        assertTrue(md.contains("Symbol-Diff"), md);
    }

    private static DiscoveredTransformation sampleDerivation() {
        TransformationStep step = new TransformationStep(
            0,
            "a*(b + c)",
            "a*b + a*c",
            "ast_distribute_left_add",
            RewriteKind.EXPAND,
            10, 12, true,
            "Distributivgesetz angewandt");
        return new DiscoveredTransformation(
            "sample-derivation-id",
            "a*(b + c)",
            "a*b + a*c",
            List.of(step),
            new ExpressionScore(8, 5, 2, 2, 0),
            new ExpressionScore(10, 7, 3, 2, 0),
            -2,
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            Instant.EPOCH,
            "hash");
    }
}
