package de.regelsuche.export;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;

/**
 * LaTeX renderer for math content.
 *
 * <p>Delegates to {@link AstLatexRenderer} so the output is AST-based
 * (fractions as {@code \frac}, powers as {@code ^}, functions like
 * {@code \sin}, {@code \log}, {@code \sqrt}, proper parenthesisation by
 * operator precedence, equations, equation systems). The previous naive
 * {@code *}-replacement implementation is retained only as a final fallback
 * inside {@link AstLatexRenderer} for inputs that cannot be parsed.</p>
 */
public class LaTeXMathRenderer implements MathRenderer {

    private final AstLatexRenderer delegate = new AstLatexRenderer();

    @Override
    public String renderExpression(String expression) {
        return delegate.renderExpression(expression);
    }

    @Override
    public String renderStep(TransformationStep step) {
        return delegate.renderStep(step);
    }

    @Override
    public String renderPath(DiscoveredTransformation path) {
        return delegate.renderPath(path);
    }
}

