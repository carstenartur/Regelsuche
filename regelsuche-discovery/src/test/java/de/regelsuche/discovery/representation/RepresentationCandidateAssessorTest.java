package de.regelsuche.discovery.representation;

import static de.regelsuche.ast.BinaryOperator.ADD;
import static de.regelsuche.ast.BinaryOperator.MUL;
import static de.regelsuche.ast.BinaryOperator.POW;
import static de.regelsuche.ast.BinaryOperator.SUB;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.COMPRESSION_BLOCKED_BY_INTRODUCED_SYMBOLS;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.COMPRESSION_MATERIAL_MULTI_DIMENSIONAL;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.TYPE_DOWNSTREAM_CAPABILITY_BRIDGE;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.TYPE_KNOWN_WHOLE_FORM_BRIDGE;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.TYPE_NO_MATERIAL_REPRESENTATION_GAIN;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.TYPE_SUBEXPRESSION_COMPRESSION;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.TYPE_WHOLE_EXPRESSION_COMPRESSION;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.WARNING_UNSATISFIED_KNOWN_STRUCTURE_ASSUMPTIONS;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.WARNING_VALIDATION_BELOW_SYMBOLIC_CONFIRMATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.validation.CandidateProofStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class RepresentationCandidateAssessorTest {
    private static final CandidateProofStatus VERIFIED =
        CandidateProofStatus.SYMBOLICALLY_VERIFIED;
    private final SemanticDescriptionMeasurer measurer =
        new SemanticDescriptionMeasurer();

    @Test
    void recognizesTargetFreeMultiDimensionalCompression() {
        var result = assess(
            KnownStructureCatalog.empty(),
            "a^2 + 2*a*b + b^2",
            "(a + b)^2",
            VERIFIED
        );

        assertEquals(COMPRESSION_MATERIAL_MULTI_DIMENSIONAL,
            result.compressionStatus());
        assertType(result, TYPE_WHOLE_EXPRESSION_COMPRESSION);
        assertTrue(result.materialRepresentationGain());
        assertTrue(result.claimEligible());
        assertTrue(result.scopedCompressionDelta().improvedDimensions().size() >= 2);
    }

    @Test
    void keepsAcReorderingAsNonMaterialControl() {
        var result = assess(
            KnownStructureCatalog.empty(), "a + b", "b + a", VERIFIED);

        assertEquals(List.of(TYPE_NO_MATERIAL_REPRESENTATION_GAIN),
            result.candidateTypes());
        assertFalse(result.materialRepresentationGain());
        assertFalse(result.claimEligible());
    }

    @Test
    void blocksCompressionByInventedFunctionAlias() {
        var result = assess(
            KnownStructureCatalog.empty(),
            "a^2 + 2*a*b + b^2",
            "shortcut(a, b)",
            VERIFIED
        );

        assertEquals(COMPRESSION_BLOCKED_BY_INTRODUCED_SYMBOLS,
            result.compressionStatus());
        assertEquals(List.of("shortcut"), result.introducedFunctionSymbols());
        assertFalse(result.materialRepresentationGain());
    }

    @Test
    void acceptsLongerFormWhenItUnlocksFactorization() {
        var result = assess(
            catalog(differenceOfSquares()),
            "x^4 + 4*y^4",
            "(x^2 + 2*y^2)^2 - (2*x*y)^2",
            VERIFIED
        );

        assertType(result, TYPE_KNOWN_WHOLE_FORM_BRIDGE);
        assertType(result, TYPE_DOWNSTREAM_CAPABILITY_BRIDGE);
        assertTrue(result.newlyUnlockedConsequences().stream().anyMatch(unlock ->
            unlock.consequenceId().equals("rule:factor-difference-of-squares")
                && unlock.occurrencePath().isRoot()));
        assertTrue(result.materialRepresentationGain());
        assertTrue(result.claimEligible());
        assertFalse(result.candidateTypes().contains(
            TYPE_WHOLE_EXPRESSION_COMPRESSION));
    }

    @Test
    void treatsSameCapabilityAtNewOccurrenceAsNewOpportunity() {
        var result = assess(
            catalog(perfectSquare()),
            "(a + b)^2 + (c^2 + 2*c*d + d^2)",
            "(a + b)^2 + (c + d)^2",
            VERIFIED
        );

        assertTrue(result.newlyUnlockedConsequences().stream().anyMatch(unlock ->
            unlock.consequenceId().equals("rule:square-reasoning")
                && unlock.occurrencePath().equals(path(1))));
    }

    @Test
    void compressesOneOccurrenceWithoutChangingItsContext() {
        var proposal = RepresentationCandidateProposal.subexpression(
            "z + (a^2 + 2*a*b + b^2)",
            "z + (a + b)^2",
            path(1),
            List.of(),
            VERIFIED
        );
        var result = new RepresentationCandidateAssessor(
            KnownStructureCatalog.empty()).assess(proposal);

        assertType(result, TYPE_SUBEXPRESSION_COMPRESSION);
        assertTrue(result.materialRepresentationGain());
    }

    @Test
    void rejectsChangesOutsideDeclaredOccurrence() {
        var proposal = RepresentationCandidateProposal.subexpression(
            "z + (a^2 + 2*a*b + b^2)",
            "w + (a + b)^2",
            path(1),
            List.of(),
            VERIFIED
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> new RepresentationCandidateAssessor(
                KnownStructureCatalog.empty()).assess(proposal));
    }

    @Test
    void requiresDeclaredAssumptionsBeforeUnlockingConsequences() {
        var guarded = new KnownStructure(
            "guarded-quotient",
            "rational",
            PatternExpr.var("expression"),
            List.of("x != 0"),
            List.of("rule:guarded-cancellation"),
            "first-party"
        );
        var result = assess(catalog(guarded), "x", "x + 0", VERIFIED);

        assertTrue(result.newlyUnlockedConsequences().isEmpty());
        assertTrue(result.warnings().contains(
            WARNING_UNSATISFIED_KNOWN_STRUCTURE_ASSUMPTIONS));
    }

    @Test
    void leavesMaterialSignalIneligibleUntilSymbolicallyConfirmed() {
        var result = assess(
            KnownStructureCatalog.empty(),
            "a^2 + 2*a*b + b^2",
            "(a + b)^2",
            CandidateProofStatus.VALIDATED_BY_EXAMPLES
        );

        assertTrue(result.materialRepresentationGain());
        assertFalse(result.claimEligible());
        assertTrue(result.warnings().contains(
            WARNING_VALIDATION_BELOW_SYMBOLIC_CONFIRMATION));
    }

    @Test
    void exposesSharingAwareRawDimensions() {
        var metrics = measurer.measure("(x + 1) * (x + 1)");

        assertTrue(metrics.semanticValueOccurrences()
            > metrics.distinctSemanticValues());
        assertEquals(
            metrics.semanticValueOccurrences() - metrics.distinctSemanticValues(),
            metrics.repeatedSemanticValueSavings());
        assertTrue(metrics.variableSymbols().contains("x"));
    }

    @Test
    void countsSharedAstObjectAtEverySyntaxPosition() {
        var shared = new BinaryExpr(new VariableExpr("x"), ADD, new NumberExpr(1));
        var root = new BinaryExpr(shared, MUL, shared);
        var metrics = measurer.measure(root);

        assertEquals(metrics.astNodeCount(), metrics.semanticValueOccurrences());
        assertTrue(metrics.repeatedSemanticValueSavings() > 0);
    }

    @Test
    void compactSquareImprovesRawDimensions() {
        var expanded = measurer.measure("a^2 + 2*a*b + b^2");
        var compact = measurer.measure("(a + b)^2");

        assertTrue(expanded.tokenCount() > compact.tokenCount());
        assertTrue(expanded.astNodeCount() > compact.astNodeCount());
        assertTrue(expanded.operatorCount() > compact.operatorCount());
        assertTrue(expanded.distinctSemanticValues()
            > compact.distinctSemanticValues());
    }

    @Test
    void whitespaceDoesNotChangeMeasuredExpression() {
        assertEquals(measurer.measure("a+b"), measurer.measure("  a   +   b  "));
    }

    @Test
    void findsKnownStructureAtExactOccurrence() {
        var matcher = new KnownStructureMatcher(catalog(perfectSquare()));
        var matches = matcher.match("z + (a + b)^2");

        assertTrue(matches.stream().anyMatch(match ->
            match.structureId().equals("perfect-square")
                && match.occurrencePath().equals(path(1))));
        assertFalse(matches.stream().anyMatch(KnownStructureMatch::wholeExpression));
    }

    @Test
    void catalogIdentityIsIndependentOfInputOrder() {
        KnownStructure first = perfectSquare();
        KnownStructure second = new KnownStructure(
            "sum",
            "algebra",
            PatternExpr.op(ADD, PatternExpr.var("left"), PatternExpr.var("right")),
            List.of(),
            List.of(),
            "first-party"
        );

        assertEquals(
            new KnownStructureCatalog("v1", List.of(first, second)).contentHash(),
            new KnownStructureCatalog("v1", List.of(second, first)).contentHash());
    }

    @Test
    void duplicateStructureIdsFailClosed() {
        KnownStructure structure = perfectSquare();
        assertThrows(IllegalArgumentException.class,
            () -> new KnownStructureCatalog("v1", List.of(structure, structure)));
    }

    private static RepresentationCandidateAssessment assess(
        KnownStructureCatalog catalog,
        String source,
        String candidate,
        CandidateProofStatus status
    ) {
        return new RepresentationCandidateAssessor(catalog).assess(
            RepresentationCandidateProposal.whole(
                source, candidate, List.of(), status));
    }

    private static KnownStructureCatalog catalog(KnownStructure structure) {
        return new KnownStructureCatalog("test-v1", List.of(structure));
    }

    private static ExpressionOccurrencePath path(int... indexes) {
        return new ExpressionOccurrencePath(
            java.util.Arrays.stream(indexes).boxed().toList());
    }

    private static void assertType(
        RepresentationCandidateAssessment result,
        String type
    ) {
        assertTrue(result.candidateTypes().contains(type));
    }

    private static KnownStructure differenceOfSquares() {
        return new KnownStructure(
            "difference-of-squares",
            "algebra",
            PatternExpr.op(
                SUB,
                PatternExpr.op(POW, PatternExpr.var("left"), PatternExpr.num(2)),
                PatternExpr.op(POW, PatternExpr.var("right"), PatternExpr.num(2))
            ),
            List.of(),
            List.of("rule:factor-difference-of-squares"),
            "first-party"
        );
    }

    private static KnownStructure perfectSquare() {
        return new KnownStructure(
            "perfect-square",
            "algebra",
            PatternExpr.op(POW, PatternExpr.var("base"), PatternExpr.num(2)),
            List.of(),
            List.of("rule:square-reasoning"),
            "first-party"
        );
    }
}
