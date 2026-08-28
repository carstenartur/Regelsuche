package de.regelsuche.transform;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Optional transformation boundary for verified polynomial theory.
 *
 * <p>The default search inventory does not use this engine. Callers select an
 * explicit policy so that a cache hit is never confused with a mathematical
 * result, and both on-demand execution and replay expose the same application
 * identity.</p>
 */
public final class PolynomialTheoryTransformationEngine
        implements TransformationEngine {
    public enum Policy {
        NO_FACTORIZATION,
        ON_DEMAND_VERIFIED_FACTORIZATION,
        VERIFIED_DERIVED_MACRO_CACHE
    }

    private final TransformationEngine baseEngine;
    private final PolynomialDecompositionSynthesisOperator factorization;
    private final PolynomialTheorySubsumptionClassifier classifier;
    private final PolynomialDerivedMacroCache cache;
    private final Policy policy;
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();
    private final ExpressionParser parser = new ExpressionParser();

    public PolynomialTheoryTransformationEngine(
        TransformationEngine baseEngine,
        Policy policy
    ) {
        this(
            baseEngine,
            policy,
            new PolynomialDecompositionSynthesisOperator(Integer.MAX_VALUE),
            new PolynomialDerivedMacroCache());
    }

    public PolynomialTheoryTransformationEngine(
        TransformationEngine baseEngine,
        Policy policy,
        PolynomialDecompositionSynthesisOperator factorization,
        PolynomialDerivedMacroCache cache
    ) {
        this.baseEngine = Objects.requireNonNull(baseEngine, "baseEngine");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.factorization = Objects.requireNonNull(factorization, "factorization");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.classifier = new PolynomialTheorySubsumptionClassifier();
    }

    @Override
    public List<Transformation> transform(String expression) {
        List<Transformation> result = new ArrayList<>(baseEngine.transform(expression));
        if (policy == Policy.NO_FACTORIZATION || expression == null
                || expression.isBlank()) {
            return List.copyOf(result);
        }

        if (policy == Policy.VERIFIED_DERIVED_MACRO_CACHE) {
            String canonicalSource = canonicalSource(expression);
            for (PolynomialDerivedMacroCache.Entry entry : cache.entries()) {
                if (entry.leftPattern().equals(canonicalSource)) {
                    result.add(toTransformation(entry, entry.lineages().getFirst()));
                }
            }
            return List.copyOf(result);
        }

        ExpressionFactorizationReport report = factorization.factorExpression(expression);
        if (!report.generated()) {
            return List.copyOf(result);
        }
        for (ExpressionFactorizationReport.RenderedFactorization candidate
                : report.candidates()) {
            PolynomialTheorySubsumptionClassifier.Classification classification =
                classifier.classify(expression, candidate.transformedExpression());
            if (!classification.subsumed()) {
                continue;
            }
            PolynomialDerivedMacroCache.Entry entry = cache.retain(
                classification,
                List.of(PolynomialDecompositionSynthesisOperator.RULE_ID),
                List.of("transformation:" + classification.applicationKey()));
            result.add(toTransformation(entry, entry.lineages().getLast()));
        }
        return List.copyOf(result);
    }

    /**
     * Routes an observed mined or learned identity through the same verifier
     * boundary without changing the caller's original evidence.
     */
    public PolynomialTheorySubsumptionClassifier.Classification observe(
        String leftExpression,
        String rightExpression,
        List<String> primitiveRuleIds,
        List<String> sourceProvenance
    ) {
        PolynomialTheorySubsumptionClassifier.Classification classification =
            classifier.classify(leftExpression, rightExpression);
        if (classification.subsumed()) {
            cache.retain(classification, primitiveRuleIds, sourceProvenance);
        }
        return classification;
    }

    public PolynomialDerivedMacroCache cache() {
        return cache;
    }

    public Policy policy() {
        return policy;
    }

    private static Transformation toTransformation(
        PolynomialDerivedMacroCache.Entry entry,
        PolynomialDerivedMacroCache.Lineage lineage
    ) {
        return new Transformation(
            PolynomialDecompositionSynthesisOperator.RULE_ID,
            entry.rightPattern(),
            RewriteKind.FACTOR,
            true,
            -2,
            true,
            lineage.applicationKey(),
            List.of(),
            "derived-polynomial-macro-cache",
            "PROJECT",
            lineage.primitiveRuleIds());
    }

    private String canonicalSource(String expression) {
        return ExpressionFormatter.format(parser.parse(new InputRequest(
            InputType.TERM,
            canonicalizer.canonicalize(expression))).terms().getFirst());
    }
}
