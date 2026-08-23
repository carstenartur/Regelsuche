package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.ExactPolynomialDecompositionSynthesizer;
import de.regelsuche.polynomial.ExactPolynomialDecompositionSynthesizer.Candidate;
import de.regelsuche.polynomial.ExactPolynomialDecompositionSynthesizer.Result;
import de.regelsuche.polynomial.PolynomialSemanticView;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates exact polynomial factorisation edges from a semantic polynomial
 * view and bounded coefficient-template synthesis.
 */
public final class PolynomialStructureSynthesisOperator
        implements HypothesisOperator {
    public static final String RULE_ID =
        "hypothesis_polynomial_structure_synthesis";
    public static final String PACK_ID = "core-polynomial-synthesis";
    private static final int DEFAULT_MAX_CANDIDATES = 6;

    private final int maxCandidates;
    private final ExpressionParser parser = new ExpressionParser();
    private final PolynomialSemanticView semanticView =
        new PolynomialSemanticView();
    private final ExactPolynomialDecompositionSynthesizer synthesizer;

    public PolynomialStructureSynthesisOperator() {
        this(DEFAULT_MAX_CANDIDATES);
    }

    public PolynomialStructureSynthesisOperator(int maxCandidates) {
        this.maxCandidates = Math.max(0, maxCandidates);
        this.synthesizer = new ExactPolynomialDecompositionSynthesizer(
            new ExactPolynomialDecompositionSynthesizer.Budget(
                8,
                4,
                8,
                100_000,
                Math.max(1, this.maxCandidates)));
    }

    @Override
    public List<Transformation> generateCandidates(String expression) {
        if (maxCandidates == 0 || expression == null || expression.isBlank()) {
            return List.of();
        }
        Expr source;
        try {
            source = parser.parse(new InputRequest(InputType.TERM, expression))
                .terms().getFirst();
        } catch (IllegalArgumentException exception) {
            return List.of();
        }
        PolynomialSemanticView.Result analyzed = semanticView.analyze(source);
        if (!analyzed.supported()) {
            return List.of();
        }
        Result synthesis = synthesizer.synthesize(analyzed.view());
        if (!synthesis.synthesized()) {
            return List.of();
        }

        String sourceExpression = ExpressionFormatter.format(source);
        int sourceNodes = nodeCount(source);
        List<Transformation> transformations = new ArrayList<>();
        for (Candidate candidate : synthesis.candidates()) {
            String transformed = ExpressionFormatter.format(
                candidate.factoredExpression());
            if (transformed.equals(sourceExpression)) {
                continue;
            }
            int growth = nodeCount(candidate.factoredExpression()) - sourceNodes;
            String applicationKey = RULE_ID
                + "|algorithm="
                + ExactPolynomialDecompositionSynthesizer.ALGORITHM_ID
                + "|view=" + analyzed.view().semanticHash()
                + "|certificate=" + candidate.certificate().certificateHash()
                + "|templates=" + candidate.certificate().work().enumeratedTemplates()
                + "|divisions=" + candidate.certificate().work().exactDivisions();
            transformations.add(new Transformation(
                RULE_ID,
                transformed,
                RewriteKind.FACTOR,
                growth > 0,
                growth,
                true,
                applicationKey,
                List.of(),
                PACK_ID,
                "PROJECT",
                List.of(RULE_ID)));
            if (transformations.size() >= maxCandidates) {
                break;
            }
        }
        return List.copyOf(transformations);
    }

    private int nodeCount(Expr expression) {
        if (expression instanceof BinaryExpr binary) {
            return 1 + nodeCount(binary.left()) + nodeCount(binary.right());
        }
        if (expression instanceof FunctionExpr function) {
            return 1 + function.arguments().stream()
                .mapToInt(this::nodeCount)
                .sum();
        }
        return 1;
    }
}
