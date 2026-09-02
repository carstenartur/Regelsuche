package de.regelsuche.discovery.representation;

import static de.regelsuche.ast.BinaryOperator.POW;
import static de.regelsuche.ast.BinaryOperator.SUB;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.TYPE_KNOWN_WHOLE_FORM_BRIDGE;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.TYPE_NO_MATERIAL_REPRESENTATION_GAIN;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryInformationBoundary.Track.R1_TARGET_FREE_COMPRESSION;
import static de.regelsuche.discovery.representation
    .RepresentationSalienceCaseAudit.ReferenceReachability.UNSUPPORTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.knowledge.RuleProfile;
import de.regelsuche.transform.PatternExpr;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TargetFreeHeldOutSaliencePilotTest {
    @Test
    void preservesCurrentRepeatedTermRecognitionMiss() {
        String source = "x + x";
        CandidateEvidence repeated = candidate(
            source,
            "2 * x",
            "ast_double_term"
        );

        var ranked = TargetFreeHeldOutSaliencePilot.rankCandidates(
            benchmarkCase(source),
            List.of(repeated),
            KnownStructureCatalog.empty()
        );

        assertEquals(1, ranked.size());
        assertEquals(
            List.of(TYPE_NO_MATERIAL_REPRESENTATION_GAIN),
            ranked.getFirst().candidateTypes()
        );
        assertFalse(ranked.getFirst().recognized());
        assertEquals(1, ranked.getFirst().rank());
    }

    @Test
    void ranksANewlyExposedKnownStructureAheadOfAnUnchangedForm() {
        String source = "x * x - y * y";
        CandidateEvidence unchanged = candidate(
            source,
            source,
            "identity-control"
        );
        CandidateEvidence differenceOfSquares = candidate(
            source,
            "x ^ 2 - y ^ 2",
            "product-to-power"
        );
        KnownStructure structure = new KnownStructure(
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
            "test"
        );

        var ranked = TargetFreeHeldOutSaliencePilot.rankCandidates(
            benchmarkCase(source),
            List.of(unchanged, differenceOfSquares),
            new KnownStructureCatalog("test-v1", List.of(structure))
        );

        assertEquals(2, ranked.size());
        assertEquals("x ^ 2 - y ^ 2", ranked.getFirst().expression());
        assertTrue(ranked.getFirst().recognized());
        assertTrue(ranked.getFirst().candidateTypes().contains(
            TYPE_KNOWN_WHOLE_FORM_BRIDGE
        ));
        assertFalse(ranked.get(1).recognized());
    }

    @Test
    void representationIdentityIsIndependentOfAssumptionOrder() {
        assertEquals(
            TargetFreeHeldOutSaliencePilot.representationId(
                "x / y", List.of("x != 0", "y != 0")),
            TargetFreeHeldOutSaliencePilot.representationId(
                "x / y", List.of("y != 0", "x != 0"))
        );
    }

    @Test
    void doesNotOverclaimIndependentReachabilityEvidence() {
        assertEquals(
            UNSUPPORTED,
            TargetFreeHeldOutSaliencePilot.RETAINED_MATRIX_REACHABILITY
        );
        assertTrue(
            TargetFreeHeldOutSaliencePilot.PILOT_CLAIM_BOUNDARY.contains(
                "without oracle confirmation"
            )
        );
        assertTrue(
            TargetFreeHeldOutSaliencePilot.PILOT_CLAIM_BOUNDARY.contains(
                "retained-candidate projections"
            )
        );
    }

    @Test
    void representsMissingRetainedReferencesAsUnresolvedPreRetentionRows() {
        var unresolved = new TargetFreeHeldOutSaliencePilot.PilotSummary(
            1,
            1,
            0,
            0,
            0,
            0,
            0,
            0,
            1,
            0,
            0,
            0,
            0,
            List.of(new TargetFreeHeldOutSaliencePilot.PilotCaseSummary(
                "case", 1, 0, 0, 0, 0
            )),
            Map.of("UNSUPPORTED", 1)
        );

        assertEquals(0, unresolved.retainedReferencePositiveRows());
        assertEquals(
            0,
            unresolved.cases().getFirst().retainedReferenceRows()
        );
        assertEquals(1, unresolved.preRetentionUnresolvedRows());
        assertThrows(
            IllegalArgumentException.class,
            () -> new TargetFreeHeldOutSaliencePilot.PilotSummary(
                1,
                1,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                unresolved.cases(),
                unresolved.localizationCounts()
            )
        );
    }

    private static CaseSpec benchmarkCase(String source) {
        return new CaseSpec(
            "salience-test",
            source,
            List.of(),
            R1_TARGET_FREE_COMPRESSION,
            RuleProfile.MINIMAL_KERNEL,
            List.of(),
            List.of(),
            new WorkBudget(4, 100, 100, 100, 20, 20, 1, 2, 20)
        );
    }

    private static CandidateEvidence candidate(
        String source,
        String expression,
        String ruleId
    ) {
        return CandidateEvidence.create(
            expression,
            List.of(),
            1,
            List.of(source, expression),
            List.of(ruleId),
            List.of(ruleId),
            true,
            false
        );
    }
}
