package de.regelsuche.transform;

import de.regelsuche.algebra.QuadraticAnalyzer;
import de.regelsuche.algebra.QuadraticCoefficients;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

public class SymPyTransformationEngine implements TransformationEngine {
    @Override
    public List<Transformation> transform(String expression) {
        List<Transformation> symPyResults = trySymPy(expression);
        if (!symPyResults.isEmpty()) {
            return symPyResults;
        }
        return fallback(expression);
    }

    private List<Transformation> trySymPy(String expression) {
        String escaped = expression.replace("\\", "\\\\").replace("'", "\\'");
        String script = "import sympy as sp\\n"
            + "expr = sp.sympify('" + escaped + "')\\n"
            + "results = [str(sp.simplify(expr)), str(sp.expand(expr)), str(sp.factor(expr))]\\n"
            + "results";

        try (Context context = Context.newBuilder("python").allowAllAccess(true).build()) {
            Value result = context.eval("python", script);
            Set<String> unique = new LinkedHashSet<>();
            for (int i = 0; i < result.getArraySize(); i++) {
                unique.add(result.getArrayElement(i).asString());
            }
            List<Transformation> transformations = new ArrayList<>();
            for (String candidate : unique) {
                if (!candidate.isBlank() && !candidate.equals(expression)) {
                    transformations.add(new Transformation("sympy", candidate));
                }
            }
            return transformations;
        } catch (RuntimeException | LinkageError ignored) {
            return List.of();
        }
    }

    private List<Transformation> fallback(String expression) {
        Set<Transformation> transformations = new LinkedHashSet<>();
        String normalized = expression.replaceAll("\\s+", " ").trim();
        addQuadraticTransformations(transformations, normalized);

        String withoutPlusZeroLeft = normalized.replaceAll("(^|\\()0\\s*\\+\\s*", "$1");
        if (!withoutPlusZeroLeft.equals(normalized)) {
            transformations.add(new Transformation("fallback_plus_zero", withoutPlusZeroLeft));
        }

        String withoutPlusZeroRight = normalized.replaceAll("\\s*\\+\\s*0($|\\))", "$1");
        if (!withoutPlusZeroRight.equals(normalized)) {
            transformations.add(new Transformation("fallback_plus_zero", withoutPlusZeroRight));
        }

        String withoutTimesOneLeft = normalized.replaceAll("(^|\\()1\\s*\\*\\s*", "$1");
        if (!withoutTimesOneLeft.equals(normalized)) {
            transformations.add(new Transformation("fallback_times_one", withoutTimesOneLeft));
        }

        String withoutTimesOneRight = normalized.replaceAll("\\s*\\*\\s*1($|\\))", "$1");
        if (!withoutTimesOneRight.equals(normalized)) {
            transformations.add(new Transformation("fallback_times_one", withoutTimesOneRight));
        }

        String withoutOuterParens = normalized;
        if (withoutOuterParens.startsWith("(") && withoutOuterParens.endsWith(")")) {
            withoutOuterParens = withoutOuterParens.substring(1, withoutOuterParens.length() - 1).trim();
        }
        if (!withoutOuterParens.equals(normalized) && !withoutOuterParens.isBlank()) {
            transformations.add(new Transformation("fallback_remove_parens", withoutOuterParens));
        }

        return new ArrayList<>(transformations);
    }

    private void addQuadraticTransformations(Set<Transformation> transformations, String expression) {
        Optional<QuadraticCoefficients> perfectSquare = QuadraticAnalyzer.analyzePerfectSquare(expression);
        perfectSquare.ifPresent(coefficients -> transformations.add(new Transformation(
            "fallback_expand_power",
            QuadraticAnalyzer.formatPolynomial(coefficients)
        )));

        Optional<QuadraticCoefficients> differenceProduct = QuadraticAnalyzer.analyzeDifferenceProduct(expression);
        differenceProduct.ifPresent(coefficients -> transformations.add(new Transformation(
            "fallback_expand_difference_product",
            QuadraticAnalyzer.formatPolynomial(coefficients)
        )));

        Optional<QuadraticCoefficients> polynomial = QuadraticAnalyzer.analyzePolynomial(expression);
        if (polynomial.isEmpty()) {
            return;
        }
        QuadraticCoefficients coefficients = polynomial.orElseThrow();
        if (!coefficients.isMonic() || coefficients.linear() == 0 || coefficients.linear() % 2 != 0) {
            return;
        }

        int parameter = coefficients.linear() / 2;
        if (coefficients.constant() == parameter * parameter) {
            transformations.add(new Transformation(
                "fallback_factor_perfect_square",
                QuadraticAnalyzer.formatPerfectSquare(coefficients.variable(), parameter)
            ));
        }
        if (coefficients.constant() == 0) {
            transformations.add(new Transformation(
                "fallback_complete_square",
                QuadraticAnalyzer.formatCompletion(coefficients.variable(), parameter)
            ));
        }
    }
}
