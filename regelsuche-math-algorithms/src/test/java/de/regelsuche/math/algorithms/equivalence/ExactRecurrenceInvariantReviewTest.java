package de.regelsuche.math.algorithms.equivalence;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.equivalence.ExactRecurrenceInductionVerifier.Certificate;
import de.regelsuche.math.algorithms.equivalence.RecurrenceInvariantFormation.Bounds;
import de.regelsuche.math.algorithms.equivalence.RecurrenceInvariantFormation.Recurrence;
import de.regelsuche.scalar.ExactRational;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExactRecurrenceInvariantReviewTest {
    private final ExactRecurrenceInvariantDiscovery discovery = new ExactRecurrenceInvariantDiscovery();

    @Test
    void malformedAssumptionCannotAliasAnIssuedNegativeRun() {
        var expected = formation(List.of("state0 > ?"));
        var retained = discovery.discover(expected);
        assertEquals(ExactRecurrenceInvariantDiscovery.Status.UNSUPPORTED, retained.status());
        assertTrue(retained.certificates().isEmpty());
        assertEquals(0, retained.work().consumed());

        assertThrows(IllegalArgumentException.class, () -> {
            var foreign = formation(List.of("state0 > " + (char) 0xd800));
            // Before the fix this different context issues exactly the same frozen UTF-8 identity.
            assertNotEquals(expected, foreign);
            assertEquals(expected.contentHash(), foreign.contentHash());
            var foreignRun = discovery.discover(foreign);
            assertEquals(retained.contentHash(), foreignRun.contentHash());
            assertArrayEquals(retained.toCanonicalJson().getBytes(StandardCharsets.UTF_8),
                foreignRun.toCanonicalJson().getBytes(StandardCharsets.UTF_8));
        });
        for (String malformed : List.of("x" + (char) 0xdc00, "x" + (char) 0xd800 + "y",
                "x" + (char) 0xdc00 + (char) 0xd800)) {
            assertThrows(IllegalArgumentException.class, () -> formation(List.of(malformed)));
        }
    }

    @Test
    void validSupplementaryAssumptionsRetainTheirExactUnsupportedContext() {
        String context = new String(Character.toChars(0x1d465)) + " > 0";
        var formation = formation(List.of(context));
        var run = discovery.discover(formation);
        assertEquals(List.of(context), run.formation().assumptions());
        assertEquals(ExactRecurrenceInvariantDiscovery.Status.UNSUPPORTED, run.status());
        assertTrue(run.certificates().isEmpty());
        assertEquals(0, run.work().consumed());
        assertEquals(run.toCanonicalJson(), new String(run.toCanonicalJson().getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8));
        assertNotEquals(formation(List.of("? > 0")).contentHash(), formation.contentHash());
    }

    @Test
    void certificateRejectsExcessDimensionBeforeAccessingTheDynamicList() {
        var source = discovery.discover(formation(List.of())).certificates().getFirst().certificate();
        List<ExactRational> unadmitted = new AbstractList<>() {
            @Override public int size() { return 13; }
            @Override public ExactRational get(int index) {
                throw new AssertionError("coefficient element read before dimension admission");
            }
        };
        assertThrows(IllegalArgumentException.class, () -> new Certificate(source.formationHash(),
            source.lambdaIndex(), source.chartIndex(), source.lambda(), unadmitted, source.solverResult(),
            source.invariantExpression(), source.shiftedInvariantExpression(), source.scaledInvariantExpression(),
            source.initialValue()));
    }

    @Test
    void rationalCompanionAndCubicCertificatesAgreeWithTheirActualInitialSequences() {
        var bounds = new Bounds(128, 256, 20_000, 200_000);
        var formations = List.of(
            new RecurrenceInvariantFormation(new Recurrence(rationals("1/2", "1/3", "1/6"),
                rationals("1/3", "2/5", "3/7")), List.of(0, 1, 2), 1,
                RecurrenceInvariantFormation.homogeneousBasis(3, 1), rationals("1"), List.of(), bounds),
            new RecurrenceInvariantFormation(new Recurrence(rationals("1/2"), rationals("3/2")), List.of(0), 3,
                RecurrenceInvariantFormation.homogeneousBasis(1, 3), rationals("1/8"), List.of(), bounds),
            new RecurrenceInvariantFormation(new Recurrence(rationals("0"), rationals("2/3")), List.of(0), 3,
                RecurrenceInvariantFormation.homogeneousBasis(1, 3), rationals("0"), List.of(), bounds));
        for (var formation : formations) {
            var run = discovery.discover(formation);
            assertEquals(ExactRecurrenceInvariantDiscovery.Status.COMPLETE_WITH_INVARIANTS, run.status());
            assertEquals(1, run.certificates().size());
            var certificate = run.certificates().getFirst().certificate();
            assertEquals(ExactRecurrenceInductionVerifier.VerificationStatus.CONFIRMED,
                new ExactRecurrenceInductionVerifier().verify(formation, certificate, bounds.maxTotalWorkUnits()).status());
            List<ExactRational> state = new ArrayList<>(formation.recurrence().initialValues());
            // Additional finite controls for the typed source convention, not a substitute for induction.
            for (int n = 0; n < 8; n++) {
                ExactRational value = ExactRational.ZERO;
                for (int term = 0; term < formation.basis().size(); term++) {
                    ExactRational monomial = certificate.coefficients().get(term);
                    for (int axis = 0; axis < state.size(); axis++) {
                        monomial = monomial.multiply(state.get(axis).pow(formation.basis().get(term).exponents().get(axis)));
                    }
                    value = value.add(monomial);
                }
                assertEquals(certificate.lambda().pow(n).multiply(certificate.initialValue()), value);
                ExactRational next = ExactRational.ZERO;
                for (int axis = 0; axis < state.size(); axis++) {
                    next = next.add(formation.recurrence().coefficients().get(axis).multiply(state.get(axis)));
                }
                state.removeFirst();
                state.add(next);
            }
        }
    }

    private static List<ExactRational> rationals(String... values) {
        return java.util.Arrays.stream(values).map(ExactRational::fromCanonicalText).toList();
    }

    private static RecurrenceInvariantFormation formation(List<String> assumptions) {
        return new RecurrenceInvariantFormation(new Recurrence(List.of(ExactRational.integer(2)),
            List.of(ExactRational.integer(3))), List.of(0), 1,
            RecurrenceInvariantFormation.homogeneousBasis(1, 1), List.of(ExactRational.integer(2)), assumptions,
            new Bounds(128, 256, 20_000, 200_000));
    }
}
