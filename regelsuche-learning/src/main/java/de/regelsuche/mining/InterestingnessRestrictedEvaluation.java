package de.regelsuche.mining;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.mining.InterestingnessCalibrationCorpus.CorpusCase;
import de.regelsuche.mining.InterestingnessCalibrationCorpus.CorpusReport;
import de.regelsuche.mining.InterestingnessCalibrationCorpus.CorpusSplit;
import de.regelsuche.mining.InterestingnessIndependentReviewIntake.IntakeOutcome;
import de.regelsuche.mining.InterestingnessIndependentReviewIntake.IntakeReport;
import de.regelsuche.mining.InterestingnessIndependentReviewIntake.ReviewOrigin;
import de.regelsuche.mining.InterestingnessIndependentReviewStudy.StudyPlan;
import de.regelsuche.mining.InterestingnessProfileCalibration.CaseProfiles;
import de.regelsuche.mining.InterestingnessReviewConsensus.ReviewLabel;
import de.regelsuche.mining.InterestingnessReviewConsensus.ReviewSource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Frozen-input bridge to the production consensus, calibration and pre-registered gate. */
final class InterestingnessRestrictedEvaluation {
    private InterestingnessRestrictedEvaluation() { }

    static List<CaseProfiles> profiles(ObjectMapper json, JsonNode supplied, StudyPlan plan) throws IOException {
        var pairs = List.of(json.treeToValue(supplied, CaseProfiles[].class)).stream()
            .sorted(Comparator.comparing(CaseProfiles::caseId)).toList();
        if (pairs.size() != plan.cases().size() || pairs.stream().map(CaseProfiles::caseId).distinct().count() != pairs.size()) {
            throw new IllegalArgumentException("profile inputs must cover the frozen plan exactly");
        }
        for (var pair : pairs) {
            var item = plan.requireCase(pair.caseId());
            if (!item.candidateId().equals(pair.candidateId()) || !item.assessmentContentHash().equals(pair.theoryDiscovery().contentHash())) {
                throw new IllegalArgumentException("profile input differs from frozen case");
            }
            InterestingnessProfileCalibration.validateProfilePair(pair);
        }
        return pairs;
    }

    static ObjectNode evaluate(ObjectMapper json, ObjectNode state) throws IOException {
        JsonNode frozen = state.required("frozen");
        var plan = json.treeToValue(state.required("plan"), StudyPlan.class);
        var intake = json.treeToValue(frozen.required("intake"), IntakeReport.class);
        boolean fixture = state.required("registration").required("origin").asText().equals(ReviewOrigin.DEVELOPMENT_FIXTURE.name());
        var labels = new ArrayList<ReviewLabel>();
        for (var row : intake.decisions()) {
            var accepted = fixture ? IntakeOutcome.DEVELOPMENT_ONLY : IntakeOutcome.COUNTED_EXPERT_REVIEW;
            if (row.outcome() != accepted) throw new IllegalArgumentException("unexpected evidence origin or rejected decision");
            labels.add(new ReviewLabel(row.reviewId(), row.candidateId(), row.reviewerHash(),
                fixture ? ReviewSource.TEST_FIXTURE : ReviewSource.EXPERT_REVIEW, row.blindReview(),
                row.relevancePermille(), row.confidencePermille(), row.rationaleCodes()));
        }
        var consensus = new InterestingnessReviewConsensus().evaluate(labels);
        var result = json.createObjectNode();
        result.put("schema", "regelsuche.restricted-review-analysis/v1");
        result.put("freezeHash", frozen.required("freezeHash").textValue());
        result.put("registrationHash", state.required("registrationHash").textValue());
        result.put("evidenceClass", fixture ? "DEVELOPMENT_ONLY" : "EXTERNAL_REVIEW_COLLECTION");
        result.put("empiricalConsensusStatus", fixture ? "NOT_EVALUATED" : "ASSESSED");
        result.put("publicEvidenceStatus", "NOT_EVALUATED");
        result.put("promotionStatus", "NOT_EVALUATED");
        result.put("thresholdLockHash", plan.thresholdLockHash());
        result.set("consensus", json.readTree(consensus.toCanonicalJson()));
        var exposures = result.putArray("testExposureLedger");
        for (var item : plan.cases()) {
            if (item.split() == CorpusSplit.TEST) {
                var exposure = exposures.addObject();
                exposure.put("candidateArtifactHash", item.candidateArtifactHash());
                exposure.put("kind", fixture ? "DEVELOPMENT_EVALUATION" : "PRIOR_HELD_OUT_USE");
                exposure.put("sourceStudyHash", plan.contentHash());
                exposure.put("freezeHash", frozen.required("freezeHash").textValue());
            }
        }
        var problems = result.putArray("blockers");
        for (var item : plan.cases()) {
            var candidate = consensus.candidates().stream().filter(c -> c.candidateId().equals(item.candidateId())).findFirst();
            if (candidate.isEmpty()) {
                problems.add("no-rating:" + item.caseId());
                continue;
            }
            var c = candidate.get();
            int reviews = fixture ? c.reviewerHashes().size() : c.countedExpertReviews();
            if (reviews < plan.reviewProtocol().minimumIndependentExpertReviews()) problems.add("insufficient-reviews:" + item.caseId());
            if (c.spreadPermille() > InterestingnessReviewConsensus.MAX_CONSENSUS_SPREAD_PERMILLE) problems.add("disagreement:" + item.caseId());
            if (c.meanConfidencePermille() < InterestingnessReviewConsensus.MIN_MEAN_CONFIDENCE_PERMILLE) problems.add("low-confidence:" + item.caseId());
        }
        if (!problems.isEmpty()) {
            result.put("analysisStatus", "INSUFFICIENT_CONSENSUS");
            return result;
        }
        var pairs = profiles(json, state.required("profiles"), plan);
        var cases = pairs.stream().map(pair -> {
            var item = plan.requireCase(pair.caseId());
            return new CorpusCase(item.caseId(), item.candidateId(), item.split(), item.candidateFamily(),
                item.structuralSignatureHash(), item.assessmentContentHash(), pair.theoryDiscovery().evidence().controlClassification());
        }).toList();
        var builder = new InterestingnessCalibrationCorpus();
        CorpusReport corpus;
        try {
            corpus = fixture ? builder.freezeDevelopmentDiagnostics(cases, consensus) : builder.freeze(cases, consensus);
        } catch (IllegalArgumentException blocked) {
            problems.add(Objects.requireNonNullElse(blocked.getMessage(), "corpus-rejected"));
            result.put("analysisStatus", "CORPUS_REJECTED");
            return result;
        }
        var report = new InterestingnessProfileCalibration().calibrate(corpus, pairs);
        var gate = new InterestingnessAcceptanceGate().evaluate(report, plan.acceptanceThresholds());
        result.put("analysisStatus", fixture ? "FIXTURE_DIAGNOSTICS" : "HELD_OUT_EVALUATION");
        result.set("corpus", json.readTree(corpus.toCanonicalJson()));
        result.set("calibration", json.readTree(report.toCanonicalJson()));
        result.set("acceptance", json.readTree(gate.toCanonicalJson()));
        return result;
    }
}
