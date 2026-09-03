package de.regelsuche.math.algorithms.equivalence;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Composes local polynomial transformations by balancing exact residuals.
 *
 * <p>An effect records
 * {@code sourceFragment = structuredFragment + residual}. It is not an
 * executable rewrite while the residual is non-zero. A composition is emitted
 * only when selected effects cover a complete source partition without
 * overlap, their residuals sum to zero and their structured fragments
 * reconstruct the source polynomial.</p>
 *
 * <p>The API accepts no target expression. Reference correspondence belongs
 * after the returned candidate set has been frozen.</p>
 */
public final class ExactPolynomialResidualComposer {
    private static final int MAX_COMPONENTS = 64;
    private static final int MAX_EFFECTS = 128;
    private static final int MAX_COMPOSITION_SIZE = 8;

    private final PolynomialArithmetic arithmetic = new PolynomialArithmetic();
    private final ExpressionParser parser = new ExpressionParser();

    public SourceComponent component(
        String id,
        String occurrenceKey,
        String expression
    ) {
        String normalized = normalizeSyntax(expression, "expression");
        parsePolynomial(normalized, "expression");
        return new SourceComponent(id, occurrenceKey, normalized);
    }

    public Effect effect(
        String id,
        Collection<SourceComponent> coveredComponents,
        String transformedFragment,
        String structuredFragment,
        List<String> assumptions,
        List<String> primitiveRuleIds,
        List<String> applicationKeys
    ) {
        List<SourceComponent> components =
            orderedComponents(coveredComponents);
        String sourceFragment = sum(components.stream()
            .map(SourceComponent::expression)
            .toList());
        String transformed = normalizeSyntax(
            transformedFragment,
            "transformedFragment");
        String structured = normalizeSyntax(
            structuredFragment,
            "structuredFragment");
        Polynomial source =
            parsePolynomial(sourceFragment, "sourceFragment");
        Polynomial transformedPolynomial =
            parsePolynomial(transformed, "transformedFragment");
        Polynomial structuredPolynomial =
            parsePolynomial(structured, "structuredFragment");

        if (!source.equals(transformedPolynomial)) {
            throw new IllegalArgumentException(
                "transformed fragment must equal its source components");
        }
        if (!containsSubtree(
                parser.parseTerm(transformed),
                parser.parseTerm(structured))) {
            throw new IllegalArgumentException(
                "structured fragment must occur in transformed fragment");
        }

        List<String> normalizedAssumptions =
            optionalTexts(assumptions, "assumptions");
        if (!normalizedAssumptions.isEmpty()) {
            throw new IllegalArgumentException(
                "v1 residual effects must be assumption-free");
        }
        return new Effect(
            requireText(id, "id"),
            components,
            sourceFragment,
            transformed,
            structured,
            source.subtract(structuredPolynomial).toCanonicalString(),
            normalizedAssumptions,
            texts(primitiveRuleIds, "primitiveRuleIds"),
            texts(applicationKeys, "applicationKeys"));
    }

    public List<Composition> compose(
        String sourceExpression,
        List<SourceComponent> sourceComponents,
        List<Effect> candidateEffects,
        int compositionSize,
        int maxResults
    ) {
        requireBounds(compositionSize, maxResults);
        if (maxResults == 0) {
            return List.of();
        }

        String source = normalizeSyntax(
            sourceExpression,
            "sourceExpression");
        Polynomial sourcePolynomial =
            parsePolynomial(source, "sourceExpression");
        List<SourceComponent> components =
            validatePartition(sourcePolynomial, sourceComponents);
        Set<String> requiredIds = components.stream()
            .map(SourceComponent::id)
            .collect(Collectors.toUnmodifiableSet());

        List<Effect> effects = List.copyOf(
            Objects.requireNonNull(
                candidateEffects,
                "candidateEffects"));
        if (effects.size() > MAX_EFFECTS) {
            throw new IllegalArgumentException(
                "candidateEffects exceeds " + MAX_EFFECTS);
        }
        effects = effects.stream()
            .map(effect -> validateEffect(effect, components))
            .filter(effect -> requiredIds.containsAll(
                effect.componentIds()))
            .sorted(Comparator
                .comparing(Effect::id)
                .thenComparing(Effect::structuredFragment))
            .toList();

        List<Composition> results = new ArrayList<>();
        select(
            source,
            sourcePolynomial,
            components,
            requiredIds,
            effects,
            compositionSize,
            maxResults,
            0,
            new ArrayList<>(),
            new LinkedHashSet<>(),
            results,
            new HashSet<>());
        return List.copyOf(results);
    }

    private void select(
        String source,
        Polynomial sourcePolynomial,
        List<SourceComponent> components,
        Set<String> requiredIds,
        List<Effect> effects,
        int compositionSize,
        int maxResults,
        int start,
        List<Effect> selected,
        Set<String> coveredIds,
        List<Composition> results,
        Set<String> retainedCandidates
    ) {
        if (results.size() >= maxResults) {
            return;
        }
        if (selected.size() == compositionSize) {
            if (coveredIds.equals(requiredIds)) {
                Composition result = composeSelection(
                    source,
                    sourcePolynomial,
                    components,
                    selected);
                if (result != null
                        && retainedCandidates.add(
                            result.candidateExpression())) {
                    results.add(result);
                }
            }
            return;
        }

        int missing = compositionSize - selected.size();
        for (int index = start;
                index <= effects.size() - missing;
                index++) {
            Effect effect = effects.get(index);
            if (effect.componentIds().stream()
                    .anyMatch(coveredIds::contains)) {
                continue;
            }
            selected.add(effect);
            coveredIds.addAll(effect.componentIds());
            select(
                source,
                sourcePolynomial,
                components,
                requiredIds,
                effects,
                compositionSize,
                maxResults,
                index + 1,
                selected,
                coveredIds,
                results,
                retainedCandidates);
            coveredIds.removeAll(effect.componentIds());
            selected.removeLast();
        }
    }

    private Composition composeSelection(
        String source,
        Polynomial sourcePolynomial,
        List<SourceComponent> components,
        List<Effect> selected
    ) {
        Polynomial sourceSum = Polynomial.zero();
        Polynomial structureSum = Polynomial.zero();
        Polynomial residualSum = Polynomial.zero();
        for (Effect effect : selected) {
            sourceSum = sourceSum.add(parsePolynomial(
                effect.sourceFragment(),
                "effect source"));
            structureSum = structureSum.add(parsePolynomial(
                effect.structuredFragment(),
                "effect structure"));
            residualSum = residualSum.add(parsePolynomial(
                effect.residualNormalForm(),
                "effect residual"));
        }
        if (!sourceSum.equals(sourcePolynomial)
                || !residualSum.isZero()
                || !structureSum.equals(sourcePolynomial)) {
            return null;
        }

        List<Effect> ordered = selected.stream()
            .sorted(Comparator.comparing(effect ->
                String.join("\u0001", effect.componentIds())))
            .toList();
        String candidate = sum(ordered.stream()
            .map(Effect::structuredFragment)
            .toList());
        if (!parsePolynomial(candidate, "candidate")
                .equals(sourcePolynomial)) {
            return null;
        }
        return new Composition(
            source,
            components,
            ordered,
            candidate,
            "0",
            ordered.stream()
                .flatMap(effect ->
                    effect.primitiveRuleIds().stream())
                .toList(),
            ordered.stream()
                .flatMap(effect ->
                    effect.applicationKeys().stream())
                .toList());
    }

    private List<SourceComponent> validatePartition(
        Polynomial source,
        List<SourceComponent> sourceComponents
    ) {
        List<SourceComponent> components =
            orderedComponents(sourceComponents);
        if (components.size() > MAX_COMPONENTS) {
            throw new IllegalArgumentException(
                "sourceComponents exceeds " + MAX_COMPONENTS);
        }
        requireUnique(components.stream()
            .map(SourceComponent::id)
            .toList(), "component IDs");
        requireUnique(components.stream()
            .map(SourceComponent::occurrenceKey)
            .toList(), "occurrence keys");

        Polynomial reconstructed = Polynomial.zero();
        for (SourceComponent component : components) {
            SourceComponent validated = component(
                component.id(),
                component.occurrenceKey(),
                component.expression());
            if (!validated.equals(component)) {
                throw new IllegalArgumentException(
                    "source component changed after certification");
            }
            reconstructed = reconstructed.add(parsePolynomial(
                component.expression(),
                "source component"));
        }
        if (!reconstructed.equals(source)) {
            throw new IllegalArgumentException(
                "source components do not reconstruct source expression");
        }
        return components;
    }

    private Effect validateEffect(
        Effect effect,
        List<SourceComponent> sourceComponents
    ) {
        Objects.requireNonNull(effect, "effect");
        for (SourceComponent component : effect.components()) {
            if (!sourceComponents.contains(component)) {
                throw new IllegalArgumentException(
                    "effect references an unknown source component");
            }
        }
        Effect validated = effect(
            effect.id(),
            effect.components(),
            effect.transformedFragment(),
            effect.structuredFragment(),
            effect.assumptions(),
            effect.primitiveRuleIds(),
            effect.applicationKeys());
        if (!validated.equals(effect)) {
            throw new IllegalArgumentException(
                "effect changed after certification");
        }
        return effect;
    }

    private List<SourceComponent> orderedComponents(
        Collection<SourceComponent> sourceComponents
    ) {
        Objects.requireNonNull(
            sourceComponents,
            "sourceComponents");
        List<SourceComponent> components = sourceComponents.stream()
            .map(component ->
                Objects.requireNonNull(
                    component,
                    "source component"))
            .sorted(Comparator
                .comparing(SourceComponent::id)
                .thenComparing(SourceComponent::occurrenceKey))
            .toList();
        if (components.isEmpty()) {
            throw new IllegalArgumentException(
                "sourceComponents must not be empty");
        }
        return components;
    }

    private Polynomial parsePolynomial(
        String expression,
        String name
    ) {
        return arithmetic.parse(expression).orElseThrow(() ->
            new IllegalArgumentException(
                name + " is outside the polynomial fragment"));
    }

    private String normalizeSyntax(String expression, String name) {
        String text = requireText(expression, name);
        try {
            return ExpressionFormatter.format(parser.parseTerm(text));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                name + " is not a supported expression",
                exception);
        }
    }

    private String sum(List<String> expressions) {
        return normalizeSyntax(
            expressions.stream()
                .map(expression -> "(" + expression + ")")
                .collect(Collectors.joining(" + ")),
            "sum");
    }

    private static boolean containsSubtree(
        Expr expression,
        Expr expected
    ) {
        if (expression.equals(expected)) {
            return true;
        }
        if (expression instanceof BinaryExpr binary) {
            return containsSubtree(binary.left(), expected)
                || containsSubtree(binary.right(), expected);
        }
        if (expression instanceof FunctionExpr function) {
            return function.arguments().stream()
                .anyMatch(argument ->
                    containsSubtree(argument, expected));
        }
        return false;
    }

    private static void requireBounds(
        int compositionSize,
        int maxResults
    ) {
        if (compositionSize < 1
                || compositionSize > MAX_COMPOSITION_SIZE) {
            throw new IllegalArgumentException(
                "compositionSize must be in [1,"
                    + MAX_COMPOSITION_SIZE + "]");
        }
        if (maxResults < 0) {
            throw new IllegalArgumentException(
                "maxResults must not be negative");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                name + " must not be blank");
        }
        return value.trim();
    }

    private static List<String> optionalTexts(
        Collection<String> values,
        String name
    ) {
        Objects.requireNonNull(values, name);
        return List.copyOf(values.stream()
            .map(value -> requireText(
                value,
                name + " entry"))
            .toList());
    }

    private static List<String> texts(
        Collection<String> values,
        String name
    ) {
        Objects.requireNonNull(values, name);
        List<String> result = values.stream()
            .map(value -> requireText(
                value,
                name + " entry"))
            .toList();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(
                name + " must not be empty");
        }
        return List.copyOf(result);
    }

    private static void requireUnique(
        List<String> values,
        String name
    ) {
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(
                name + " must be unique");
        }
    }

    public record SourceComponent(
        String id,
        String occurrenceKey,
        String expression
    ) {
        public SourceComponent {
            id = requireText(id, "id");
            occurrenceKey = requireText(
                occurrenceKey,
                "occurrenceKey");
            expression = requireText(
                expression,
                "expression");
        }
    }

    public record Effect(
        String id,
        List<SourceComponent> components,
        String sourceFragment,
        String transformedFragment,
        String structuredFragment,
        String residualNormalForm,
        List<String> assumptions,
        List<String> primitiveRuleIds,
        List<String> applicationKeys
    ) {
        public Effect {
            id = requireText(id, "id");
            components = List.copyOf(
                Objects.requireNonNull(
                    components,
                    "components"));
            if (components.isEmpty()) {
                throw new IllegalArgumentException(
                    "components must not be empty");
            }
            sourceFragment = requireText(
                sourceFragment,
                "sourceFragment");
            transformedFragment = requireText(
                transformedFragment,
                "transformedFragment");
            structuredFragment = requireText(
                structuredFragment,
                "structuredFragment");
            residualNormalForm = requireText(
                residualNormalForm,
                "residualNormalForm");
            assumptions = optionalTexts(
                assumptions,
                "assumptions");
            if (!assumptions.isEmpty()) {
                throw new IllegalArgumentException(
                    "v1 residual effects must be assumption-free");
            }
            primitiveRuleIds = texts(
                primitiveRuleIds,
                "primitiveRuleIds");
            applicationKeys = texts(
                applicationKeys,
                "applicationKeys");
        }

        public List<String> componentIds() {
            return components.stream()
                .map(SourceComponent::id)
                .toList();
        }
    }

    public record Composition(
        String sourceExpression,
        List<SourceComponent> sourceComponents,
        List<Effect> effects,
        String candidateExpression,
        String combinedResidualNormalForm,
        List<String> primitiveRuleIds,
        List<String> applicationKeys
    ) {
        public Composition {
            sourceExpression = requireText(
                sourceExpression,
                "sourceExpression");
            sourceComponents = List.copyOf(
                Objects.requireNonNull(
                    sourceComponents,
                    "sourceComponents"));
            effects = List.copyOf(
                Objects.requireNonNull(effects, "effects"));
            candidateExpression = requireText(
                candidateExpression,
                "candidateExpression");
            combinedResidualNormalForm = requireText(
                combinedResidualNormalForm,
                "combinedResidualNormalForm");
            if (!"0".equals(combinedResidualNormalForm)) {
                throw new IllegalArgumentException(
                    "composition residual must be zero");
            }
            primitiveRuleIds = texts(
                primitiveRuleIds,
                "primitiveRuleIds");
            applicationKeys = texts(
                applicationKeys,
                "applicationKeys");
        }
    }
}
