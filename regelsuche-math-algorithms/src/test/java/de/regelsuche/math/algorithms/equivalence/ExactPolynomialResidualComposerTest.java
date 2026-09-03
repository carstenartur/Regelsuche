package de.regelsuche.math.algorithms.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.equivalence.ExactPolynomialResidualComposer.Composition;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialResidualComposer.Effect;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialResidualComposer.SourceComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ExactPolynomialResidualComposerTest {
    private final ExactPolynomialResidualComposer composer =
        new ExactPolynomialResidualComposer();

    @Test
    void composesDisjointEffectsWhenTheirResidualsCancel() {
        List<SourceComponent> components = components();
        Effect difference = composer.effect(
            "difference",
            List.of(components.get(0), components.get(3)),
            "(a*c - b*d)^2 + 2*a*b*c*d",
            "(a*c - b*d)^2",
            List.of(),
            List.of("complete-square"),
            List.of("move-difference"));
        Effect sum = composer.effect(
            "sum",
            List.of(components.get(1), components.get(2)),
            "(a*d + b*c)^2 - 2*a*b*c*d",
            "(a*d + b*c)^2",
            List.of(),
            List.of("complete-square"),
            List.of("move-sum"));

        List<Composition> results = composer.compose(
            source(),
            components,
            List.of(difference, sum),
            2,
            8);

        assertEquals(1, results.size());
        Composition result = results.getFirst();
        assertEquals("0", result.combinedResidualNormalForm());
        assertEquals(
            List.of("difference", "sum"),
            result.effects().stream().map(Effect::id).toList());
        assertEquals(
            List.of("complete-square", "complete-square"),
            result.primitiveRuleIds());
    }

    @Test
    void rejectsNonEquivalentOrStructurallyUnrelatedEffects() {
        List<SourceComponent> components = components();
        List<SourceComponent> pair =
            List.of(components.get(0), components.get(3));

        assertThrows(IllegalArgumentException.class, () ->
            composer.effect(
                "false-effect",
                pair,
                "(a*c - b*d)^2",
                "(a*c - b*d)^2",
                List.of(),
                List.of("false"),
                List.of("false-move")));
        assertThrows(IllegalArgumentException.class, () ->
            composer.effect(
                "missing-structure",
                pair,
                "(a*c - b*d)^2 + 2*a*b*c*d",
                "(a*c + b*d)^2",
                List.of(),
                List.of("false"),
                List.of("false-move")));
        assertThrows(IllegalArgumentException.class, () ->
            composer.effect(
                "assumption-bound",
                pair,
                "(a*c - b*d)^2 + 2*a*b*c*d",
                "(a*c - b*d)^2",
                List.of("a != 0"),
                List.of("conditional"),
                List.of("conditional-move")));
    }

    @Test
    void keepsNonCancellingAndOverlappingCombinationsOut() {
        List<SourceComponent> components = components();
        Effect firstPlus = composer.effect(
            "first-plus",
            List.of(components.get(0), components.get(3)),
            "(a*c + b*d)^2 - 2*a*b*c*d",
            "(a*c + b*d)^2",
            List.of(),
            List.of("complete-square"),
            List.of("move-first"));
        Effect secondPlus = composer.effect(
            "second-plus",
            List.of(components.get(1), components.get(2)),
            "(a*d + b*c)^2 - 2*a*b*c*d",
            "(a*d + b*c)^2",
            List.of(),
            List.of("complete-square"),
            List.of("move-second"));
        Effect overlap = composer.effect(
            "overlap",
            List.of(components.get(0), components.get(3)),
            "(a*c - b*d)^2 + 2*a*b*c*d",
            "(a*c - b*d)^2",
            List.of(),
            List.of("complete-square"),
            List.of("move-overlap"));

        assertTrue(composer.compose(
            source(),
            components,
            List.of(firstPlus, secondPlus),
            2,
            8).isEmpty());
        assertTrue(composer.compose(
            source(),
            components,
            List.of(firstPlus, overlap),
            2,
            8).isEmpty());
    }

    @Test
    void rejectsEffectsChangedAfterCertification() {
        List<SourceComponent> components = components();
        Effect valid = composer.effect(
            "valid",
            List.of(components.get(0), components.get(3)),
            "(a*c - b*d)^2 + 2*a*b*c*d",
            "(a*c - b*d)^2",
            List.of(),
            List.of("complete-square"),
            List.of("move-valid"));
        Effect changed = new Effect(
            valid.id(),
            valid.components(),
            valid.sourceFragment(),
            valid.transformedFragment(),
            valid.structuredFragment(),
            "0",
            valid.assumptions(),
            valid.primitiveRuleIds(),
            valid.applicationKeys());

        assertThrows(IllegalArgumentException.class, () ->
            composer.compose(
                source(),
                components,
                List.of(changed),
                1,
                8));
    }

    @Test
    void rejectsDuplicateEffectOccurrencesAndUnboundedResultRequests() {
        List<SourceComponent> components = components();

        assertThrows(IllegalArgumentException.class, () ->
            composer.effect(
                "duplicate",
                List.of(components.get(0), components.get(0)),
                "2*(a*c)^2",
                "(a*c)^2",
                List.of(),
                List.of("duplicate"),
                List.of("duplicate-move")));
        assertThrows(IllegalArgumentException.class, () ->
            composer.compose(
                source(),
                components,
                List.of(),
                1,
                1_025));
    }

    @Test
    void rejectsACombinatorialPlanAboveTheFixedWorkCeiling() {
        List<SourceComponent> components = components();
        List<Effect> effects = IntStream.range(0, 128)
            .mapToObj(index -> composer.effect(
                "candidate-" + index,
                List.of(components.get(0), components.get(3)),
                "(a*c - b*d)^2 + 2*a*b*c*d",
                "(a*c - b*d)^2",
                List.of(),
                List.of("complete-square"),
                List.of("move-" + index)))
            .toList();

        assertThrows(IllegalArgumentException.class, () ->
            composer.compose(
                source(),
                components,
                effects,
                8,
                8));
    }

    @Test
    void requiresTheDeclaredComponentsToReconstructTheSource() {
        List<SourceComponent> components = components();

        assertThrows(IllegalArgumentException.class, () ->
            composer.compose(
                source(),
                components.subList(0, 3),
                List.of(),
                1,
                8));

        SourceComponent first = components.getFirst();
        List<SourceComponent> forged = new ArrayList<>(
            components);
        forged.set(0, new SourceComponent(
            first.id(),
            "invented-occurrence",
            first.expression()));
        assertThrows(IllegalArgumentException.class, () ->
            composer.compose(
                source(),
                forged,
                List.of(),
                1,
                8));
    }

    private List<SourceComponent> components() {
        return composer.additiveComponents(source());
    }

    private static String source() {
        return "(a*c)^2 + (a*d)^2 + (b*c)^2 + (b*d)^2";
    }
}
