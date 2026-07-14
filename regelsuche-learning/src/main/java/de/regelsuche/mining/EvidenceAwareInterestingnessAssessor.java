package de.regelsuche.mining;

import de.regelsuche.mining.InterestingnessAssessment.ComponentContribution;
import de.regelsuche.mining.InterestingnessAssessment.Eligibility;
import de.regelsuche.mining.InterestingnessEvidence.ControlClassification;
import de.regelsuche.mining.InterestingnessEvidence.ProjectNoveltyStatus;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.CounterexampleSearchService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Applies hard scientific gates before computing a decomposable interestingness rank.
 *
 * <p>Proof and counterexample outcomes are never positive ranking contributions. They
 * remain independent blockers or evidence-completeness signals. The legacy
 * {@link InterestingnessScore} is reused only for non-gating structural components.</p>
 */
public final class EvidenceAwareInterestingnessAssessor {
    private static final List<String> COMPONENTS = List.of(
        "compression",
        "generalization",
        "independentEvidence",
        "reusability",
        "structuralSurprise",
        "crossFamilyTransfer",
        "assumptionSimplicity",
        "pairedUtility");

    public InterestingnessAssessment assess(
        HypothesisCandidate candidate,
        double knownRuleSimilarity,
        Set<String> domainTags,
        InterestingnessEvidence evidence,
        InterestingnessProfile profile
    ) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(profile, "profile");
        Set<String> safeDomains = domainTags == null ? Set.of() : Set.copyOf(domainTags);
        int similarityPermille = toPermille(
            Math.max(0.0, Math.min(1.0, knownRuleSimilarity)));

        List<String> blockers = hardBlockers(candidate, evidence);
        List<String> warnings = warnings(candidate, evidence);
        int completeness = evidenceCompleteness(candidate, evidence);
        Eligibility eligibility = eligibility(blockers, completeness);

        InterestingnessScore legacy = InterestingnessScore.from(
            candidate, similarityPermille / 1000.0, safeDomains);
        List<ComponentContribution> contributions = contributions(
            legacy, evidence, profile, similarityPermille);
        int baseTotal = contributions.stream()
            .mapToInt(ComponentContribution::weightedPermille)
            .sum();
        int unresolvedRisk = unresolvedRiskPenalty(evidence, completeness);
        int controlPenalty = controlPenalty(evidence.controlClassification());
        int total = eligibility == Eligibility.BLOCKED
            ? -1000
            : clamp(baseTotal - unresolvedRisk - controlPenalty, -1000, 1000);

        String contentHash = hash(canonicalMaterial(
            candidate,
            evidence,
            profile,
            similarityPermille,
            completeness,
            contributions,
            unresolvedRisk,
            controlPenalty,
            total,
            blockers,
            warnings));
        return new InterestingnessAssessment(
            InterestingnessAssessment.SCHEMA,
            candidate.id(),
            profile,
            eligibility,
            evidence,
            candidate.proofStatus(),
            evidence.counterexampleStatus(),
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            similarityPermille,
            completeness,
            contributions,
            unresolvedRisk,
            controlPenalty,
            total,
            blockers,
            warnings,
            contentHash);
    }

    private static List<String> hardBlockers(
        HypothesisCandidate candidate,
        InterestingnessEvidence evidence
    ) {
        List<String> blockers = new ArrayList<>();
        if (candidate.proofStatus() == CandidateProofStatus.REJECTED) {
            blockers.add("proof-status=REJECTED");
        }
        if (candidate.counterexampleSearchStatus()
                == CounterexampleSearchService.Status.COUNTEREXAMPLE_FOUND
                || Boolean.TRUE.equals(candidate.counterexampleStatus())
                || evidence.counterexampleStatus()
                    == CounterexampleSearchService.Status.COUNTEREXAMPLE_FOUND) {
            blockers.add("counterexample-found");
        }
        if (evidence.oracleDisagreed()) {
            blockers.add("oracle=DISAGREE");
        }
        if (evidence.failedPositiveChecks() > 0) {
            blockers.add("positive-checks-failed=" + evidence.failedPositiveChecks());
        }
        if (evidence.failedNegativeChecks() > 0) {
            blockers.add("negative-checks-failed=" + evidence.failedNegativeChecks());
        }
        if (!evidence.positiveAccountingComplete()) {
            blockers.add("positive-check-accounting-inconsistent");
        }
        if (!evidence.negativeAccountingComplete()) {
            blockers.add("negative-check-accounting-inconsistent");
        }
        if (!evidence.heldOutAccountingComplete()) {
            blockers.add("held-out-family-accounting-inconsistent");
        }
        return ordered(blockers);
    }

    private static List<String> warnings(
        HypothesisCandidate candidate,
        InterestingnessEvidence evidence
    ) {
        List<String> warnings = new ArrayList<>();
        if (candidate.proofStatus().ordinal()
                < CandidateProofStatus.VALIDATED_BY_EXAMPLES.ordinal()) {
            warnings.add("proof-evidence-incomplete");
        }
        if (evidence.configuredPositiveChecks() == 0) {
            warnings.add("positive-suite-missing");
        } else if (evidence.skippedPositiveChecks() > 0) {
            warnings.add("positive-checks-skipped=" + evidence.skippedPositiveChecks());
        }
        if (evidence.configuredNegativeChecks() == 0) {
            warnings.add("negative-suite-missing");
        } else if (evidence.skippedNegativeChecks() > 0) {
            warnings.add("negative-checks-skipped=" + evidence.skippedNegativeChecks());
        }
        if (evidence.counterexampleStatus() == CounterexampleSearchService.Status.INCONCLUSIVE) {
            warnings.add("counterexample-search-inconclusive");
        }
        if (evidence.counterexampleSourcesAttempted() == 0) {
            warnings.add("counterexample-sources-missing");
        }
        if (evidence.projectNoveltyStatus() == ProjectNoveltyStatus.UNKNOWN) {
            warnings.add("project-novelty-unknown");
        }
        if (evidence.heldOutTransferRequired()) {
            if (evidence.heldOutFamiliesConfigured() == 0) {
                warnings.add("held-out-family-suite-missing");
            } else if (evidence.heldOutFamiliesPassed()
                    < evidence.heldOutFamiliesConfigured()) {
                warnings.add("held-out-transfer-incomplete");
            }
        }
        if (!evidence.pairedUtilityEvaluated()) {
            warnings.add("paired-utility-not-evaluated");
        }
        if (evidence.controlClassification() != ControlClassification.NONE) {
            warnings.add("control=" + evidence.controlClassification().name());
        }
        return ordered(warnings);
    }

    private static int evidenceCompleteness(
        HypothesisCandidate candidate,
        InterestingnessEvidence evidence
    ) {
        int positive = executedRatio(
            evidence.configuredPositiveChecks(),
            evidence.executedPositiveChecks(),
            evidence.positiveAccountingComplete());
        int negative = executedRatio(
            evidence.configuredNegativeChecks(),
            evidence.executedNegativeChecks(),
            evidence.negativeAccountingComplete());
        int counterexample = switch (evidence.counterexampleStatus()) {
            case NO_COUNTEREXAMPLE_FOUND -> evidence.counterexampleSourcesAttempted() > 0 ? 1000 : 0;
            case INCONCLUSIVE -> evidence.counterexampleSourcesAttempted() > 0 ? 250 : 0;
            case COUNTEREXAMPLE_FOUND -> 0;
        };
        int heldOut = !evidence.heldOutTransferRequired()
            ? 1000
            : ratio(evidence.heldOutFamiliesPassed(), evidence.heldOutFamiliesConfigured());
        int utility = evidence.pairedUtilityEvaluated() ? 1000 : 0;
        int proof = candidate.proofStatus().ordinal()
                >= CandidateProofStatus.VALIDATED_BY_EXAMPLES.ordinal()
            ? 1000
            : 250;
        return (positive * 200
            + negative * 250
            + counterexample * 200
            + heldOut * 150
            + utility * 100
            + proof * 100) / 1000;
    }

    private static Eligibility eligibility(List<String> blockers, int completeness) {
        if (!blockers.isEmpty()) {
            return Eligibility.BLOCKED;
        }
        return completeness == 1000
            ? Eligibility.RANKABLE_COMPLETE
            : Eligibility.RANKABLE_INCOMPLETE;
    }

    private static List<ComponentContribution> contributions(
        InterestingnessScore legacy,
        InterestingnessEvidence evidence,
        InterestingnessProfile profile,
        int similarityPermille
    ) {
        List<Integer> raw = List.of(
            saturatingPositive(legacy.compressionGain()),
            saturatingPositive(legacy.generality()),
            evidenceCountPermille(legacy.independentEvidenceCount()),
            saturatingPositive(legacy.macroReusability()),
            structuralSurprise(evidence.projectNoveltyStatus(), similarityPermille),
            crossFamilyTransfer(evidence),
            saturatingUnit(legacy.minimalAssumptions()),
            evidence.pairedUtilityEvaluated() ? evidence.pairedUtilityPermille() : 0);
        List<ComponentContribution> result = new ArrayList<>();
        for (int index = 0; index < COMPONENTS.size(); index++) {
            String name = COMPONENTS.get(index);
            int weight = profile.weight(name);
            int value = raw.get(index);
            result.add(new ComponentContribution(
                name,
                value,
                weight,
                (value * weight + 500) / 1000));
        }
        return List.copyOf(result);
    }

    private static int structuralSurprise(
        ProjectNoveltyStatus novelty,
        int similarityPermille
    ) {
        int raw = 1000 - similarityPermille;
        return switch (novelty) {
            case NOVEL_WITHIN_PROJECT -> raw;
            case ALPHA_EQUIVALENT -> Math.min(raw, 200);
            case DUPLICATE -> Math.min(raw, 100);
            case UNKNOWN -> Math.min(raw, 400);
        };
    }

    private static int crossFamilyTransfer(InterestingnessEvidence evidence) {
        if (evidence.heldOutFamiliesConfigured() == 0) {
            return 0;
        }
        return ratio(
            evidence.heldOutFamiliesPassed(), evidence.heldOutFamiliesConfigured());
    }

    private static int unresolvedRiskPenalty(
        InterestingnessEvidence evidence,
        int completeness
    ) {
        int penalty = ((1000 - completeness) * 650 + 500) / 1000;
        if (evidence.projectNoveltyStatus() == ProjectNoveltyStatus.UNKNOWN) {
            penalty += 100;
        }
        return clamp(penalty, 0, 1000);
    }

    private static int controlPenalty(ControlClassification classification) {
        return switch (classification) {
            case NONE -> 0;
            case ALPHA_RENAMING_ONLY -> 800;
            case FORMAT_ONLY -> 750;
            case GENERIC_NORMALIZATION -> 650;
        };
    }

    private static int executedRatio(int configured, int executed, boolean accountingValid) {
        return accountingValid ? ratio(executed, configured) : 0;
    }

    private static int ratio(int numerator, int denominator) {
        if (denominator <= 0) {
            return 0;
        }
        return clamp((numerator * 1000) / denominator, 0, 1000);
    }

    private static int evidenceCountPermille(double count) {
        if (count <= 0.0) {
            return 0;
        }
        double normalized = Math.log1p(count) / Math.log(6.0);
        return toPermille(Math.min(1.0, normalized));
    }

    private static int saturatingPositive(double value) {
        if (value <= 0.0 || !Double.isFinite(value)) {
            return 0;
        }
        return toPermille(1.0 - Math.exp(-value));
    }

    private static int saturatingUnit(double value) {
        if (!Double.isFinite(value)) {
            return 0;
        }
        return toPermille(Math.max(0.0, Math.min(1.0, value)));
    }

    private static int toPermille(double value) {
        return clamp((int) Math.round(value * 1000.0), 0, 1000);
    }

    private static String canonicalMaterial(
        HypothesisCandidate candidate,
        InterestingnessEvidence evidence,
        InterestingnessProfile profile,
        int similarityPermille,
        int completeness,
        List<ComponentContribution> contributions,
        int unresolvedRisk,
        int controlPenalty,
        int total,
        List<String> blockers,
        List<String> warnings
    ) {
        StringBuilder material = new StringBuilder(InterestingnessAssessment.SCHEMA)
            .append("\ncandidate=").append(candidate.id())
            .append("\nrelation=").append(normalize(candidate.leftPattern()))
            .append("->").append(normalize(candidate.rightPattern()))
            .append("\nassumptions=").append(candidate.assumptions())
            .append("\nproof=").append(candidate.proofStatus())
            .append("\nprofile=").append(profile.name())
            .append("\nweights=").append(profile.weights())
            .append("\nevidence=").append(evidence)
            .append("\nsimilarity=").append(similarityPermille)
            .append("\ncompleteness=").append(completeness)
            .append("\nrisk=").append(unresolvedRisk)
            .append("\ncontrol=").append(controlPenalty)
            .append("\ntotal=").append(total)
            .append("\nblockers=").append(blockers)
            .append("\nwarnings=").append(warnings);
        contributions.forEach(contribution -> material.append("\ncomponent=")
            .append(contribution.name()).append('|')
            .append(contribution.rawPermille()).append('|')
            .append(contribution.weightPermille()).append('|')
            .append(contribution.weightedPermille()));
        return material.toString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static List<String> ordered(List<String> values) {
        return values.stream().distinct().sorted().toList();
    }

    private static String hash(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
