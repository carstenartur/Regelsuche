package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation
    .RepresentationSalienceCaseAudit.CaseRole.NEGATIVE_OR_ALIAS_CONTROL;
import static de.regelsuche.discovery.representation
    .RepresentationSalienceCaseAudit.CaseRole.POSITIVE_REFERENCE;
import static de.regelsuche.discovery.representation
    .RepresentationSalienceCaseAudit.ExpertVerdict.CONSENSUS_INTERESTING;
import static de.regelsuche.discovery.representation
    .RepresentationSalienceCaseAudit.ExpertVerdict.NOT_EVALUATED;
import static de.regelsuche.discovery.representation
    .RepresentationSalienceCaseAudit.Localization.INVALID_OR_FALSE_POSITIVE;
import static de.regelsuche.discovery.representation
    .RepresentationSalienceCaseAudit.Localization.REACHABLE_NOT_REACHED_BY_POLICY;
import static de.regelsuche.discovery.representation
    .RepresentationSalienceCaseAudit.Localization.RECOGNIZED_NOT_RANKED;
import static de.regelsuche.discovery.representation
    .RepresentationSalienceCaseAudit.Localization.RETAINED_NOT_RECOGNIZED;
import static de.regelsuche.discovery.representation
    .RepresentationSalienceCaseAudit.ReferenceReachability.INCONCLUSIVE;
import static de.regelsuche.discovery.representation
    .RepresentationSalienceCaseAudit.ReferenceReachability.REACHABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.representation
    .RepresentationSalienceCaseAudit;
import de.regelsuche.discovery.representation
    .RepresentationSalienceStageSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class RepresentationSalienceAuditTest {
    @Test
    void distinguishesPolicyMissFromReachedButUnrecognized() {
        RepresentationSalienceCaseAudit policyMiss = positive(
            "policy-miss",
            RepresentationSalienceStageSet.empty(),
            RepresentationSalienceStageSet.empty(),
            RepresentationSalienceStageSet.empty(),
            RepresentationSalienceStageSet.empty(),
            RepresentationSalienceStageSet.empty(),
            NOT_EVALUATED
        );
        RepresentationSalienceStageSet reached =
            RepresentationSalienceStageSet.of(List.of(
                hash("historical-form")
            ));
        RepresentationSalienceCaseAudit recognitionMiss = positive(
            "recognition-miss",
            reached,
            reached,
            reached,
            RepresentationSalienceStageSet.empty(),
            RepresentationSalienceStageSet.empty(),
            NOT_EVALUATED
        );

        assertEquals(
            REACHABLE_NOT_REACHED_BY_POLICY,
            policyMiss.localization()
        );
        assertEquals(RETAINED_NOT_RECOGNIZED, recognitionMiss.localization());
        assertNotEquals(policyMiss.contentHash(), recognitionMiss.contentHash());
    }

    @Test
    void distinguishesRecognizedButUnrankedCandidate() {
        RepresentationSalienceStageSet candidate =
            RepresentationSalienceStageSet.of(List.of(
                hash("capability-bridge")
            ));
        RepresentationSalienceCaseAudit result = positive(
            "ranking-miss",
            candidate,
            candidate,
            candidate,
            candidate,
            RepresentationSalienceStageSet.empty(),
            NOT_EVALUATED
        );

        assertEquals(RECOGNIZED_NOT_RANKED, result.localization());
    }

    @Test
    void derivesConditionalRecallWithoutDroppingInconclusiveCases() {
        RepresentationSalienceStageSet first =
            RepresentationSalienceStageSet.of(List.of(hash("first")));
        RepresentationSalienceStageSet second =
            RepresentationSalienceStageSet.of(List.of(hash("second")));
        RepresentationSalienceCaseAudit detected = positive(
            "detected",
            first,
            first,
            first,
            first,
            first,
            CONSENSUS_INTERESTING
        );
        RepresentationSalienceCaseAudit recognitionMiss = positive(
            "recognition-miss",
            second,
            second,
            second,
            RepresentationSalienceStageSet.empty(),
            RepresentationSalienceStageSet.empty(),
            NOT_EVALUATED
        );
        RepresentationSalienceCaseAudit inconclusive =
            RepresentationSalienceCaseAudit.create(
            "inconclusive",
            POSITIVE_REFERENCE,
            INCONCLUSIVE,
            evidence("inconclusive", "oracle"),
            evidence("inconclusive", "search"),
            evidence("inconclusive", "formation"),
            evidence("inconclusive", "retention"),
            evidence("inconclusive", "recognition"),
            evidence("inconclusive", "ranking"),
            evidence("inconclusive", "expert"),
            RepresentationSalienceStageSet.empty(),
            RepresentationSalienceStageSet.empty(),
            RepresentationSalienceStageSet.empty(),
            RepresentationSalienceStageSet.empty(),
            RepresentationSalienceStageSet.empty(),
            10,
            NOT_EVALUATED
        );
        RepresentationSalienceCaseAudit negative =
            negativeFalsePositive("negative-control");

        var artifact = RepresentationSalienceAudit.create(
            "historical-calibration-v1",
            "0".repeat(40),
            hash("workspace"),
            hash("information-boundary"),
            List.of(inconclusive, negative, recognitionMiss, detected)
        );
        var summary = artifact.summary();

        assertEquals(4, summary.caseCount());
        assertEquals(3, summary.positiveCaseCount());
        assertEquals(2, summary.oracleReachablePositiveCount());
        assertEquals(1, summary.reachabilityInconclusiveCount());
        assertEquals(2, summary.reachedPositiveCount());
        assertEquals(1, summary.recognizedPositiveCount());
        assertEquals(1, summary.rankedPositiveCount());
        assertEquals(500, summary.recognitionRecallGivenRetained().permille());
        assertEquals(500,
            summary.automatedDetectionRecallGivenReachable().permille());
        assertEquals(1, summary.falsePositiveCount());
        assertEquals(1_000, summary.falsePositiveRate().permille());
        assertTrue(summary.expertConsensusGivenReviewed().defined());
        assertEquals(1_000,
            summary.expertConsensusGivenReviewed().permille());
        assertTrue(artifact.toCanonicalJson().contains(
            "\"localization\":\"RETAINED_NOT_RECOGNIZED\""
        ));
        assertEquals(artifact.toCanonicalJson(), artifact.toCanonicalJson());
    }

    @Test
    void retainsUndefinedConditionalDenominatorExplicitly() {
        RepresentationSalienceCaseAudit inconclusive =
            RepresentationSalienceCaseAudit.create(
            "only-inconclusive",
            POSITIVE_REFERENCE,
            INCONCLUSIVE,
            evidence("only-inconclusive", "oracle"),
            evidence("only-inconclusive", "search"),
            evidence("only-inconclusive", "formation"),
            evidence("only-inconclusive", "retention"),
            evidence("only-inconclusive", "recognition"),
            evidence("only-inconclusive", "ranking"),
            evidence("only-inconclusive", "expert"),
            RepresentationSalienceStageSet.empty(),
            RepresentationSalienceStageSet.empty(),
            RepresentationSalienceStageSet.empty(),
            RepresentationSalienceStageSet.empty(),
            RepresentationSalienceStageSet.empty(),
            5,
            NOT_EVALUATED
        );

        var summary = RepresentationSalienceAudit.create(
            "inconclusive-only-v1",
            "WORKTREE",
            hash("workspace"),
            hash("boundary"),
            List.of(inconclusive)
        ).summary();

        assertEquals(0,
            summary.policyReachabilityRecall().denominator());
        assertFalse(summary.policyReachabilityRecall().defined());
        assertEquals(0, summary.policyReachabilityRecall().permille());
        assertEquals(1, summary.reachabilityInconclusiveCount());
    }

    @Test
    void rejectsStageSetsThatInventLaterCandidates() {
        RepresentationSalienceStageSet candidate =
            RepresentationSalienceStageSet.of(List.of(hash("candidate")));

        assertThrows(IllegalArgumentException.class, () -> positive(
            "invented-recognition",
            RepresentationSalienceStageSet.empty(),
            RepresentationSalienceStageSet.empty(),
            RepresentationSalienceStageSet.empty(),
            candidate,
            candidate,
            NOT_EVALUATED
        ));
    }

    @Test
    void localizesNegativeControlRecognitionAsFalsePositive() {
        RepresentationSalienceCaseAudit result =
            negativeFalsePositive("alias-control");

        assertEquals(INVALID_OR_FALSE_POSITIVE, result.localization());
    }

    private static RepresentationSalienceCaseAudit positive(
        String caseId,
        RepresentationSalienceStageSet reached,
        RepresentationSalienceStageSet formed,
        RepresentationSalienceStageSet retained,
        RepresentationSalienceStageSet recognized,
        RepresentationSalienceStageSet ranked,
        RepresentationSalienceCaseAudit.ExpertVerdict verdict
    ) {
        return RepresentationSalienceCaseAudit.create(
            caseId,
            POSITIVE_REFERENCE,
            REACHABLE,
            evidence(caseId, "oracle"),
            evidence(caseId, "search"),
            evidence(caseId, "formation"),
            evidence(caseId, "retention"),
            evidence(caseId, "recognition"),
            evidence(caseId, "ranking"),
            evidence(caseId, "expert"),
            reached,
            formed,
            retained,
            recognized,
            ranked,
            10,
            verdict
        );
    }

    private static RepresentationSalienceCaseAudit negativeFalsePositive(
        String caseId
    ) {
        RepresentationSalienceStageSet candidate =
            RepresentationSalienceStageSet.of(List.of(
                hash(caseId + "-candidate")
            ));
        return RepresentationSalienceCaseAudit.create(
            caseId,
            NEGATIVE_OR_ALIAS_CONTROL,
            REACHABLE,
            evidence(caseId, "oracle"),
            evidence(caseId, "search"),
            evidence(caseId, "formation"),
            evidence(caseId, "retention"),
            evidence(caseId, "recognition"),
            evidence(caseId, "ranking"),
            evidence(caseId, "expert"),
            candidate,
            candidate,
            candidate,
            candidate,
            candidate,
            10,
            NOT_EVALUATED
        );
    }

    private static String evidence(String caseId, String stage) {
        return hash(caseId + ':' + stage);
    }

    private static String hash(String value) {
        return KnownStructureCatalog.sha256(value);
    }
}
