package de.regelsuche.math.algorithms.equivalence;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.parse.ExactExpressionFormatter;
import de.regelsuche.parse.ExactParsedTerm;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
 * after the returned candidate set has been frozen. Source numbers are resolved
 * through parser-issued exact provenance. Primitive rule/application IDs are
 * retained provenance, not independently replayed proof objects.
 */
public final class ExactPolynomialResidualComposer {
    private static final int MAX_COMPONENTS = 64;
    private static final int MAX_EFFECTS = 128;
    private static final int MAX_COMPOSITION_SIZE = 8;
    private static final int MAX_RESULTS = 1_024;
    private static final long MAX_COMBINATION_ATTEMPTS = 1_000_000L;

    private final ExactResidualPolynomialArithmetic arithmetic =
        new ExactResidualPolynomialArithmetic();

    /**
     * Extracts the exact top-level additive occurrence partition used by v1.
     *
     * <p>Only addition is flattened. Subtraction and every other operator stay
     * occurrence-visible and form one component.</p>
     */
    public List<SourceComponent> additiveComponents(
        String sourceExpression
    ) {
        String source = normalizeSyntax(
            sourceExpression,
            "sourceExpression");
        parsePolynomial(source, "sourceExpression");
        ExactParsedTerm parsed = arithmetic.exactTerm(source);
        List<Expr> terms = new ArrayList<>();
        collectAddition(parsed.expression(), terms);
        if (terms.isEmpty() || terms.size() > MAX_COMPONENTS) {
            throw new IllegalArgumentException(
                "additive component count must be in [1,"
                    + MAX_COMPONENTS + "]");
        }
        List<SourceComponent> components =
            new ArrayList<>(terms.size());
        for (int index = 0; index < terms.size(); index++) {
            String ordinal = String.format(
                Locale.ROOT,
                "%02d",
                index);
            components.add(new SourceComponent(
                "term-" + ordinal,
                "additive-term-v1:" + ordinal,
                ExactExpressionFormatter.format(terms.get(index), parsed)));
        }
        return List.copyOf(components);
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
        if (components.size() > MAX_COMPONENTS) {
            throw new IllegalArgumentException(
                "coveredComponents exceeds " + MAX_COMPONENTS);
        }
        requireUnique(components.stream()
            .map(SourceComponent::id)
            .toList(), "covered component IDs");
        requireUnique(components.stream()
            .map(SourceComponent::occurrenceKey)
            .toList(), "covered occurrence keys");
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
        ExactParsedTerm transformedTerm = arithmetic.exactTerm(transformed);
        if (!containsSubtree(
                transformedTerm.expression(), transformedTerm, structured)) {
            throw new IllegalArgumentException(
                "structured fragment must occur in transformed fragment");
        }

        List<String> normalizedAssumptions =
            optionalTexts(assumptions, "assumptions");
        if (!normalizedAssumptions.isEmpty()) {
            throw new IllegalArgumentException(
                "v1 residual effects must be assumption-free");
        }

        List<String> normalizedApplicationKeys =
            texts(applicationKeys, "applicationKeys");
        requireUnique(
            normalizedApplicationKeys,
            "applicationKeys");
        return new Effect(
            requireText(id, "id"),
            components,
            sourceFragment,
            transformed,
            structured,
            source.subtract(structuredPolynomial).toCanonicalString(),
            normalizedAssumptions,
            texts(primitiveRuleIds, "primitiveRuleIds"),
            normalizedApplicationKeys);
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
            validatePartition(
                source,
                sourcePolynomial,
                sourceComponents);
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
        requireUnique(effects.stream()
            .map(Effect::id)
            .toList(), "effect IDs");
        if (effects.size() < compositionSize) {
            return List.of();
        }
        requireCombinationBound(effects.size(), compositionSize);

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
            if (results.size() >= maxResults) {
                return;
            }
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
        List<String> primitiveRuleIds = ordered.stream()
            .flatMap(effect ->
                effect.primitiveRuleIds().stream())
            .toList();
        List<String> applicationKeys = ordered.stream()
            .flatMap(effect ->
                effect.applicationKeys().stream())
            .toList();
        requireUnique(applicationKeys, "composition application keys");
        return new Composition(
            source,
            components,
            ordered,
            candidate,
            "0",
            primitiveRuleIds,
            applicationKeys);
    }

    private List<SourceComponent> validatePartition(
        String sourceExpression,
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

        List<SourceComponent> expected =
            additiveComponents(sourceExpression);
        if (!components.equals(expected)) {
            throw new IllegalArgumentException(
                "source components are not the exact additive occurrences");
        }

        Polynomial reconstructed = Polynomial.zero();
        for (SourceComponent component : components) {
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
        try {
            return arithmetic.parse(expression);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                name + " is outside the bounded exact polynomial fragment",
                exception);
        }
    }

    private String normalizeSyntax(String expression, String name) {
        String text = requireText(expression, name);
        try {
            return arithmetic.syntax(text);
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

    private static void collectAddition(
        Expr expression,
        List<Expr> terms
    ) {
        if (expression instanceof BinaryExpr binary
                && binary.operator()
                    == de.regelsuche.ast.BinaryOperator.ADD) {
            collectAddition(binary.left(), terms);
            collectAddition(binary.right(), terms);
        } else {
            terms.add(expression);
        }
    }

    private static boolean containsSubtree(
        Expr expression,
        ExactParsedTerm parsed,
        String expected
    ) {
        if (ExactExpressionFormatter.format(expression, parsed).equals(expected)) {
            return true;
        }
        if (expression instanceof BinaryExpr binary) {
            return containsSubtree(binary.left(), parsed, expected)
                || containsSubtree(binary.right(), parsed, expected);
        }
        if (expression instanceof FunctionExpr function) {
            return function.arguments().stream()
                .anyMatch(argument ->
                    containsSubtree(argument, parsed, expected));
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
        if (maxResults < 0 || maxResults > MAX_RESULTS) {
            throw new IllegalArgumentException(
                "maxResults must be in [0," + MAX_RESULTS + "]");
        }
    }

    private static void requireCombinationBound(
        int effectCount,
        int compositionSize
    ) {
        int selected = Math.min(
            compositionSize,
            effectCount - compositionSize);
        long combinations = 1L;
        for (int index = 1; index <= selected; index++) {
            combinations = combinations
                * (effectCount - selected + index)
                / index;
            if (combinations > MAX_COMBINATION_ATTEMPTS) {
                throw new IllegalArgumentException(
                    "candidate effect combinations exceed "
                        + MAX_COMBINATION_ATTEMPTS);
            }
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
