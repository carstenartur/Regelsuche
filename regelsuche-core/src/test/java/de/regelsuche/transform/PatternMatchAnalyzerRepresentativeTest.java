package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PatternMatchAnalyzerRepresentativeTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void retainsBoundedRepresentativeRecognitionAsTheoryMatch() {
        RecognitionProfile profile = RecognitionProfile.exact()
            .withRecognitionRules(Set.of("test-representative"), 1);
        ExprMatcher.MatchOptions options =
            ExprMatcher.MatchOptions.defaults().withRepresentativeProvider(
                (expression, ignored) -> List.of(
                    expression,
                    parser.parseTerm("x")));

        PatternMatchAnalyzer.Analysis analysis =
            new PatternMatchAnalyzer().analyze(
                PatternExpr.variable("x"),
                parser.parseTerm("y"),
                profile,
                options);

        assertEquals(
            PatternMatchAnalyzer.Status.MATCH_MODULO_THEORY,
            analysis.status());
        assertTrue(analysis.matches().stream().anyMatch(match ->
            match.recognitionStrength()
                == ExprMatcher.RecognitionStrength.BOUNDED_REPRESENTATIVE));
        assertEquals("x", analysis.matches().getFirst().representative().toString());
    }
}
