package de.regelsuche.discovery.representation;

import static de.regelsuche.ast.BinaryOperator.POW;
import static de.regelsuche.ast.BinaryOperator.SUB;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.transform.PatternExpr;
import de.regelsuche.validation.CandidateProofStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class RepresentationCandidateAssessorTest {
    @Test
    void recognizesTargetFreeMultiDimensionalCompression() {
        RepresentationCandidateAssessment assessment =
            assessor(KnownStructureCatalog.empty()).assess(
                RepresentationCandidateProposal.whole(
                    "a^2 + 2*a*b + b^2",
                    "(a + b)^2",
                    List.of(),
                    CandidateProofStatus.SYMBOLICALLY_VERIFIED
                )
            );

        assertEquals(
            SemanticCompressionStatus.MATERIAL_MULTI_DIMENSIONAL,
            assessment.compressionStatus());
        assertTrue(assessment.candidateTypes().contains(
            RepresentationCandidateType.WHOLE_EXPRESSION_COMPRESSION));
        assertTrue(assessment.materialRepresentationGain());
        assertTrue(assessment.claimEligible());
        assertTrue(assessment.scopedCompressionDelta()
            .improvedDimensions().size() >= 2);
    }

    @Test
    void acReorderingIsRetainedAsANonMaterialControl() {
        RepresentationCandidateAssessment assessment =
            assessor(KnownStructureCatalog.empty()).assess(
                RepresentationCandidateProposal.whole(
                    "a + b",
                    "b + a",
                    List.of(),
                    CandidateProofStatus.SYMBOLICALLY_VERIFIED
                )
            );

        assertEquals(
            List.of(
                RepresentationCandidateType.NO_MATERIAL_REPRESENTATION_GAIN),
            assessment.candidateTypes());
        assertFalse(assessment.materialRepresentationGain());
        assertFalse(assessment.claimEligible());
    }

    @Test
    void introducedFunctionCannotManufactureCompressionCredit() {
        RepresentationCandidateAssessment assessment =
            assessor(KnownStructureCatalog.empty()).assess(
                RepresentationCandidateProposal.whole(
                    "a^2 + 2*a*b + b^2",
                    "shortcut(a, b)",
                    List.of(),
                    CandidateProofStatus.SYMBOLICALLY_VERIFIED
                )
            );

        assertEquals(
            SemanticCompressionStatus.BLOCKED_BY_INTRODUCED_SYMBOLS,
            assessment.compressionStatus());
        assertTrue(assessment.introducedFunctionSymbols().contains("shortcut"));
        assertFalse(assessment.candidateTypes().contains(
            RepresentationCandidateType.WHOLE_EXPRESSION_COMPRESSION));
        assertFalse(assessment.materialRepresentationGain());
    }

    @Test
    void longerDifferenceOfSquaresFormQualifiesByUnlockedConsequence() {
        KnownStructureCatalog catalog = new KnownStructureCatalog(
            "algebra-v1",
            List.of(differenceOfSquares())
        );
        RepresentationCandidateAssessment assessment = assessor(catalog).assess(
            RepresentationCandidateProposal.whole(
                "x^4 + 4*y^4",
                "(x^2 + 2*y^2)^2 - (2*x*y)^2",
                List.of(),
                CandidateProofStatus.SYMBOLICALLY_VERIFIED
            )
        );

        assertTrue(assessment.candidateTypes().contains(
            RepresentationCandidateType.KNOWN_WHOLE_FORM_BRIDGE));
        assertTrue(assessment.candidateTypes().contains(
            RepresentationCandidateType.DOWNSTREAM_CAPABILITY_BRIDGE));
        assertTrue(assessment.newlyUnlockedConsequences().contains(
            "rule:factor-difference-of-squares"));
        assertTrue(assessment.materialRepresentationGain());
        assertTrue(assessment.claimEligible());
        assertFalse(assessment.candidateTypes().contains(
            RepresentationCandidateType.WHOLE_EXPRESSION_COMPRESSION));
    }

    @Test
    void occurrenceLocalCompressionPreservesTheEnclosingContext() {
        RepresentationCandidateAssessment assessment =
            assessor(KnownStructureCatalog.empty()).assess(
                RepresentationCandidateProposal.subexpression(
                    "z + (a^2 + 2*a*b + b^2)",
                    "z + (a + b)^2",
                    new ExpressionOccurrencePath(List.of(1)),
                    List.of(),
                    CandidateProofStatus.SYMBOLICALLY_VERIFIED
                )
            );

        assertTrue(assessment.candidateTypes().contains(
            RepresentationCandidateType.SUBEXPRESSION_COMPRESSION));
        assertTrue(assessment.materialRepresentationGain());
    }

    @Test
    void occurrenceLocalProposalRejectsChangesOutsideTheDeclaredPath() {
        RepresentationCandidateProposal proposal =
            RepresentationCandidateProposal.subexpression(
                "z + (a^2 + 2*a*b + b^2)",
                "w + (a + b)^2",
                new ExpressionOccurrencePath(List.of(1)),
                List.of(),
                CandidateProofStatus.SYMBOLICALLY_VERIFIED
            );

        assertThrows(
            IllegalArgumentException.class,
            () -> assessor(KnownStructureCatalog.empty()).assess(proposal)
        );
    }

    @Test
    void knownStructureConsequencesRequireTheirDeclaredAssumptions() {
        KnownStructure guarded = new KnownStructure(
            "guarded-quotient",
            "rational",
            PatternExpr.var("expression"),
            List.of("x != 0"),
            List.of("rule:guarded-cancellation"),
            "first-party"
        );
        RepresentationCandidateAssessment assessment =
            assessor(new KnownStructureCatalog("rational-v1", List.of(guarded)))
                .assess(
                    RepresentationCandidateProposal.whole(
                        "x",
                        "x + 0",
                        List.of(),
                        CandidateProofStatus.SYMBOLICALLY_VERIFIED
                    )
                );

        assertTrue(assessment.newlyUnlockedConsequences().isEmpty());
        assertTrue(assessment.warnings().contains(
            RepresentationAssessmentWarning
                .UNSATISFIED_KNOWN_STRUCTURE_ASSUMPTIONS));
    }

    @Test
    void materialSignalRemainsIneligibleUntilSymbolicallyConfirmed() {
        RepresentationCandidateAssessment assessment =
            assessor(KnownStructureCatalog.empty()).assess(
                RepresentationCandidateProposal.whole(
                    "a^2 + 2*a*b + b^2",
                    "(a + b)^2",
                    List.of(),
                    CandidateProofStatus.VALIDATED_BY_EXAMPLES
                )
            );

        assertTrue(assessment.materialRepresentationGain());
        assertFalse(assessment.claimEligible());
        assertTrue(assessment.warnings().contains(
            RepresentationAssessmentWarning
                .VALIDATION_BELOW_SYMBOLIC_CONFIRMATION));
    }

    @Test
    void assessmentCodecIsDeterministicStrictAndRoundTrips() {
        RepresentationCandidateAssessment assessment =
            assessor(new KnownStructureCatalog(
                "algebra-v1",
                List.of(differenceOfSquares())
            )).assess(
                RepresentationCandidateProposal.whole(
                    "x^4 + 4*y^4",
                    "(x^2 + 2*y^2)^2 - (2*x*y)^2",
                    List.of(),
                    CandidateProofStatus.SYMBOLICALLY_VERIFIED
                )
            );
        RepresentationCandidateAssessmentCodec codec =
            new RepresentationCandidateAssessmentCodec();

        String first = codec.encode(assessment);
        String second = codec.encode(assessment);

        assertEquals(first, second);
        assertEquals(assessment, codec.decode(first));
        assertEquals(codec.semanticHash(assessment), codec.semanticHash(assessment));
        assertThrows(
            IllegalArgumentException.class,
            () -> codec.decode(first.replaceFirst(
                "\\{",
                "{\"unknown\":true,"
            ))
        );
    }

    private static RepresentationCandidateAssessor assessor(
        KnownStructureCatalog catalog
    ) {
        return new RepresentationCandidateAssessor(catalog);
    }

    private static KnownStructure differenceOfSquares() {
        return new KnownStructure(
            "difference-of-squares",
            "algebra",
            PatternExpr.op(
                SUB,
                PatternExpr.op(
                    POW,
                    PatternExpr.var("left"),
                    PatternExpr.num(2)
                ),
                PatternExpr.op(
                    POW,
                    PatternExpr.var("right"),
                    PatternExpr.num(2)
                )
            ),
            List.of(),
            List.of("rule:factor-difference-of-squares"),
            "first-party"
        );
    }
}
