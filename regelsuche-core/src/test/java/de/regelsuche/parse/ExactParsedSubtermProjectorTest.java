package de.regelsuche.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.polynomial.ExactParsedUnivariatePolynomialView;
import de.regelsuche.polynomial.PolynomialWorkAuthority;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.scalar.ExactRational;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExactParsedSubtermProjectorTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final ExactParsedSubtermProjector projector =
        new ExactParsedSubtermProjector();

    @Test
    void reusesWholeParserRootWithoutLosingStalenessOrRangeGuards() {
        var reusable = ExactParsedSubtermProjector.withParserRootReuse(
            ExactParsedSubtermProjector.Policy.boundedDefaults(), PolynomialWorkAuthority.unbounded());
        var root = parser.parseExactTerm("x^2-1/9");
        String formatted = ExpressionFormatter.format(root.expression());
        var compact = reusable.project(root, List.of(), formatted);
        var legacy = projector.project(root, List.of(), formatted);
        assertSame(root, compact.projected().orElseThrow());
        assertEquals(legacy.selectedRange(), compact.selectedRange());
        var legacyLiterals = legacy.projected().orElseThrow().literals();
        for (int index = 0; index < root.literals().size(); index++) {
            var original = root.literals().get(index);
            var copied = legacyLiterals.get(index);
            assertSame(original.node(), copied.node());
            assertEquals(original.startInclusive(), copied.startInclusive());
            assertEquals(original.endExclusive(), copied.endExclusive());
            assertEquals(original.evidence().certificateHash(), copied.evidence().certificateHash());
        }
        assertTrue(compact.work().totalWorkUnits() < legacy.work().totalWorkUnits());
        assertEquals(ExactParsedSubtermProjector.Status.POSITION_STALE,
            reusable.project(root, List.of(), "x^2-2").status());

        var padded = parser.parseExactTerm("  x^2-1/9 ");
        var projected = reusable.project(padded, List.of(), formatted).projected().orElseThrow();
        assertNotSame(padded, projected);
        assertEquals(root.source(), projected.source());
        assertEquals(root.rootSourceRange(), projected.rootSourceRange());
    }

    @Test
    void reusesExactBindingsForEqualNestedOccurrencesUnderATightBudget() {
        String occurrence = "((01 / 004.00))";
        var parsed = parser.parseExactTerm("f(g(" + occurrence + "), g(" + occurrence + "))");
        var policy = new ExactParsedSubtermProjector.Policy(10, 100, 10, 100, 100, 1_500);
        var reusable = ExactParsedSubtermProjector.withParserOccurrenceReuse(policy, PolynomialWorkAuthority.unbounded());
        var leftPath = List.of(0, 0);
        var rightPath = List.of(1, 0);
        var left = reusable.project(parsed, leftPath, "1 / 4");
        var right = reusable.project(parsed, rightPath, "1 / 4");
        var legacy = new ExactParsedSubtermProjector(policy).project(parsed, leftPath, "1 / 4");

        assertEquals(ExactParsedSubtermProjector.Status.BUDGET_INCONCLUSIVE, legacy.status());
        assertTrue(left.successful(), left.detailCode());
        assertTrue(right.successful(), right.detailCode());
        assertEquals(new ExactParsedTerm.SourceRange(4, 19), left.selectedRange().orElseThrow());
        assertEquals(new ExactParsedTerm.SourceRange(24, 39), right.selectedRange().orElseThrow());
        assertEquals(left.rangeCommitmentHash(), right.rangeCommitmentHash());
        assertNotEquals(left.certificateHash(), right.certificateHash());
        assertEquals(left.certificateHash(), reusable.project(parsed, leftPath, "1 / 4").certificateHash());

        var leftTerm = left.projected().orElseThrow();
        var rightTerm = right.projected().orElseThrow();
        assertEquals(leftTerm.expression(), rightTerm.expression());
        assertNotSame(leftTerm.expression(), rightTerm.expression());
        assertTrue(leftTerm.sourceRangeFor(rightTerm.expression()).isEmpty());
        assertTrue(leftTerm.literalFor(rightTerm.literals().getFirst().node()).isEmpty());
        assertEquals(occurrence, leftTerm.source());
        assertEquals(occurrence, rightTerm.source());
        assertEquals(new ExactParsedTerm.SourceRange(0, 15), leftTerm.rootSourceRange());
        assertEquals(List.of("01", "004.00"), leftTerm.literals().stream()
            .map(ExactParsedTerm.LiteralOccurrence::sourceLexeme).toList());
        var numerator = leftTerm.literals().get(0);
        var denominator = leftTerm.literals().get(1);
        assertEquals(2, numerator.startInclusive());
        assertEquals(4, numerator.endExclusive());
        assertEquals(7, denominator.startInclusive());
        assertEquals(13, denominator.endExclusive());
        assertEquals(ExactRational.integer(4), denominator.exactValue());
        assertSame(parsed.literals().get(0).evidence(), numerator.evidence());
        assertSame(parsed.literals().get(1).evidence(), denominator.evidence());
        assertEquals(0L, left.work().units("projection.revalidation-literal-code-units"));

        var again = reusable.project(leftTerm, List.of(1), "4");
        assertTrue(again.successful(), again.detailCode());
        assertEquals(new ExactParsedTerm.SourceRange(7, 13), again.selectedRange().orElseThrow());
        assertEquals("004.00", again.projected().orElseThrow().source());
        assertSame(denominator.evidence(), again.projected().orElseThrow().literals().getFirst().evidence());
    }

    @Test
    void reusesParserBindingsWhenRootSourceHasOuterWhitespace() {
        var parsed = parser.parseExactTerm("\t  x^2 - 01.250 \n");
        var other = parser.parseExactTerm("   x^2 - 01.250 \t");
        var policy = new ExactParsedSubtermProjector.Policy(10, 100, 10, 100, 100, 1_500);
        var reusable = ExactParsedSubtermProjector.withParserOccurrenceReuse(policy, PolynomialWorkAuthority.unbounded());
        var result = reusable.project(parsed, List.of(), "x ^ 2 - 1.25");
        var sameRangeOtherSource = reusable.project(other, List.of(), "x ^ 2 - 1.25");

        assertTrue(result.successful(), result.detailCode());
        assertTrue(sameRangeOtherSource.successful(), sameRangeOtherSource.detailCode());
        var projected = result.projected().orElseThrow();
        assertEquals(new ExactParsedTerm.SourceRange(3, 15), result.selectedRange().orElseThrow());
        assertEquals(result.selectedRange(), sameRangeOtherSource.selectedRange());
        assertNotEquals(result.rootSourceHash(), sameRangeOtherSource.rootSourceHash());
        assertNotEquals(result.certificateHash(), sameRangeOtherSource.certificateHash());
        assertSame(parsed.expression(), projected.expression());
        assertEquals("x^2 - 01.250", projected.source());
        assertEquals(new ExactParsedTerm.SourceRange(0, 12), projected.rootSourceRange());
        var decimal = projected.literals().getLast();
        assertEquals(6, decimal.startInclusive());
        assertEquals(12, decimal.endExclusive());
        assertEquals("01.250", decimal.sourceLexeme());
        assertSame(parsed.literals().getLast().evidence(), decimal.evidence());
        assertTrue(new ExactParsedUnivariatePolynomialView().analyze(projected).supported());
    }

    @Test
    void refusesToReuseHandAssembledBindingsEvenWithAGenuineParserNodeAndLiteral() {
        var original = parser.parseExactTerm("01+x");
        var sum = (BinaryExpr) original.expression();
        var ranges = new IdentityHashMap<Expr, ExactParsedTerm.SourceRange>();
        ranges.put(sum, new ExactParsedTerm.SourceRange(0, 4));
        ranges.put(sum.left(), new ExactParsedTerm.SourceRange(0, 2));
        ranges.put(sum.right(), new ExactParsedTerm.SourceRange(3, 4));
        var forged = new ExactParsedTerm("01-y", sum, original.literals(), ranges);
        var reusable = ExactParsedSubtermProjector.withParserOccurrenceReuse(projector.policy(), PolynomialWorkAuthority.unbounded());

        assertEquals(ExactParsedSubtermProjector.Status.UNSUPPORTED,
            reusable.project(forged, List.of(1), "x").status());
        assertEquals(ExactParsedSubtermProjector.Status.UNSUPPORTED,
            reusable.project(forged, List.of(), "1 + x").status());
    }

    @Test
    void rejectsStaleTextOnTheReusedPathWithoutIssuingAProjection() {
        var original = parser.parseExactTerm("f((01 / 004.00))");
        var policy = new ExactParsedSubtermProjector.Policy(10, 100, 10, 100, 100, 1_500);
        var reusable = ExactParsedSubtermProjector.withParserOccurrenceReuse(policy, PolynomialWorkAuthority.unbounded());
        var result = reusable.project(original, List.of(0), "1 / 5");

        assertEquals(ExactParsedSubtermProjector.Status.POSITION_STALE, result.status());
        assertEquals("1 / 4", result.actualFormattedText().orElseThrow());
        assertEquals(new ExactParsedTerm.SourceRange(2, 15), result.selectedRange().orElseThrow());
        assertTrue(result.rootSourceHash().isPresent());
        assertTrue(result.rangeCommitmentHash().isPresent());
        assertTrue(result.work().units("projection.reused-parser-observed-text-hash-code-units") > 0);
        assertTrue(result.projected().isEmpty());
    }

    @Test
    void admitsReuseWorkThroughTheSharedBudgetAndRetainsEarlierChargesOnFailure() {
        var parsed = parser.parseExactTerm("f((01 / 004.00))");
        var policy = ExactParsedSubtermProjector.Policy.boundedDefaults();
        var baseline = ExactParsedSubtermProjector.withParserOccurrenceReuse(policy, PolynomialWorkAuthority.unbounded())
            .project(parsed, List.of(0), "1 / 4");
        long required = baseline.work().totalWorkUnits();
        var authority = new BoundedAuthority(required - 1);
        var truncated = ExactParsedSubtermProjector.withParserOccurrenceReuse(policy, authority).project(parsed, List.of(0), "1 / 4");

        assertTrue(baseline.successful(), baseline.detailCode());
        assertTrue(baseline.work().units("projection.shifted-range-bindings") > 0);
        assertTrue(baseline.work().units("projection.shifted-literal-bindings") > 0);
        assertTrue(baseline.work().units("projection.staleness-format-code-units") > 0);
        assertEquals(ExactParsedSubtermProjector.Status.BUDGET_INCONCLUSIVE, truncated.status());
        assertEquals(authority.stages, truncated.work().stages());
        assertTrue(truncated.work().totalWorkUnits() > 0);
        assertTrue(truncated.work().within(required - 1));
        assertTrue(truncated.projected().isEmpty());

        var exact = new BoundedAuthority(required);
        var succeeded = ExactParsedSubtermProjector.withParserOccurrenceReuse(policy, exact).project(parsed, List.of(0), "1 / 4");
        assertTrue(succeeded.successful(), succeeded.detailCode());
        assertEquals(baseline.work(), succeeded.work());
        assertEquals(exact.stages, succeeded.work().stages());
    }

    private static final class BoundedAuthority implements PolynomialWorkAuthority {
        private final Map<String, Long> stages = new HashMap<>();
        private long remaining;

        private BoundedAuthority(long remaining) {
            this.remaining = remaining;
        }

        @Override
        public void consume(PolynomialWorkLedger work) {
            if (work.totalWorkUnits() > remaining) {
                throw new PolynomialWorkAuthority.LimitReached();
            }
            remaining -= work.totalWorkUnits();
            work.stages().forEach((stage, units) -> stages.merge(stage, units, Math::addExact));
        }

        @Override
        public long remainingOpaqueWorkUnits() {
            return remaining;
        }
    }

    @Test
    void projectsANestedExactPolynomialWithoutReparseOrNewAstNodes() {
        ExactParsedTerm root = parser.parseExactTerm(
            "f(1/3*x^2 - 2/3*x + 1/3, y)");
        Expr selected = ((FunctionExpr) root.expression())
            .arguments().getFirst();
        String expected = ExpressionFormatter.format(selected);

        var first = projector.project(root, List.of(0), expected);
        var repeated = projector.project(root, List.of(0), expected);

        assertTrue(first.successful(), first.detailCode());
        ExactParsedTerm projected = first.projected().orElseThrow();
        assertSame(selected, projected.expression());
        assertEquals(
            "1/3*x^2 - 2/3*x + 1/3",
            projected.source());
        assertEquals(7, projected.literals().size());
        assertEquals(
            ExactRational.ONE,
            projected.literals().get(0).exactValue());
        assertEquals(
            ExactRational.integer(3),
            projected.literals().get(1).exactValue());
        assertEquals(
            ExactRational.integer(2),
            projected.literals().get(2).exactValue());
        assertSame(
            projected.literals().getFirst(),
            projected.literalFor(
                projected.literals().getFirst().node())
                .orElseThrow());
        assertEquals(
            new ExactParsedTerm.SourceRange(
                0,
                projected.source().length()),
            projected.rootSourceRange());
        assertTrue(
            first.work().units("projection.path-navigation-steps") > 0);
        assertTrue(
            first.work().units("projection.shifted-literal-bindings") > 0);
        assertTrue(first.work().within(projector.policy().maxWorkUnits()));
        assertEquals(first.certificateHash(), repeated.certificateHash());
        assertTrue(first.certificateHash().matches("sha256:[0-9a-f]{64}"));

        var polynomial = new ExactParsedUnivariatePolynomialView()
            .analyze(projected);
        assertTrue(polynomial.supported(), polynomial.detailCode());
    }

    @Test
    void retainsGroupingInTheProjectedSourceButNotInTheAstIdentity() {
        ExactParsedTerm root = parser.parseExactTerm(
            "a + ((x^2 - 1))");
        Expr selected = ((BinaryExpr) root.expression()).right();

        var result = projector.project(
            root,
            List.of(1),
            ExpressionFormatter.format(selected));

        assertTrue(result.successful(), result.detailCode());
        ExactParsedTerm projected = result.projected().orElseThrow();
        assertSame(selected, projected.expression());
        assertEquals("((x^2 - 1))", projected.source());
        assertEquals(
            new ExactParsedTerm.SourceRange(
                0,
                projected.source().length()),
            projected.rootSourceRange());
        assertEquals("2", projected.literals().get(0).sourceLexeme());
        assertEquals("1", projected.literals().get(1).sourceLexeme());
    }

    @Test
    void shiftsLiteralRangesIntoTheSelectedCoordinateSystem() {
        ExactParsedTerm root = parser.parseExactTerm(
            "q + ((01 / 004))");
        Expr selected = ((BinaryExpr) root.expression()).right();

        var result = projector.project(
            root,
            List.of(1),
            ExpressionFormatter.format(selected));

        ExactParsedTerm projected = result.projected().orElseThrow();
        assertEquals("((01 / 004))", projected.source());
        assertEquals(2, projected.literals().size());
        assertEquals(2, projected.literals().get(0).startInclusive());
        assertEquals(4, projected.literals().get(0).endExclusive());
        assertEquals(7, projected.literals().get(1).startInclusive());
        assertEquals(10, projected.literals().get(1).endExclusive());
        assertEquals(
            ExactRational.ONE,
            projected.literals().get(0).exactValue());
        assertEquals(
            ExactRational.integer(4),
            projected.literals().get(1).exactValue());
    }

    @Test
    void keepsEqualSubtreesAtDifferentPathsAsDifferentOccurrences() {
        ExactParsedTerm root = parser.parseExactTerm(
            "(x^2 - 1) + (x^2 - 1)");
        BinaryExpr sum = (BinaryExpr) root.expression();
        assertEquals(sum.left(), sum.right());
        assertNotSame(sum.left(), sum.right());

        var left = projector.project(
            root,
            List.of(0),
            ExpressionFormatter.format(sum.left()));
        var right = projector.project(
            root,
            List.of(1),
            ExpressionFormatter.format(sum.right()));

        assertTrue(left.successful(), left.detailCode());
        assertTrue(right.successful(), right.detailCode());
        assertEquals(
            left.projected().orElseThrow().source(),
            right.projected().orElseThrow().source());
        assertNotEquals(
            left.selectedRange().orElseThrow(),
            right.selectedRange().orElseThrow());
        assertNotEquals(left.certificateHash(), right.certificateHash());
    }

    @Test
    void rejectsAStaleFormatterSnapshotWithoutCreatingAProjection() {
        ExactParsedTerm root = parser.parseExactTerm("x^2 - 1");

        var result = projector.project(root, List.of(), "x ^ 2 + 1");

        assertFalse(result.successful());
        assertEquals(
            ExactParsedSubtermProjector.Status.POSITION_STALE,
            result.status());
        assertEquals(
            "SELECTED_POSITION_TEXT_IS_STALE",
            result.detailCode());
        assertEquals(
            "x ^ 2 - 1",
            result.actualFormattedText().orElseThrow());
        assertTrue(result.projected().isEmpty());
        assertTrue(result.selectedRange().isPresent());
        assertTrue(result.rangeCommitmentHash().isPresent());
    }

    @Test
    void distinguishesMissingPathsFromSourceFreeSyntheticNodes() {
        ExactParsedTerm ordinary = parser.parseExactTerm("x + 1");
        var missing = projector.project(ordinary, List.of(2), "missing");

        ExactParsedTerm unary = parser.parseExactTerm("-x");
        var syntheticZero = projector.project(unary, List.of(0), "0");

        assertEquals(
            ExactParsedSubtermProjector.Status.POSITION_NOT_PRESENT,
            missing.status());
        assertEquals(
            "SELECTED_PATH_IS_NOT_PRESENT",
            missing.detailCode());
        assertEquals(
            ExactParsedSubtermProjector.Status.UNSUPPORTED,
            syntheticZero.status());
        assertEquals(
            "SELECTED_OCCURRENCE_HAS_NO_SOURCE_RANGE",
            syntheticZero.detailCode());
        assertTrue(missing.projected().isEmpty());
        assertTrue(syntheticZero.projected().isEmpty());
    }

    @Test
    void projectsTheRootWithoutRetainingOuterWhitespace() {
        ExactParsedTerm root = parser.parseExactTerm("  x + 1  ");

        var result = projector.project(root, List.of(), "x + 1");

        assertTrue(result.successful(), result.detailCode());
        assertSame(root.expression(), result.projected().orElseThrow()
            .expression());
        assertEquals("x + 1", result.projected().orElseThrow().source());
        assertEquals(
            new ExactParsedTerm.SourceRange(2, 7),
            result.selectedRange().orElseThrow());
    }

    @Test
    void reportsStructuralAndWorkLimitsAsBudgetInconclusive() {
        ExactParsedTerm root = parser.parseExactTerm("x + 1");
        var noDepth = new ExactParsedSubtermProjector(
            new ExactParsedSubtermProjector.Policy(
                0,
                100,
                100,
                100,
                100,
                10_000));
        var noWork = new ExactParsedSubtermProjector(
            new ExactParsedSubtermProjector.Policy(
                10,
                100,
                100,
                100,
                100,
                1));

        var depthResult = noDepth.project(root, List.of(0), "x");
        var workResult = noWork.project(root, List.of(), "x + 1");

        assertEquals(
            ExactParsedSubtermProjector.Status.BUDGET_INCONCLUSIVE,
            depthResult.status());
        assertEquals("MAX_PATH_DEPTH_EXCEEDED", depthResult.detailCode());
        assertEquals(
            ExactParsedSubtermProjector.Status.BUDGET_INCONCLUSIVE,
            workResult.status());
        assertEquals(
            "SUBTERM_PROJECTION_WORK_BUDGET_EXCEEDED",
            workResult.detailCode());
        assertTrue(depthResult.projected().isEmpty());
        assertTrue(workResult.projected().isEmpty());
    }
}
