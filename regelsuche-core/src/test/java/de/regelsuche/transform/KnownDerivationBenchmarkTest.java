package de.regelsuche.transform;

import static de.regelsuche.ast.BinaryOperator.ADD;
import static de.regelsuche.ast.BinaryOperator.MUL;
import static de.regelsuche.ast.BinaryOperator.POW;
import static de.regelsuche.ast.BinaryOperator.SUB;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Small deterministic benchmark corpus for known algebraic derivations.
 *
 * <p>The same curated rule inventory is evaluated with exact, AC and algebraic
 * AC recognition. This makes recognition improvements visible as additional
 * reachable derivations without changing the search algorithm or target set.</p>
 */
class KnownDerivationBenchmarkTest {
    private static final int MAX_VISITED_STATES = 1_000;
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void broaderRecognitionStrictlyIncreasesKnownDerivationCoverage() {
        List<Case> corpus = corpus();
        Map<Profile, Summary> summaries = new EnumMap<>(Profile.class);
        for (Profile profile : Profile.values()) {
            summaries.put(profile, runCorpus(corpus, profile.recognitionProfile));
        }

        Summary exact = summaries.get(Profile.EXACT);
        Summary ac = summaries.get(Profile.AC);
        Summary algebraic = summaries.get(Profile.ALGEBRAIC_AC);

        assertEquals(5, exact.solved(), exact.describe());
        assertEquals(7, ac.solved(), ac.describe());
        assertEquals(9, algebraic.solved(), algebraic.describe());
        assertTrue(exact.solved() < ac.solved(), summaries.toString());
        assertTrue(ac.solved() < algebraic.solved(), summaries.toString());

        assertCase(exact, "reordered-square", false);
        assertCase(ac, "reordered-square", true);
        assertCase(ac, "scaled-square", false);
        assertCase(algebraic, "scaled-square", true);
        assertCase(algebraic, "inconsistent-near-miss", false);
    }

    private Summary runCorpus(List<Case> corpus, RecognitionProfile profile) {
        List<Result> results = new ArrayList<>();
        for (Case benchmarkCase : corpus) {
            Derivation derivation = derive(
                benchmarkCase.source(),
                benchmarkCase.target(),
                profile,
                benchmarkCase.maxDepth()
            );
            results.add(new Result(
                benchmarkCase.id(),
                derivation.found(),
                derivation.rules().size(),
                derivation.visitedStates()
            ));
        }
        return new Summary(results);
    }

    private List<Case> corpus() {
        return List.of(
            new Case("complete-square", "x*x + 2*x*y + y*y", "(x+y)^2", 3),
            new Case("reordered-square", "x*x + y*y + 2*y*x", "(x+y)^2", 3),
            new Case("regrouped-square", "y*y + (2*x)*y + x*x", "(x+y)^2", 3),
            new Case("scaled-square", "x*x + 3*x*a + 2.25*a*a", "(x+1.5*a)^2", 3),
            new Case("fractional-square", "x*x + (4/3)*x*y + (4/9)*y*y", "(x+(2/3)*y)^2", 3),
            new Case("difference-of-squares", "a*a - b*b", "(a-b)*(a+b)", 3),
            new Case("difference-of-squares-powers", "m^2 - n^2", "(m-n)*(m+n)", 1),
            new Case("factor-common-left", "a*x + a*y", "a*(x+y)", 1),
            new Case("factor-common-right", "x*a + y*a", "(x+y)*a", 1),
            new Case("inconsistent-near-miss", "x*x + 3*x*a + a*a", "(x+1.5*a)^2", 3)
        );
    }

    private Derivation derive(String source, String target, RecognitionProfile profile, int maxDepth) {
        AstRewriteTransformationEngine engine = new AstRewriteTransformationEngine(curatedRules(profile), 12, 100);
        String normalizedSource = format(source);
        String normalizedTarget = format(target);
        ArrayDeque<SearchNode> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(new SearchNode(normalizedSource, List.of()));
        visited.add(normalizedSource);

        while (!queue.isEmpty() && visited.size() <= MAX_VISITED_STATES) {
            SearchNode current = queue.removeFirst();
            if (current.expression().equals(normalizedTarget)) {
                return new Derivation(true, current.rules(), visited.size());
            }
            if (current.rules().size() >= maxDepth) {
                continue;
            }
            for (Transformation transformation : engine.transform(current.expression())) {
                String next = transformation.transformedExpression();
                if (!visited.add(next)) {
                    continue;
                }
                List<String> rules = new ArrayList<>(current.rules());
                rules.add(transformation.rule());
                queue.addLast(new SearchNode(next, List.copyOf(rules)));
            }
        }
        return new Derivation(false, List.of(), visited.size());
    }

    private List<RewriteRule> curatedRules(RecognitionProfile profile) {
        PatternExpr value = PatternExpr.var("V");
        PatternExpr a = PatternExpr.var("A");
        PatternExpr b = PatternExpr.var("B");
        PatternExpr x = PatternExpr.var("X");
        PatternExpr y = PatternExpr.var("Y");
        return List.of(
            new PatternRewriteRule(
                "product-to-square",
                PatternExpr.op(MUL, value, value),
                PatternExpr.op(POW, value, PatternExpr.num(2))
            ),
            completeSquare(profile),
            new PatternRewriteRule(
                "difference-of-squares",
                PatternExpr.op(SUB,
                    PatternExpr.op(POW, a, PatternExpr.num(2)),
                    PatternExpr.op(POW, b, PatternExpr.num(2))),
                PatternExpr.op(MUL,
                    PatternExpr.op(SUB, a, b),
                    PatternExpr.op(ADD, a, b))
            ),
            new PatternRewriteRule(
                "factor-common-left",
                PatternExpr.op(ADD,
                    PatternExpr.op(MUL, a, x),
                    PatternExpr.op(MUL, a, y)),
                PatternExpr.op(MUL, a, PatternExpr.op(ADD, x, y))
            ),
            new PatternRewriteRule(
                "factor-common-right",
                PatternExpr.op(ADD,
                    PatternExpr.op(MUL, x, a),
                    PatternExpr.op(MUL, y, a)),
                PatternExpr.op(MUL, PatternExpr.op(ADD, x, y), a)
            )
        );
    }

    private PatternRewriteRule completeSquare(RecognitionProfile profile) {
        PatternExpr x = PatternExpr.var("X");
        PatternExpr a = PatternExpr.var("A");
        PatternExpr source = PatternExpr.op(
            ADD,
            PatternExpr.op(
                ADD,
                PatternExpr.op(POW, x, PatternExpr.num(2)),
                PatternExpr.op(MUL, PatternExpr.op(MUL, PatternExpr.num(2), x), a)
            ),
            PatternExpr.op(POW, a, PatternExpr.num(2))
        );
        PatternExpr target = PatternExpr.op(
            POW,
            PatternExpr.op(ADD, x, a),
            PatternExpr.num(2)
        );
        return new PatternRewriteRule("complete-square", source, target, profile);
    }

    private void assertCase(Summary summary, String id, boolean expected) {
        Result result = summary.results().stream()
            .filter(candidate -> candidate.id().equals(id))
            .findFirst()
            .orElseThrow();
        if (expected) {
            assertTrue(result.solved(), summary.describe());
        } else {
            assertFalse(result.solved(), summary.describe());
        }
    }

    private String format(String expression) {
        return ExpressionFormatter.format(parser.parseTerm(expression));
    }

    private enum Profile {
        EXACT(RecognitionProfile.exact()),
        AC(RecognitionProfile.arithmeticAc()),
        ALGEBRAIC_AC(RecognitionProfile.algebraicAc());

        private final RecognitionProfile recognitionProfile;

        Profile(RecognitionProfile recognitionProfile) {
            this.recognitionProfile = recognitionProfile;
        }
    }

    private record Case(String id, String source, String target, int maxDepth) {
    }

    private record SearchNode(String expression, List<String> rules) {
    }

    private record Derivation(boolean found, List<String> rules, int visitedStates) {
    }

    private record Result(String id, boolean solved, int pathLength, int visitedStates) {
    }

    private record Summary(List<Result> results) {
        private int solved() {
            return (int) results.stream().filter(Result::solved).count();
        }

        private String describe() {
            return "solved=" + solved() + "/" + results.size() + ", results=" + results;
        }
    }
}
