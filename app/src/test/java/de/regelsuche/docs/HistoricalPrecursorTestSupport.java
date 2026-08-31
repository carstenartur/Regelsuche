package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.docs.HiddenRulePilotRunner.CandidateSnapshot;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeResult;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeTask;
import de.regelsuche.evolution.ExactPolynomialPatternVerificationService;
import de.regelsuche.mining.DynamicOperatorCompiler;
import de.regelsuche.mining.DynamicPatternOperator;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.HypothesisOperator;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.SumOfSquaresCompletionOperator;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;

final class HistoricalPrecursorTestSupport {
    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();
    private final ExpressionScorer scorer = new ExpressionScorer();
    private final ExactPolynomialPatternVerificationService exactVerifier =
        new ExactPolynomialPatternVerificationService();

    ExactPolynomialPatternVerificationService exactVerifier() {
        return exactVerifier;
    }

    RuntimeTask completionTask() {
        SumOfSquaresCompletionOperator operator =
            new SumOfSquaresCompletionOperator();
        String target = operator.generateCandidates("p^2 + q^2")
            .getFirst()
            .transformedExpression();
        return new RuntimeTask(
            "precursor-complete-visible-square-sum",
            "p^2 + q^2",
            syntaxTarget(target),
            engine(List.of(operator)),
            new SearchHeuristic(1, 48, 1, 4, 8, 16),
            List.of(),
            List.of());
    }

    RuntimeTask differenceTask() {
        RewriteRule rule = AstRewriteTransformationEngine.allBuiltInRules()
            .stream()
            .filter(candidate -> "ast_square_difference_factor".equals(
                candidate.id()))
            .findFirst()
            .orElseThrow();
        return new RuntimeTask(
            "precursor-factor-visible-square-difference",
            "r^2 - s^2",
            syntaxTarget("(r - s) * (r + s)"),
            new AstRewriteTransformationEngine(List.of(rule)),
            new SearchHeuristic(1, 48, 1, 4, 8, 16),
            List.of(),
            List.of());
    }

    FrozenRule freeze(RuntimeTask task) {
        RuntimeResult result = new HiddenRulePilotRunner().run(task);
        assertTrue(result.frozen(), result.toString());
        assertTrue(result.validationEvidence().passed(), result.toString());
        assertTrue(result.holdouts().allPassed(), result.toString());
        CandidateSnapshot candidate = result.candidate();
        assertNotNull(candidate, result.toString());
        var identity = exactVerifier.verify(
            candidate.leftPattern(),
            candidate.rightPattern());
        assertTrue(identity.proved(), identity.toString());

        DynamicOperatorCompiler.CompilationResult compilation =
            new DynamicOperatorCompiler().compile(
                "pilot-" + task.opaqueCaseId(),
                "frozen-v1",
                candidate.leftPattern(),
                candidate.rightPattern());
        assertTrue(compilation.isSuccess(), compilation.rejectionReason());
        DynamicPatternOperator operator = compilation.operator().orElseThrow();
        assertEquals(candidate.dynamicRuleId(), operator.ruleId());
        assertEquals(candidate.provenanceHash(), operator.provenanceHash());
        return new FrozenRule(candidate, operator);
    }

    Transformation onlyApplication(
        HypothesisOperator operator,
        String source
    ) {
        List<Transformation> applications = operator.generateCandidates(source);
        assertEquals(1, applications.size(),
            () -> source + " -> " + applications);
        return applications.getFirst();
    }

    String requireExactMove(
        HypothesisOperator operator,
        String source,
        String expected
    ) {
        var expectedAst = parser.parseTerm(expected);
        return operator.generateCandidates(source).stream()
            .map(Transformation::transformedExpression)
            .filter(candidate -> parser.parseTerm(candidate).equals(expectedAst))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                expected + " not generated from " + source));
    }

    TransformationEngine engine(List<HypothesisOperator> operators) {
        TransformationEngine empty = ignored -> List.of();
        return new HypothesisTransformationEngine(empty, operators, 96);
    }

    GoalSearchResult search(
        TransformationEngine engine,
        SearchHeuristic heuristic,
        String source,
        String target
    ) {
        SearchProblem problem = new SearchProblem(
            source,
            engine,
            scorer,
            canonicalizer,
            heuristic).withTarget(syntaxTarget(target));
        return new BestFirstSearchStrategy().searchWithDiagnostics(problem);
    }

    SearchTarget syntaxTarget(String expression) {
        return SearchTarget.syntaxExact(
            ExpressionFormatter.format(parser.parseTerm(expression)));
    }

    record FrozenRule(
        CandidateSnapshot candidate,
        DynamicPatternOperator operator
    ) {
    }
}
