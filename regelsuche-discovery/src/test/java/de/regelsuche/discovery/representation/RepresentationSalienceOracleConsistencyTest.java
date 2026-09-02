package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation
    .RepresentationSalienceCaseAudit.CaseRole.POSITIVE_REFERENCE;
import static de.regelsuche.discovery.representation
    .RepresentationSalienceCaseAudit.ExpertVerdict.NOT_EVALUATED;
import static de.regelsuche.discovery.representation
    .RepresentationSalienceCaseAudit.Localization.REACHABILITY_INCONCLUSIVE;
import static de.regelsuche.discovery.representation
    .RepresentationSalienceCaseAudit.ReferenceReachability.INCONCLUSIVE;
import static de.regelsuche.discovery.representation
    .RepresentationSalienceCaseAudit.ReferenceReachability
        .NOT_REACHABLE_COMPLETE_CLOSURE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class RepresentationSalienceOracleConsistencyTest {
    @Test
    void acceptsReachedEvidenceWhenOracleWasInconclusive() {
        RepresentationSalienceStageSet candidate =
            RepresentationSalienceStageSet.of(List.of(hash("candidate")));

        RepresentationSalienceCaseAudit audit =
            RepresentationSalienceCaseAudit.create(
                "inconclusive-but-reached",
                POSITIVE_REFERENCE,
                INCONCLUSIVE,
                evidence("oracle"),
                evidence("search"),
                evidence("formation"),
                evidence("retention"),
                evidence("recognition"),
                evidence("ranking"),
                evidence("review"),
                candidate,
                candidate,
                candidate,
                candidate,
                candidate,
                1,
                NOT_EVALUATED
            );

        assertEquals(REACHABILITY_INCONCLUSIVE, audit.localization());
    }

    @Test
    void rejectsReachedEvidenceAfterCompleteClosureProvedUnreachable() {
        RepresentationSalienceStageSet candidate =
            RepresentationSalienceStageSet.of(List.of(hash("candidate")));
        RepresentationSalienceStageSet empty =
            RepresentationSalienceStageSet.empty();

        assertThrows(
            IllegalArgumentException.class,
            () -> RepresentationSalienceCaseAudit.create(
                "closure-unreachable-but-reached",
                POSITIVE_REFERENCE,
                NOT_REACHABLE_COMPLETE_CLOSURE,
                evidence("oracle"),
                evidence("search"),
                evidence("formation"),
                evidence("retention"),
                evidence("recognition"),
                evidence("ranking"),
                evidence("review"),
                candidate,
                empty,
                empty,
                empty,
                empty,
                1,
                NOT_EVALUATED
            )
        );
    }

    private static String evidence(String stage) {
        return hash("oracle-consistency:" + stage);
    }

    private static String hash(String value) {
        return KnownStructureCatalog.sha256(value);
    }
}
