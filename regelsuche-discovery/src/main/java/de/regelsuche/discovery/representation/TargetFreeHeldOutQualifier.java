package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireText;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.CANONICALIZER;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.NEGATIVE_OUTCOME;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.POSITIVE_OUTCOME;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.reject;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.requireBoundaryAuthority;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.uniqueProposals;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.visibleSelection;

import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.Transformation;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.OracleValidator;
import de.regelsuche.validation.SymPyOracleValidator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

final class TargetFreeHeldOutQualifier {
    private TargetFreeHeldOutQualifier() {
    }

    static QualificationRow qualifyRow(
        FreezeRow frozen,
        CaseSpec benchmarkCase,
        CaseQualification rule,
        SymPyOracleValidator oracle
    ) {
        RepresentationDiscoveryInformationBoundary boundary =
            RepresentationDiscoveryInformationBoundary.fromKnowledgePacks(
                benchmarkCase.informationTrack(),
                visibleSelection(benchmarkCase));
        requireBoundaryAuthority(frozen, boundary);

        List<RepresentationCandidateProposal> proposals = uniqueProposals(
            benchmarkCase.sourceExpression(), frozen.candidates());
        RepresentationDiscoveryInformationBoundary.CandidateFreezeReceipt
            receipt = boundary.freezeCandidates(proposals);
        if (receipt.candidateCount() != frozen.candidateSetCount()
                || !receipt.candidateSetHash().equals(
                    frozen.candidateSetHash())
                || !receipt.contentHash().equals(
                    frozen.candidateFreezeReceiptHash())) {
            throw new IllegalArgumentException(
                "candidate set changed before qualification");
        }
        var disclosure = boundary.disclosePostFreeze(receipt);
        if (!disclosure.classificationCatalog().contentHash().equals(
                frozen.postFreezeCatalogCommitment())
                || !disclosure.formationRuleInventoryHash().equals(
                    frozen.formationRuleInventoryHash())) {
            throw new IllegalArgumentException(
                "post-freeze disclosure differs from commitment");
        }

        RepresentationCandidateAssessor assessor =
            new RepresentationCandidateAssessor(
                disclosure.classificationCatalog());
        AstRewriteTransformationEngine classificationEngine =
            AstRewriteTransformationEngine.withKnowledgePacks(
                disclosure.classificationSelection());
        List<CandidateQualification> candidates = frozen.candidates().stream()
            .map(candidate -> classifyCandidate(
                benchmarkCase,
                rule,
                candidate,
                assessor,
                classificationEngine,
                oracle))
            .sorted(Comparator.comparing(
                CandidateQualification::candidateHash))
            .toList();
        int qualified = Math.toIntExact(candidates.stream()
            .filter(CandidateQualification::qualified).count());
        int violations = Math.toIntExact(candidates.stream()
            .filter(CandidateQualification::negativeControlViolation)
            .count());
        String status;
        if (POSITIVE_OUTCOME.equals(rule.expectedOutcome())) {
            status = qualified > 0 ? "QUALIFIED"
                : "NO_QUALIFYING_CANDIDATE";
        } else if (NEGATIVE_OUTCOME.equals(rule.expectedOutcome())) {
            status = violations == 0 ? "NEGATIVE_CONTROL_PASSED"
                : "NEGATIVE_CONTROL_FAILED";
        } else {
            throw new IllegalArgumentException(
                "unknown expected outcome " + rule.expectedOutcome());
        }
        return new QualificationRow(
            frozen.sequence(),
            frozen.configurationId(),
            frozen.caseId(),
            frozen.policyId(),
            frozen.checkpoint(),
            frozen.status(),
            frozen.candidateBatchHash(),
            frozen.candidateSetHash(),
            frozen.candidateFreezeReceiptHash(),
            frozen.work().contentHash(),
            disclosure.contentHash(),
            disclosure.classificationCatalog().contentHash(),
            disclosure.classificationRuleInventoryHash(),
            rule.expectedOutcome(),
            status,
            candidates,
            qualified,
            violations);
    }

    private static CandidateQualification classifyCandidate(
        CaseSpec benchmarkCase,
        CaseQualification rule,
        CandidateEvidence candidate,
        RepresentationCandidateAssessor assessor,
        AstRewriteTransformationEngine classificationEngine,
        SymPyOracleValidator oracle
    ) {
        boolean referenceMatched = rule.referenceExpressions().stream()
            .map(CANONICALIZER::canonicalize)
            .anyMatch(CANONICALIZER.canonicalize(
                candidate.expression())::equals);
        boolean positive = POSITIVE_OUTCOME.equals(rule.expectedOutcome());
        boolean depthQualified = !positive
            || candidate.depth() >= rule.minimumQualifiedDepth()
                && candidate.depth() <= rule.maximumQualifiedDepth();
        boolean assumptionsPresent = candidate.assumptions().containsAll(
            rule.requiredAssumptions());
        boolean complexityQualified = !positive
            || !rule.requireTemporaryComplexityIncrease()
            || candidate.temporaryComplexityIncrease();
        Proof proof = proof(
            benchmarkCase.sourceExpression(), candidate,
            referenceMatched, oracle);
        RepresentationCandidateAssessment assessment = assessor.assess(
            RepresentationCandidateProposal.whole(
                benchmarkCase.sourceExpression(),
                candidate.expression(),
                candidate.assumptions(),
                proof.status()));
        List<String> structures = assessment
            .newlyExposedStructureMatches().stream()
            .map(match -> match.structureId()
                + "@" + match.occurrencePath())
            .distinct().sorted().toList();
        List<String> unlocked = assessment
            .newlyUnlockedConsequences().stream()
            .map(KnownStructureConsequenceUnlock::consequenceId)
            .distinct().sorted().toList();
        List<String> executable = referenceMatched
            ? executableCapabilities(
                classificationEngine,
                candidate.expression(),
                rule.requiredCapabilities())
            : List.of();
        boolean capabilitiesUnlocked = unlocked.containsAll(
            rule.requiredCapabilities());
        boolean capabilitiesExecutable = executable.containsAll(
            rule.requiredCapabilities());
        boolean postFreezeRuleUsed = usedPostFreezeRule(
            candidate, rule.requiredCapabilities());
        boolean symbolic = proof.status().atLeast(
            CandidateProofStatus.SYMBOLICALLY_VERIFIED);
        boolean subexpressionMatch = assessment
            .newlyExposedStructureMatches().stream()
            .anyMatch(match -> !match.wholeExpression());

        TreeSet<String> observedForbidden = new TreeSet<>();
        for (String forbidden : rule.forbiddenOutcomes()) {
            boolean observed = switch (forbidden) {
                case "DIRECT_PRIMITIVE_EDGE_TO_REFERENCE",
                     "DIRECT_MACRO_EDGE_TO_REFERENCE",
                     "DEPTH_ONE_QUALIFICATION" ->
                    referenceMatched && candidate.depth() <= 1;
                case "REQUIRED_ASSUMPTION_MISSING" ->
                    referenceMatched && !assumptionsPresent;
                case "WHOLE_EXPRESSION_ONLY_MATCH" ->
                    referenceMatched && !subexpressionMatch;
                case "CAPABILITY_NOT_EXECUTABLE" ->
                    referenceMatched && !capabilitiesExecutable;
                case "FALSE_POSITIVE_KNOWN_STRUCTURE_MATCH",
                     "PYTHAGOREAN_MATCH_WITH_DIFFERENT_ARGUMENTS" ->
                    capabilitiesUnlocked || capabilitiesExecutable;
                default -> throw new IllegalArgumentException(
                    "unknown forbidden outcome " + forbidden);
            };
            if (observed) {
                observedForbidden.add(forbidden);
            }
        }

        TreeSet<String> reasons = new TreeSet<>();
        reject(reasons, !referenceMatched, "REFERENCE_NOT_MATCHED");
        reject(reasons, !depthQualified, "DEPTH_OUTSIDE_QUALIFIED_RANGE");
        reject(reasons, !assumptionsPresent,
            "REQUIRED_ASSUMPTION_MISSING");
        reject(reasons, !complexityQualified,
            "REQUIRED_COMPLEXITY_VALLEY_MISSING");
        reject(reasons, !symbolic,
            "SYMBOLIC_VALIDATION_NOT_CONFIRMED");
        reject(reasons, !capabilitiesUnlocked,
            "REQUIRED_CAPABILITY_NOT_UNLOCKED");
        reject(reasons, !capabilitiesExecutable,
            "REQUIRED_CAPABILITY_NOT_EXECUTABLE");
        reject(reasons, postFreezeRuleUsed,
            "FORMATION_USED_POST_FREEZE_RULE");
        reject(reasons, positive && !observedForbidden.isEmpty(),
            "FORBIDDEN_OUTCOME_OBSERVED");

        boolean negativeViolation = !positive
            && referenceMatched
            && symbolic
            && (capabilitiesUnlocked || capabilitiesExecutable
                || !observedForbidden.isEmpty());
        boolean qualified = positive && reasons.isEmpty();
        return new CandidateQualification(
            candidate.candidateHash(),
            candidate.expression(),
            candidate.assumptions(),
            candidate.depth(),
            candidate.pathRuleIds(),
            candidate.temporaryComplexityIncrease(),
            proof.status().name(),
            proof.oracleStatus(),
            referenceMatched,
            depthQualified,
            assumptionsPresent,
            complexityQualified,
            structures,
            unlocked,
            executable,
            List.copyOf(observedForbidden),
            List.copyOf(reasons),
            qualified,
            negativeViolation);
    }

    private static Proof proof(
        String source,
        CandidateEvidence candidate,
        boolean referenceMatched,
        SymPyOracleValidator oracle
    ) {
        if (!referenceMatched) {
            return new Proof(
                CandidateProofStatus.OBSERVED,
                "NOT_RUN_REFERENCE_MISS");
        }
        if (!candidate.equivalencePreserving()) {
            return new Proof(
                CandidateProofStatus.OBSERVED,
                "NOT_EQUIVALENCE_PRESERVING_BY_CONSTRUCTION");
        }
        try {
            var validation =
                oracle.validateEquivalence(source, candidate.expression());
            return new Proof(
                validation.status()
                    == OracleValidator.OracleValidationStatus.AGREE
                    ? CandidateProofStatus.SYMBOLICALLY_VERIFIED
                    : CandidateProofStatus.OBSERVED,
                validation.status().name());
        } catch (RuntimeException exception) {
            return new Proof(
                CandidateProofStatus.OBSERVED,
                "VALIDATOR_ERROR_"
                    + exception.getClass().getSimpleName());
        }
    }

    private static List<String> executableCapabilities(
        AstRewriteTransformationEngine engine,
        String expression,
        List<String> capabilities
    ) {
        if (capabilities.isEmpty()) {
            return List.of();
        }
        List<Transformation> successors = engine.transform(expression);
        TreeSet<String> result = new TreeSet<>();
        for (String capability : capabilities) {
            String ruleId = capabilityRuleId(capability);
            boolean present = engine.rules().stream()
                .anyMatch(rule -> rule.id().equals(ruleId));
            boolean executed = successors.stream().anyMatch(value ->
                value.rule().equals(ruleId)
                    && !CANONICALIZER.canonicalize(
                        value.transformedExpression()).equals(
                            CANONICALIZER.canonicalize(expression)));
            if (present && executed) {
                result.add(capability);
            }
        }
        return List.copyOf(result);
    }

    private static boolean usedPostFreezeRule(
        CandidateEvidence candidate,
        List<String> capabilities
    ) {
        Set<String> withheld = capabilities.stream()
            .map(TargetFreeHeldOutQualifier::capabilityRuleId)
            .collect(Collectors.toCollection(TreeSet::new));
        return candidate.pathRuleIds().stream().anyMatch(withheld::contains)
            || candidate.primitiveRuleIds().stream()
                .anyMatch(withheld::contains);
    }

    static String capabilityRuleId(String capability) {
        String value = requireText(capability, "capability");
        if (!value.startsWith("rule:") || value.length() == 5) {
            throw new IllegalArgumentException(
                "capability must use a rule: identity");
        }
        return value.substring(5);
    }

    static List<MatchedWorkGroup> matchedWorkGroups(
        PlanContent plan,
        List<FreezeRow> rows
    ) {
        Map<String, FreezeRow> byIdentity = rows.stream().collect(
            Collectors.toUnmodifiableMap(
                row -> row.caseId() + "\u0000" + row.policyId()
                    + "\u0000" + row.checkpoint(),
                value -> value));
        List<MatchedWorkGroup> result = new ArrayList<>();
        for (CaseSpec benchmarkCase : plan.cases()) {
            for (int checkpoint : plan.workMatching().checkpoints()) {
                List<FreezeRow> groupRows = plan.policies().stream()
                    .map(policy -> Objects.requireNonNull(byIdentity.get(
                        benchmarkCase.id() + "\u0000" + policy.id()
                            + "\u0000" + checkpoint)))
                    .toList();
                boolean eligible = groupRows.stream().allMatch(row ->
                    row.work().exactCheckpointReached());
                result.add(new MatchedWorkGroup(
                    benchmarkCase.id(),
                    checkpoint,
                    eligible,
                    groupRows.stream().map(FreezeRow::policyId).toList(),
                    groupRows.stream().map(row ->
                        row.work().contentHash()).toList()));
            }
        }
        return List.copyOf(result);
    }

    static List<PolicyComparison> policyComparisons(
        List<MatchedWorkGroup> groups,
        List<QualificationRow> rows,
        Map<String, CaseQualification> qualifications
    ) {
        Map<String, QualificationRow> byIdentity = rows.stream().collect(
            Collectors.toUnmodifiableMap(
                row -> row.caseId() + "\u0000" + row.policyId()
                    + "\u0000" + row.checkpoint(),
                value -> value));
        List<PolicyComparison> result = new ArrayList<>();
        for (MatchedWorkGroup group : groups) {
            CaseQualification qualification = Objects.requireNonNull(
                qualifications.get(group.caseId()));
            List<PolicyOutcome> outcomes = group.policyIds().stream()
                .map(policyId -> {
                    QualificationRow row = Objects.requireNonNull(
                        byIdentity.get(group.caseId() + "\u0000"
                            + policyId + "\u0000" + group.checkpoint()));
                    int minimumDepth = row.candidates().stream()
                        .filter(CandidateQualification::qualified)
                        .mapToInt(CandidateQualification::depth)
                        .min().orElse(0);
                    return new PolicyOutcome(
                        policyId,
                        row.status(),
                        row.qualifyingCandidateCount(),
                        row.negativeControlViolationCount(),
                        minimumDepth);
                }).toList();
            List<String> leaders = List.of();
            if (group.eligible()
                    && POSITIVE_OUTCOME.equals(
                        qualification.expectedOutcome())) {
                int bestDepth = outcomes.stream()
                    .filter(value -> value.qualifyingCandidateCount() > 0)
                    .mapToInt(PolicyOutcome::minimumQualifiedDepth)
                    .min().orElse(0);
                if (bestDepth > 0) {
                    leaders = outcomes.stream()
                        .filter(value -> value.minimumQualifiedDepth()
                            == bestDepth)
                        .map(PolicyOutcome::policyId)
                        .sorted().toList();
                }
            }
            result.add(new PolicyComparison(
                group.caseId(),
                group.checkpoint(),
                group.eligible(),
                outcomes,
                leaders));
        }
        return List.copyOf(result);
    }
}
