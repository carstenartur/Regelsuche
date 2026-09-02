package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation
    .RepresentationDiscoveryRunContractSupport.append;

import de.regelsuche.discovery.representation
    .RepresentationSalienceCaseAudit.CaseRole;
import de.regelsuche.discovery.representation
    .RepresentationSalienceCaseAudit.ExpertVerdict;
import de.regelsuche.discovery.representation
    .RepresentationSalienceCaseAudit.Localization;
import de.regelsuche.discovery.representation
    .RepresentationSalienceCaseAudit.ReferenceReachability;
import de.regelsuche.json.JsonWriter;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Aggregate stage recall without hiding inconclusive or unsupported cases. */
public record RepresentationSalienceSummary(
    int caseCount,
    int positiveCaseCount,
    int negativeControlCount,
    int oracleReachablePositiveCount,
    int oracleConfirmedReachedPositiveCount,
    int oracleConfirmedRankedPositiveCount,
    int reachedWithoutOracleConfirmationCount,
    int reachabilityInconclusiveCount,
    int unsupportedCount,
    int reachedPositiveCount,
    int formedPositiveCount,
    int retainedPositiveCount,
    int recognizedPositiveCount,
    int rankedPositiveCount,
    int pendingExpertReviewCount,
    int expertReviewedCount,
    int expertConfirmedCount,
    int falsePositiveCount,
    RepresentationSalienceConditionalRate policyReachabilityRecall,
    RepresentationSalienceConditionalRate formationRecallGivenReached,
    RepresentationSalienceConditionalRate retentionRecallGivenFormed,
    RepresentationSalienceConditionalRate recognitionRecallGivenRetained,
    RepresentationSalienceConditionalRate rankingRecallGivenRecognized,
    RepresentationSalienceConditionalRate automatedDetectionRecallGivenReachable,
    RepresentationSalienceConditionalRate expertConsensusGivenReviewed,
    RepresentationSalienceConditionalRate falsePositiveRate
) {
    public RepresentationSalienceSummary {
        if (caseCount < 1
                || positiveCaseCount < 0
                || negativeControlCount < 0
                || positiveCaseCount + negativeControlCount != caseCount
                || oracleReachablePositiveCount < 0
                || oracleConfirmedReachedPositiveCount < 0
                || oracleConfirmedRankedPositiveCount < 0
                || reachedWithoutOracleConfirmationCount < 0
                || reachabilityInconclusiveCount < 0
                || unsupportedCount < 0
                || reachedPositiveCount < 0
                || formedPositiveCount < 0
                || retainedPositiveCount < 0
                || recognizedPositiveCount < 0
                || rankedPositiveCount < 0
                || pendingExpertReviewCount < 0
                || expertReviewedCount < 0
                || expertConfirmedCount < 0
                || falsePositiveCount < 0) {
            throw new IllegalArgumentException(
                "salience-audit summary counts do not balance"
            );
        }
        policyReachabilityRecall = Objects.requireNonNull(
            policyReachabilityRecall,
            "policyReachabilityRecall"
        );
        formationRecallGivenReached = Objects.requireNonNull(
            formationRecallGivenReached,
            "formationRecallGivenReached"
        );
        retentionRecallGivenFormed = Objects.requireNonNull(
            retentionRecallGivenFormed,
            "retentionRecallGivenFormed"
        );
        recognitionRecallGivenRetained = Objects.requireNonNull(
            recognitionRecallGivenRetained,
            "recognitionRecallGivenRetained"
        );
        rankingRecallGivenRecognized = Objects.requireNonNull(
            rankingRecallGivenRecognized,
            "rankingRecallGivenRecognized"
        );
        automatedDetectionRecallGivenReachable = Objects.requireNonNull(
            automatedDetectionRecallGivenReachable,
            "automatedDetectionRecallGivenReachable"
        );
        expertConsensusGivenReviewed = Objects.requireNonNull(
            expertConsensusGivenReviewed,
            "expertConsensusGivenReviewed"
        );
        falsePositiveRate = Objects.requireNonNull(
            falsePositiveRate,
            "falsePositiveRate"
        );
        requireMonotoneCounts(
            positiveCaseCount,
            oracleReachablePositiveCount,
            oracleConfirmedReachedPositiveCount,
            oracleConfirmedRankedPositiveCount,
            reachedWithoutOracleConfirmationCount,
            reachedPositiveCount,
            formedPositiveCount,
            retainedPositiveCount,
            recognizedPositiveCount,
            rankedPositiveCount,
            pendingExpertReviewCount,
            expertReviewedCount,
            expertConfirmedCount,
            negativeControlCount,
            falsePositiveCount
        );
        requireRates(
            oracleReachablePositiveCount,
            oracleConfirmedReachedPositiveCount,
            reachedPositiveCount,
            formedPositiveCount,
            retainedPositiveCount,
            recognizedPositiveCount,
            rankedPositiveCount,
            oracleConfirmedRankedPositiveCount,
            expertReviewedCount,
            expertConfirmedCount,
            negativeControlCount,
            falsePositiveCount,
            policyReachabilityRecall,
            formationRecallGivenReached,
            retentionRecallGivenFormed,
            recognitionRecallGivenRetained,
            rankingRecallGivenRecognized,
            automatedDetectionRecallGivenReachable,
            expertConsensusGivenReviewed,
            falsePositiveRate
        );
    }

    public static RepresentationSalienceSummary derive(
        List<RepresentationSalienceCaseAudit> cases
    ) {
        List<RepresentationSalienceCaseAudit> retainedCases = List.copyOf(
            Objects.requireNonNull(cases, "cases")
        );
        int positives = count(retainedCases, RepresentationSalienceSummary
            ::positive);
        int negatives = retainedCases.size() - positives;
        int reachable = count(retainedCases, value -> positive(value)
            && value.referenceReachability()
                == ReferenceReachability.REACHABLE);
        int inconclusive = count(retainedCases, value ->
            value.referenceReachability()
                == ReferenceReachability.INCONCLUSIVE);
        int unsupported = count(retainedCases, value ->
            value.referenceReachability()
                == ReferenceReachability.UNSUPPORTED);
        int reached = count(retainedCases, value -> positive(value)
            && value.reachedRelevantRepresentation());
        int oracleConfirmedReached = count(retainedCases, value ->
            positive(value)
                && value.referenceReachability()
                    == ReferenceReachability.REACHABLE
                && value.reachedRelevantRepresentation());
        int reachedWithoutOracleConfirmation = count(
            retainedCases,
            value -> positive(value)
                && value.referenceReachability()
                    != ReferenceReachability.REACHABLE
                && value.reachedRelevantRepresentation()
        );
        int formed = count(retainedCases, value -> positive(value)
            && value.formedRelevantCandidate());
        int retained = count(retainedCases, value -> positive(value)
            && value.retainedRelevantCandidate());
        int recognized = count(retainedCases, value -> positive(value)
            && value.recognizedRelevantCandidate());
        int ranked = count(retainedCases, value -> positive(value)
            && value.rankedRelevantCandidate());
        int oracleConfirmedRanked = count(retainedCases, value ->
            positive(value)
                && value.referenceReachability()
                    == ReferenceReachability.REACHABLE
                && value.rankedRelevantCandidate());
        int pending = count(retainedCases, value -> positive(value)
            && value.localization()
                == Localization.RANKED_PENDING_EXPERT_REVIEW);
        int reviewed = count(retainedCases, value -> positive(value)
            && reviewed(value.expertVerdict()));
        int confirmed = count(retainedCases, value -> positive(value)
            && value.expertVerdict()
                == ExpertVerdict.CONSENSUS_INTERESTING);
        int falsePositives = count(retainedCases, value ->
            value.localization() == Localization.INVALID_OR_FALSE_POSITIVE);
        return new RepresentationSalienceSummary(
            retainedCases.size(),
            positives,
            negatives,
            reachable,
            oracleConfirmedReached,
            oracleConfirmedRanked,
            reachedWithoutOracleConfirmation,
            inconclusive,
            unsupported,
            reached,
            formed,
            retained,
            recognized,
            ranked,
            pending,
            reviewed,
            confirmed,
            falsePositives,
            RepresentationSalienceConditionalRate.of(
                oracleConfirmedReached,
                reachable
            ),
            RepresentationSalienceConditionalRate.of(formed, reached),
            RepresentationSalienceConditionalRate.of(retained, formed),
            RepresentationSalienceConditionalRate.of(recognized, retained),
            RepresentationSalienceConditionalRate.of(ranked, recognized),
            RepresentationSalienceConditionalRate.of(
                oracleConfirmedRanked,
                reachable
            ),
            RepresentationSalienceConditionalRate.of(confirmed, reviewed),
            RepresentationSalienceConditionalRate.of(falsePositives, negatives)
        );
    }

    void appendIdentity(StringBuilder descriptor) {
        append(descriptor, Integer.toString(caseCount));
        append(descriptor, Integer.toString(positiveCaseCount));
        append(descriptor, Integer.toString(negativeControlCount));
        append(descriptor, Integer.toString(oracleReachablePositiveCount));
        append(
            descriptor,
            Integer.toString(oracleConfirmedReachedPositiveCount)
        );
        append(
            descriptor,
            Integer.toString(oracleConfirmedRankedPositiveCount)
        );
        append(
            descriptor,
            Integer.toString(reachedWithoutOracleConfirmationCount)
        );
        append(descriptor, Integer.toString(reachabilityInconclusiveCount));
        append(descriptor, Integer.toString(unsupportedCount));
        append(descriptor, Integer.toString(reachedPositiveCount));
        append(descriptor, Integer.toString(formedPositiveCount));
        append(descriptor, Integer.toString(retainedPositiveCount));
        append(descriptor, Integer.toString(recognizedPositiveCount));
        append(descriptor, Integer.toString(rankedPositiveCount));
        append(descriptor, Integer.toString(pendingExpertReviewCount));
        append(descriptor, Integer.toString(expertReviewedCount));
        append(descriptor, Integer.toString(expertConfirmedCount));
        append(descriptor, Integer.toString(falsePositiveCount));
        policyReachabilityRecall.appendIdentity(descriptor);
        formationRecallGivenReached.appendIdentity(descriptor);
        retentionRecallGivenFormed.appendIdentity(descriptor);
        recognitionRecallGivenRetained.appendIdentity(descriptor);
        rankingRecallGivenRecognized.appendIdentity(descriptor);
        automatedDetectionRecallGivenReachable.appendIdentity(descriptor);
        expertConsensusGivenReviewed.appendIdentity(descriptor);
        falsePositiveRate.appendIdentity(descriptor);
    }

    void writeJson(JsonWriter json) {
        json.property("caseCount", caseCount)
            .property("positiveCaseCount", positiveCaseCount)
            .property("negativeControlCount", negativeControlCount)
            .property(
                "oracleReachablePositiveCount",
                oracleReachablePositiveCount
            )
            .property(
                "oracleConfirmedReachedPositiveCount",
                oracleConfirmedReachedPositiveCount
            )
            .property(
                "oracleConfirmedRankedPositiveCount",
                oracleConfirmedRankedPositiveCount
            )
            .property(
                "reachedWithoutOracleConfirmationCount",
                reachedWithoutOracleConfirmationCount
            )
            .property(
                "reachabilityInconclusiveCount",
                reachabilityInconclusiveCount
            )
            .property("unsupportedCount", unsupportedCount)
            .property("reachedPositiveCount", reachedPositiveCount)
            .property("formedPositiveCount", formedPositiveCount)
            .property("retainedPositiveCount", retainedPositiveCount)
            .property("recognizedPositiveCount", recognizedPositiveCount)
            .property("rankedPositiveCount", rankedPositiveCount)
            .property("pendingExpertReviewCount", pendingExpertReviewCount)
            .property("expertReviewedCount", expertReviewedCount)
            .property("expertConfirmedCount", expertConfirmedCount)
            .property("falsePositiveCount", falsePositiveCount);
        writeRate(json, "policyReachabilityRecall", policyReachabilityRecall);
        writeRate(
            json,
            "formationRecallGivenReached",
            formationRecallGivenReached
        );
        writeRate(
            json,
            "retentionRecallGivenFormed",
            retentionRecallGivenFormed
        );
        writeRate(
            json,
            "recognitionRecallGivenRetained",
            recognitionRecallGivenRetained
        );
        writeRate(
            json,
            "rankingRecallGivenRecognized",
            rankingRecallGivenRecognized
        );
        writeRate(
            json,
            "automatedDetectionRecallGivenReachable",
            automatedDetectionRecallGivenReachable
        );
        writeRate(
            json,
            "expertConsensusGivenReviewed",
            expertConsensusGivenReviewed
        );
        writeRate(json, "falsePositiveRate", falsePositiveRate);
    }

    private static void requireRates(
        int reachable,
        int oracleConfirmedReached,
        int reached,
        int formed,
        int retained,
        int recognized,
        int ranked,
        int oracleConfirmedRanked,
        int reviewed,
        int confirmed,
        int negativeControls,
        int falsePositives,
        RepresentationSalienceConditionalRate policyReachability,
        RepresentationSalienceConditionalRate formationRecall,
        RepresentationSalienceConditionalRate retentionRecall,
        RepresentationSalienceConditionalRate recognitionRecall,
        RepresentationSalienceConditionalRate rankingRecall,
        RepresentationSalienceConditionalRate automatedDetectionRecall,
        RepresentationSalienceConditionalRate expertConsensus,
        RepresentationSalienceConditionalRate falsePositiveRate
    ) {
        if (!policyReachability.equals(
                RepresentationSalienceConditionalRate.of(
                    oracleConfirmedReached,
                    reachable))
                || !formationRecall.equals(
                    RepresentationSalienceConditionalRate.of(
                        formed,
                        reached))
                || !retentionRecall.equals(
                    RepresentationSalienceConditionalRate.of(
                        retained,
                        formed))
                || !recognitionRecall.equals(
                    RepresentationSalienceConditionalRate.of(
                        recognized,
                        retained))
                || !rankingRecall.equals(
                    RepresentationSalienceConditionalRate.of(
                        ranked,
                        recognized))
                || !automatedDetectionRecall.equals(
                    RepresentationSalienceConditionalRate.of(
                        oracleConfirmedRanked,
                        reachable))
                || !expertConsensus.equals(
                    RepresentationSalienceConditionalRate.of(
                        confirmed,
                        reviewed))
                || !falsePositiveRate.equals(
                    RepresentationSalienceConditionalRate.of(
                        falsePositives,
                        negativeControls))) {
            throw new IllegalArgumentException(
                "salience-audit rates differ from stage counts"
            );
        }
    }

    private static void requireMonotoneCounts(
        int positiveCases,
        int reachable,
        int oracleConfirmedReached,
        int oracleConfirmedRanked,
        int reachedWithoutOracleConfirmation,
        int reached,
        int formed,
        int retained,
        int recognized,
        int ranked,
        int pending,
        int reviewed,
        int confirmed,
        int negativeControls,
        int falsePositives
    ) {
        if (reachable > positiveCases
                || oracleConfirmedReached > reachable
                || oracleConfirmedRanked > oracleConfirmedReached
                || oracleConfirmedRanked > ranked
                || reachedWithoutOracleConfirmation
                    != reached - oracleConfirmedReached
                || ranked > recognized
                || recognized > retained
                || retained > formed
                || formed > reached
                || reached > positiveCases
                || confirmed > reviewed
                || reviewed > ranked
                || pending > ranked
                || falsePositives > negativeControls) {
            throw new IllegalArgumentException(
                "salience-audit stage counts are not monotone"
            );
        }
    }

    private static boolean positive(
        RepresentationSalienceCaseAudit value
    ) {
        return value.role() == CaseRole.POSITIVE_REFERENCE;
    }

    private static boolean reviewed(ExpertVerdict verdict) {
        return verdict == ExpertVerdict.CONSENSUS_INTERESTING
            || verdict == ExpertVerdict.CONSENSUS_NOT_INTERESTING
            || verdict == ExpertVerdict.UNCERTAIN;
    }

    private static int count(
        List<RepresentationSalienceCaseAudit> cases,
        Predicate<RepresentationSalienceCaseAudit> predicate
    ) {
        return Math.toIntExact(cases.stream().filter(predicate).count());
    }

    private static void writeRate(
        JsonWriter json,
        String name,
        RepresentationSalienceConditionalRate value
    ) {
        json.object(name, value::writeJson);
    }
}
