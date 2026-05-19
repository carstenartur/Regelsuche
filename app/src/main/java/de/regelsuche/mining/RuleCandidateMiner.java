package de.regelsuche.mining;

import de.regelsuche.algebra.QuadraticAnalyzer;
import de.regelsuche.algebra.QuadraticCoefficients;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class RuleCandidateMiner {
    private static final int MIN_EXAMPLES = 2;
    private final KnownRuleRepository knownRules;

    public RuleCandidateMiner(KnownRuleRepository knownRules) {
        this.knownRules = knownRules;
    }

    public List<RuleCandidate> mine(List<SuccessfulTransformationPath> paths) {
        Map<String, CandidateBucket> buckets = new LinkedHashMap<>();
        for (SuccessfulTransformationPath path : paths) {
            derivePattern(path).ifPresent(pattern -> {
                String hash = RulePatternCanonicalizer.hash(pattern.leftPattern(), pattern.rightPattern());
                buckets.computeIfAbsent(hash, key -> new CandidateBucket(pattern.leftPattern(), pattern.rightPattern(), hash))
                    .add(path);
            });
        }

        List<RuleCandidate> candidates = new ArrayList<>();
        for (CandidateBucket bucket : buckets.values()) {
            if (bucket.paths.size() >= MIN_EXAMPLES) {
                candidates.add(bucket.toCandidate(knownRules));
            }
        }
        return candidates;
    }

    private Optional<AbstractPattern> derivePattern(SuccessfulTransformationPath path) {
        String source = path.originalExpression();
        String target = path.targetExpression();
        Optional<AbstractPattern> pattern = derivePerfectSquarePattern(source, target);
        if (pattern.isPresent()) {
            return pattern;
        }
        pattern = deriveDifferenceOfSquares(source, target);
        if (pattern.isPresent()) {
            return pattern;
        }
        return deriveQuadraticCompletion(source, target);
    }

    private Optional<AbstractPattern> derivePerfectSquarePattern(String source, String target) {
        Optional<QuadraticCoefficients> polynomial = QuadraticAnalyzer.analyzePolynomial(source);
        Optional<QuadraticCoefficients> square = QuadraticAnalyzer.analyzePerfectSquare(target);
        if (polynomial.isEmpty() || square.isEmpty()) {
            return Optional.empty();
        }
        QuadraticCoefficients coefficients = polynomial.orElseThrow();
        if (!coefficients.isMonic() || coefficients.linear() == 0 || coefficients.linear() % 2 != 0) {
            return Optional.empty();
        }
        int parameter = coefficients.linear() / 2;
        if (coefficients.constant() != parameter * parameter || !coefficients.equals(square.orElseThrow())) {
            return Optional.empty();
        }
        if (parameter > 0) {
            return Optional.of(new AbstractPattern("x^2 + 2*a*x + a^2", "(x + a)^2"));
        }
        return Optional.of(new AbstractPattern("x^2 - 2*a*x + a^2", "(x - a)^2"));
    }

    private Optional<AbstractPattern> deriveDifferenceOfSquares(String source, String target) {
        Optional<QuadraticCoefficients> product = QuadraticAnalyzer.analyzeDifferenceProduct(source);
        Optional<QuadraticCoefficients> polynomial = QuadraticAnalyzer.analyzePolynomial(target);
        if (product.isPresent() && polynomial.isPresent() && product.orElseThrow().equals(polynomial.orElseThrow())) {
            return Optional.of(new AbstractPattern("(a + b)*(a - b)", "a^2 - b^2"));
        }
        return Optional.empty();
    }

    private Optional<AbstractPattern> deriveQuadraticCompletion(String source, String target) {
        Optional<QuadraticCoefficients> sourcePolynomial = QuadraticAnalyzer.analyzePolynomial(source);
        Optional<QuadraticCoefficients> targetPolynomial = QuadraticAnalyzer.analyze(target);
        if (sourcePolynomial.isEmpty() || targetPolynomial.isEmpty()) {
            return Optional.empty();
        }
        QuadraticCoefficients sourceCoefficients = sourcePolynomial.orElseThrow();
        if (!sourceCoefficients.isMonic() || sourceCoefficients.constant() != 0 || sourceCoefficients.linear() == 0
            || sourceCoefficients.linear() % 2 != 0 || !sourceCoefficients.equals(targetPolynomial.orElseThrow())) {
            return Optional.empty();
        }
        return sourceCoefficients.linear() > 0
            ? Optional.of(new AbstractPattern("x^2 + 2*a*x", "(x + a)^2 - a^2"))
            : Optional.of(new AbstractPattern("x^2 - 2*a*x", "(x - a)^2 - a^2"));
    }

    private record AbstractPattern(String leftPattern, String rightPattern) {
    }

    private static final class CandidateBucket {
        private final String leftPattern;
        private final String rightPattern;
        private final String hash;
        private final List<SuccessfulTransformationPath> paths = new ArrayList<>();

        private CandidateBucket(String leftPattern, String rightPattern, String hash) {
            this.leftPattern = leftPattern;
            this.rightPattern = rightPattern;
            this.hash = hash;
        }

        private void add(SuccessfulTransformationPath path) {
            paths.add(path);
        }

        private RuleCandidate toCandidate(KnownRuleRepository knownRules) {
            double average = paths.stream().mapToInt(SuccessfulTransformationPath::scoreImprovement).average().orElse(0);
            int maximum = paths.stream().mapToInt(SuccessfulTransformationPath::scoreImprovement).max().orElse(0);
            boolean equivalenceVerified = paths.stream().allMatch(path -> path.equivalenceEvidence().contains("simplify")
                || path.equivalenceEvidence().contains("matching normalized"));
            return new RuleCandidate(
                leftPattern,
                rightPattern,
                paths.size(),
                average,
                maximum,
                equivalenceVerified,
                true,
                leftPattern.contains("a") || leftPattern.contains("b") || rightPattern.contains("a") || rightPattern.contains("b"),
                knownRules.statusFor(leftPattern, rightPattern),
                hash
            );
        }
    }
}
