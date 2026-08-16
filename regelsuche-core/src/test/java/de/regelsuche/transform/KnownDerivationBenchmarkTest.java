package de.regelsuche.transform;

import static de.regelsuche.ast.BinaryOperator.ADD;
import static de.regelsuche.ast.BinaryOperator.MUL;
import static de.regelsuche.ast.BinaryOperator.POW;
import static de.regelsuche.ast.BinaryOperator.SUB;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.json.JsonReader;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private static final String CORPUS_RESOURCE =
        "/de/regelsuche/transform/known-derivation-corpus.json";
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

        // Broader recognition must never lose a derivation that a narrower
        // profile found with the same rule inventory and search budget.
        assertSolvedSuperset(ac, exact);
        assertSolvedSuperset(algebraic, ac);

        // The benchmark contains explicit witnesses for both capability gains.
        assertCase(exact, "reordered-square", false);
        assertCase(ac, "reordered-square", true);
        assertCase(ac, "scaled-square", false);
        assertCase(algebraic, "scaled-square", true);

        // These witnesses also guarantee strict aggregate improvement without
        // coupling the gate to guessed totals that may improve legitimately.
        assertTrue(exact.solved() < ac.solved(), summaries.toString());
        assertTrue(ac.solved() < algebraic.solved(), summaries.toString());

        // Broader recognition must not turn a mathematically inconsistent near
        // miss into a derivation.
        assertCase(exact, "inconsistent-near-miss", false);
        assertCase(ac, "inconsistent-near-miss", false);
        assertCase(algebraic, "inconsistent-near-miss", false);
    }

    @Test
    void visitedStateBudgetIsStrict() {
        Derivation derivation = derive(
            "x*x + 2*x*y + y*y",
            "(x+y)^2",
            RecognitionProfile.exact(),
            3,
            1
        );

        assertFalse(derivation.found());
        assertEquals(1, derivation.visitedStates());
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
        try (InputStream input = KnownDerivationBenchmarkTest.class
            .getResourceAsStream(CORPUS_RESOURCE)) {
            assertNotNull(input, "missing " + CORPUS_RESOURCE);
            Map<String, Object> root = new JsonReader(
                new String(input.readAllBytes(), StandardCharsets.UTF_8)).readObject();
            assertEquals(
                Set.of("schema", "evidenceStatus", "claimBoundary", "cases"),
                root.keySet());
            assertEquals(
                "regelsuche.known-derivation-corpus/v1",
                requiredString(root, "schema"));
            assertEquals("DEVELOPMENT_FIXTURE", requiredString(root, "evidenceStatus"));
            requiredString(root, "claimBoundary");

            List<?> rawCases = assertInstanceOf(
                List.class,
                root.get("cases"),
                "cases must be a JSON array");
            Set<String> ids = new LinkedHashSet<>();
            List<Case> cases = new ArrayList<>();
            for (Object rawCase : rawCases) {
                Map<?, ?> rawValues = assertInstanceOf(
                    Map.class,
                    rawCase,
                    "each benchmark case must be a JSON object");
                Map<String, Object> values = stringKeyedMap(rawValues);
                assertEquals(
                    Set.of("id", "source", "target", "maxDepth", "relation",
                        "provenance", "control"),
                    values.keySet());
                String id = requiredString(values, "id");
                assertTrue(ids.add(id), "duplicate benchmark case " + id);
                String relation = requiredString(values, "relation");
                String control = requiredString(values, "control");
                assertTrue(Set.of("EQUIVALENT", "NOT_EQUIVALENT").contains(relation), id);
                assertTrue(Set.of("POSITIVE", "NEGATIVE").contains(control), id);
                if (control.equals("NEGATIVE")) {
                    assertEquals("NOT_EQUIVALENT", relation, id);
                }
                Number maxDepth = assertInstanceOf(
                    Number.class,
                    values.get("maxDepth"),
                    "maxDepth must be numeric in " + values);
                assertTrue(maxDepth.intValue() >= 0, "negative maxDepth in " + values);
                cases.add(new Case(
                    id,
                    requiredString(values, "source"),
                    requiredString(values, "target"),
                    maxDepth.intValue(),
                    relation,
                    requiredString(values, "provenance"),
                    control
                ));
            }
            assertFalse(cases.isEmpty());
            return List.copyOf(cases);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read " + CORPUS_RESOURCE, exception);
        }
    }

    private Map<String, Object> stringKeyedMap(Map<?, ?> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(
            assertInstanceOf(String.class, key, "case object keys must be strings"),
            value));
        return result;
    }

    private String requiredString(Map<String, Object> values, String key) {
        String value = assertInstanceOf(
            String.class,
            values.get(key),
            "missing or non-string " + key + " in " + values);
        assertFalse(value.isBlank(), "blank " + key + " in " + values);
        return value;
    }

    private Derivation derive(
        String source,
        String target,
        RecognitionProfile profile,
        int maxDepth
    ) {
        return derive(source, target, profile, maxDepth, MAX_VISITED_STATES);
    }

    private Derivation derive(
        String source,
        String target,
        RecognitionProfile profile,
        int maxDepth,
        int maxVisitedStates
    ) {
        AstRewriteTransformationEngine engine = new AstRewriteTransformationEngine(
            curatedRules(profile), 12, 100);
        String normalizedSource = format(source);
        String normalizedTarget = format(target);
        ArrayDeque<SearchNode> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(new SearchNode(normalizedSource, List.of()));
        visited.add(normalizedSource);

        while (!queue.isEmpty()) {
            SearchNode current = queue.removeFirst();
            if (current.expression().equals(normalizedTarget)) {
                return new Derivation(true, current.rules(), visited.size());
            }
            if (current.rules().size() >= maxDepth) {
                continue;
            }
            for (Transformation transformation : engine.transform(current.expression())) {
                String next = transformation.transformedExpression();
                if (visited.contains(next)
                        || visited.size() >= maxVisitedStates) {
                    continue;
                }
                visited.add(next);
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

    private void assertSolvedSuperset(Summary broader, Summary narrower) {
        for (Result narrowResult : narrower.results()) {
            if (!narrowResult.solved()) {
                continue;
            }
            Result broadResult = broader.result(narrowResult.id());
            assertTrue(
                broadResult.solved(),
                "Broader recognition lost derivation " + narrowResult.id()
                    + "; narrower=" + narrower.describe()
                    + "; broader=" + broader.describe()
            );
        }
    }

    private void assertCase(Summary summary, String id, boolean expected) {
        Result result = summary.result(id);
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

    private record Case(
        String id,
        String source,
        String target,
        int maxDepth,
        String relation,
        String provenance,
        String control
    ) {
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

        private Result result(String id) {
            return results.stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow();
        }

        private String describe() {
            return "solved=" + solved() + "/" + results.size() + ", results=" + results;
        }
    }
}
