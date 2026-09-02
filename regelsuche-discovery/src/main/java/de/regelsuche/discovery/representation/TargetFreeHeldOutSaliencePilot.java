package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.COMPRESSION_MATERIAL_MULTI_DIMENSIONAL;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.TYPE_NO_MATERIAL_REPRESENTATION_GAIN;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole.CANDIDATE_DOSSIERS;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole.EXPORT_BUNDLE;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole.PATH_REPLAY;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole.PROGRESS_LEDGER;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole.PROOF_OBLIGATIONS;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole.REPRESENTATION_CANDIDATES;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole.RULE_RADAR;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole.SEARCH_GRAPH;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunOutcome.TerminalState.COMPLETED;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunOutcome.TerminalState.NO_RESULT;
import static de.regelsuche.discovery.representation.RepresentationSalienceCaseAudit.CaseRole.NEGATIVE_OR_ALIAS_CONTROL;
import static de.regelsuche.discovery.representation.RepresentationSalienceCaseAudit.CaseRole.POSITIVE_REFERENCE;
import static de.regelsuche.discovery.representation.RepresentationSalienceCaseAudit.ExpertVerdict.NOT_EVALUATED;
import static de.regelsuche.discovery.representation.RepresentationSalienceCaseAudit.ReferenceReachability.UNSUPPORTED;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.FREEZE_FILE_NAME;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.FREEZE_SCHEMA;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.NOT_COMPARABLE;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.PLAN_FILE_NAME;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.POSITIVE_OUTCOME;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.QUALIFICATION_FILE_NAME;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.QUALIFICATION_NOT_DISCLOSED;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.canonical;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.requireBoundaryAuthority;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.uniqueProposals;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.visibleSelection;

import com.fasterxml.jackson.core.JsonProcessingException;
import de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole;
import de.regelsuche.discovery.representation.RepresentationDiscoveryInformationBoundary.CandidateFreezeReceipt;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRunOutcome.TerminalState;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.util.AtomicJsonFile;
import de.regelsuche.validation.CandidateProofStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Post-freeze salience calibration over the existing hard target-free matrix.
 *
 * <p>Candidate assessment and rank order are frozen from the formation plan and
 * candidate-freeze bytes before the qualification artifact is opened. Historical
 * correspondence is used only by {@link #qualify}.</p>
 */
public final class TargetFreeHeldOutSaliencePilot {
    public static final String RANKING_SCHEMA =
        "regelsuche.target-free-held-out-salience-ranking-freeze/v1";
    public static final String PILOT_SCHEMA =
        "regelsuche.target-free-held-out-salience-pilot/v1";
    public static final String RANKING_FILE_NAME =
        "target-free-held-out-salience-ranking-freeze.json";
    public static final String PILOT_FILE_NAME =
        "target-free-held-out-salience-pilot.json";
    public static final String PILOT_MARKDOWN_FILE_NAME =
        "target-free-held-out-salience-pilot.md";
    public static final String RUN_DIRECTORY = "runs";
    public static final int DEFAULT_RANKING_CUTOFF = 5;
    public static final String RANKING_PROFILE =
        "CURRENT_REPRESENTATION_ASSESSOR_BALANCED_V1";
    public static final String RANKING_CLAIM_BOUNDARY =
        "Candidate assessment and ranking use only the already frozen target-free "
            + "candidate set, the committed post-freeze structure catalog, and "
            + "intrinsic representation evidence. Qualification references, "
            + "historical names and expected outcomes remain undisclosed.";
    public static final String PILOT_CLAIM_BOUNDARY =
        "Calibration-only localization of recognition and top-k ranking losses "
            + "on an already exposed retained-candidate matrix. The source "
            + "artifact contains neither independent bounded-reachability "
            + "receipts nor pre-retention stage sets, so observed hits remain "
            + "without oracle confirmation and reached/formed/retained are "
            + "retained-candidate projections; not fresh TEST evidence, expert "
            + "interestingness, external novelty or global transformation-space "
            + "completeness.";

    private static final String RANKING_STATUS =
        "CANDIDATES_ASSESSED_AND_RANKED_QUALIFICATION_NOT_DISCLOSED";
    private static final String PILOT_STATUS =
        "POST_FREEZE_CORRESPONDENCE_EVALUATED_CALIBRATION_ONLY";
    private static final ExpressionScorer SCORER = new ExpressionScorer();
    static final RepresentationSalienceCaseAudit.ReferenceReachability
        RETAINED_MATRIX_REACHABILITY = UNSUPPORTED;
    static final String RETAINED_MATRIX_REACHABILITY_EVIDENCE =
        "INDEPENDENT_BOUNDED_REACHABILITY_NOT_AVAILABLE_IN_SOURCE_ARTIFACT";

    private TargetFreeHeldOutSaliencePilot() {
    }

    static RankingFreezeResult freezeRanking(
        PlanArtifact plan,
        FreezeArtifact freeze,
        int rankingCutoff
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(freeze, "freeze");
        if (rankingCutoff < 1) {
            throw new IllegalArgumentException(
                "rankingCutoff must be positive");
        }
        requirePlanFreezeBinding(plan, freeze);
        Map<String, CaseSpec> cases = index(
            plan.content().cases(), CaseSpec::id);
        Map<String, PolicySpec> policies = index(
            plan.content().policies(), PolicySpec::id);

        List<RankingRow> rows = new ArrayList<>();
        List<RepresentationDiscoveryRunWorkspace> workspaces =
            new ArrayList<>();
        for (FreezeRow frozen : freeze.content().rows()) {
            CaseSpec benchmarkCase = Objects.requireNonNull(
                cases.get(frozen.caseId()));
            PolicySpec policy = Objects.requireNonNull(
                policies.get(frozen.policyId()));
            RepresentationDiscoveryInformationBoundary boundary =
                RepresentationDiscoveryInformationBoundary.fromKnowledgePacks(
                    benchmarkCase.informationTrack(),
                    visibleSelection(benchmarkCase)
                );
            requireBoundaryAuthority(frozen, boundary);
            List<RepresentationCandidateProposal> proposals = uniqueProposals(
                benchmarkCase.sourceExpression(), frozen.candidates());
            CandidateFreezeReceipt receipt = boundary.freezeCandidates(
                proposals);
            requireCandidateFreezeBinding(frozen, receipt, proposals.size());
            var disclosure = boundary.disclosePostFreeze(receipt);
            if (!disclosure.classificationCatalog().contentHash().equals(
                    frozen.postFreezeCatalogCommitment())) {
                throw new IllegalArgumentException(
                    "post-freeze catalog differs from its commitment");
            }

            List<RankedCandidate> candidates = rankCandidates(
                benchmarkCase,
                frozen.candidates(),
                disclosure.classificationCatalog()
            );
            if (candidates.size() != frozen.candidateSetCount()) {
                throw new IllegalArgumentException(
                    "ranked candidate set differs from frozen candidate set");
            }
            List<String> recognized = candidates.stream()
                .filter(RankedCandidate::recognized)
                .map(RankedCandidate::representationId)
                .sorted()
                .toList();
            List<String> selected = candidates.stream()
                .filter(RankedCandidate::recognized)
                .limit(rankingCutoff)
                .map(RankedCandidate::representationId)
                .sorted()
                .toList();
            RepresentationDiscoveryRunWorkspace workspace = workspace(
                plan,
                benchmarkCase,
                policy,
                frozen,
                boundary
            );
            workspaces.add(workspace);
            rows.add(new RankingRow(
                frozen.sequence(),
                frozen.configurationId(),
                frozen.caseId(),
                frozen.policyId(),
                frozen.checkpoint(),
                frozen.informationBoundaryHash(),
                workspace.runId(),
                frozen.candidateBatchHash(),
                frozen.candidateSetHash(),
                frozen.candidateFreezeReceiptHash(),
                disclosure.contentHash(),
                disclosure.classificationCatalog().contentHash(),
                rankingCutoff,
                candidates,
                recognized,
                selected
            ));
        }
        RankingFreezeContent content = new RankingFreezeContent(
            RANKING_SCHEMA,
            RANKING_STATUS,
            plan.content().repositoryRevision(),
            plan.contentHash(),
            freeze.contentHash(),
            plan.content().qualificationHash(),
            plan.content().qualificationByteLength(),
            QUALIFICATION_NOT_DISCLOSED,
            RANKING_PROFILE,
            rankingCutoff,
            rows,
            RANKING_CLAIM_BOUNDARY
        );
        return new RankingFreezeResult(
            RankingFreezeArtifact.create(content),
            workspaces
        );
    }

    static PilotArtifact qualify(
        PlanArtifact plan,
        FreezeArtifact freeze,
        RankingFreezeArtifact ranking,
        QualificationArtifact qualification
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(freeze, "freeze");
        Objects.requireNonNull(ranking, "ranking");
        Objects.requireNonNull(qualification, "qualification");
        requirePilotBinding(plan, freeze, ranking, qualification);

        Map<String, RankingRow> rankingRows = index(
            ranking.content().rows(), RankingRow::configurationId);
        List<PilotRow> rows = qualification.content().rows().stream()
            .map(qualified -> evaluateRow(
                plan,
                Objects.requireNonNull(rankingRows.get(
                    qualified.configurationId())),
                qualified
            ))
            .toList();
        PilotContent content = new PilotContent(
            PILOT_SCHEMA,
            PILOT_STATUS,
            plan.content().repositoryRevision(),
            plan.contentHash(),
            freeze.contentHash(),
            ranking.contentHash(),
            qualification.contentHash(),
            ranking.content().rankingProfile(),
            ranking.content().rankingCutoff(),
            rows,
            PilotSummary.derive(rows),
            PILOT_CLAIM_BOUNDARY
        );
        return PilotArtifact.create(content);
    }

    public static PilotArtifact write(
        Path matrixDirectory,
        Path outputDirectory
    ) throws IOException {
        Path matrix = Objects.requireNonNull(
            matrixDirectory, "matrixDirectory")
            .toAbsolutePath().normalize();
        Path output = Objects.requireNonNull(
            outputDirectory, "outputDirectory")
            .toAbsolutePath().normalize();
        Files.createDirectories(output);

        PlanArtifact plan = PlanArtifact.fromCanonicalJson(Files.readString(
            matrix.resolve(PLAN_FILE_NAME), StandardCharsets.UTF_8));
        FreezeArtifact freeze = FreezeArtifact.fromCanonicalJson(
            Files.readString(
                matrix.resolve(FREEZE_FILE_NAME), StandardCharsets.UTF_8));
        RankingFreezeResult result = freezeRanking(
            plan, freeze, DEFAULT_RANKING_CUTOFF);
        Path rankingPath = output.resolve(RANKING_FILE_NAME);
        AtomicJsonFile.writeUtf8(
            rankingPath,
            result.artifact().toCanonicalJson()
        );
        RankingFreezeArtifact reloaded =
            RankingFreezeArtifact.fromCanonicalJson(
                Files.readString(rankingPath, StandardCharsets.UTF_8));
        if (!reloaded.equals(result.artifact())) {
            throw new IllegalStateException(
                "ranking freeze changed before qualification");
        }
        Path runDirectory = output.resolve(RUN_DIRECTORY);
        for (RepresentationDiscoveryRunWorkspace workspace
                : result.workspaces()) {
            RepresentationDiscoveryRunWorkspace.retain(
                runDirectory, workspace);
        }

        // The qualification bytes are first opened after the ranking artifact
        // and all bound search-run workspaces have been durably retained.
        QualificationArtifact qualification =
            QualificationArtifact.fromCanonicalJson(Files.readString(
                matrix.resolve(QUALIFICATION_FILE_NAME),
                StandardCharsets.UTF_8
            ));
        PilotArtifact pilot = qualify(
            plan, freeze, reloaded, qualification);
        Path pilotPath = output.resolve(PILOT_FILE_NAME);
        AtomicJsonFile.writeUtf8(
            pilotPath,
            pilot.toCanonicalJson()
        );
        PilotArtifact reloadedPilot = PilotArtifact.fromCanonicalJson(
            Files.readString(pilotPath, StandardCharsets.UTF_8));
        if (!reloadedPilot.equals(pilot)) {
            throw new IllegalStateException(
                "salience pilot changed during canonical replay");
        }
        AtomicJsonFile.writeUtf8(
            output.resolve(PILOT_MARKDOWN_FILE_NAME),
            pilot.toMarkdown()
        );
        return pilot;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                "usage: <held-out-matrix-directory> <output-directory>");
        }
        PilotArtifact pilot = write(Path.of(args[0]), Path.of(args[1]));
        PilotSummary summary = pilot.content().summary();
        System.out.println("saliencePilotHash=" + pilot.contentHash());
        System.out.println("saliencePilotRetainedReferenceRows="
            + summary.retainedReferencePositiveRows());
        System.out.println("saliencePilotRecognizedRows="
            + summary.recognizedPositiveRows());
        System.out.println("saliencePilotRankedAtCutoffRows="
            + summary.rankedPositiveRows());
        System.out.println("saliencePilotRecognitionMissRows="
            + summary.retainedNotRecognizedRows());
        System.out.println("saliencePilotRankingMissRows="
            + summary.recognizedNotRankedRows());
    }

    private static PilotRow evaluateRow(
        PlanArtifact plan,
        RankingRow ranking,
        QualificationRow qualified
    ) {
        if (ranking.sequence() != qualified.sequence()
                || !ranking.caseId().equals(qualified.caseId())
                || !ranking.policyId().equals(qualified.policyId())
                || ranking.checkpoint() != qualified.checkpoint()) {
            throw new IllegalArgumentException(
                "ranking and qualification rows differ");
        }
        boolean positive = POSITIVE_OUTCOME.equals(
            qualified.expectedOutcome());
        List<String> relevant = qualified.candidates().stream()
            .filter(candidate -> positive
                ? candidate.referenceMatched()
                : candidate.negativeControlViolation())
            .map(candidate -> representationId(
                candidate.expression(), candidate.assumptions()))
            .distinct().sorted().toList();
        List<String> rankedIds = ranking.candidates().stream()
            .map(RankedCandidate::representationId)
            .toList();
        if (!new TreeSet<>(rankedIds).containsAll(relevant)) {
            throw new IllegalArgumentException(
                "qualified representation is absent from ranking freeze");
        }
        List<String> recognized = intersection(
            relevant, ranking.recognizedRepresentationIds());
        List<String> selected = intersection(
            relevant, ranking.selectedRepresentationIds());
        RepresentationSalienceStageSet relevantSet =
            RepresentationSalienceStageSet.of(relevant);
        RepresentationSalienceStageSet recognizedSet =
            RepresentationSalienceStageSet.of(recognized);
        RepresentationSalienceStageSet selectedSet =
            RepresentationSalienceStageSet.of(selected);
        String rowHash = KnownStructureCatalog.sha256(canonical(ranking));
        // This source begins at retained candidates and contains no independent
        // bounded-reachability receipt. An observed policy hit must therefore
        // not become an oracle-confirmed reachability numerator.
        RepresentationSalienceCaseAudit caseAudit =
            RepresentationSalienceCaseAudit.create(
                qualified.caseId(),
                positive ? POSITIVE_REFERENCE : NEGATIVE_OR_ALIAS_CONTROL,
                RETAINED_MATRIX_REACHABILITY,
                KnownStructureCatalog.sha256(
                    PILOT_SCHEMA + "/"
                        + RETAINED_MATRIX_REACHABILITY_EVIDENCE + "/"
                        + qualified.configurationId()
                ),
                qualified.candidateBatchHash(),
                qualified.candidateSetHash(),
                qualified.candidateFreezeReceiptHash(),
                rowHash,
                rowHash,
                KnownStructureCatalog.sha256(
                    PILOT_SCHEMA + "/expert-review/NOT_EVALUATED"),
                relevantSet,
                relevantSet,
                relevantSet,
                recognizedSet,
                selectedSet,
                ranking.rankingCutoff(),
                NOT_EVALUATED
            );
        RepresentationSalienceAudit audit = RepresentationSalienceAudit.create(
            PILOT_SCHEMA + "/" + qualified.configurationId(),
            plan.content().repositoryRevision(),
            ranking.runWorkspaceHash(),
            ranking.informationBoundaryHash(),
            List.of(caseAudit)
        );
        int bestRank = ranking.candidates().stream()
            .filter(candidate -> relevant.contains(
                candidate.representationId()))
            .mapToInt(RankedCandidate::rank)
            .min().orElse(0);
        return new PilotRow(
            qualified.sequence(),
            qualified.configurationId(),
            qualified.caseId(),
            qualified.policyId(),
            qualified.checkpoint(),
            positive ? "POSITIVE_REFERENCE" : "NEGATIVE_OR_ALIAS_CONTROL",
            ranking.candidates().size(),
            ranking.recognizedRepresentationIds().size(),
            ranking.selectedRepresentationIds().size(),
            relevant.size(),
            recognized.size(),
            selected.size(),
            bestRank,
            audit
        );
    }

    static List<RankedCandidate> rankCandidates(
        CaseSpec benchmarkCase,
        List<CandidateEvidence> lineages,
        KnownStructureCatalog catalog
    ) {
        Map<String, CandidateGroup> groups = new TreeMap<>();
        for (CandidateEvidence lineage : lineages) {
            groups.compute(
                lineage.proposalKey(),
                (ignored, existing) -> existing == null
                    ? CandidateGroup.first(lineage)
                    : existing.add(lineage)
            );
        }
        RepresentationCandidateAssessor assessor =
            new RepresentationCandidateAssessor(catalog);
        List<RankedCandidate> candidates = groups.values().stream()
            .map(group -> candidate(
                benchmarkCase.sourceExpression(), group, assessor))
            .sorted(TargetFreeHeldOutSaliencePilot::compareCandidates)
            .toList();
        List<RankedCandidate> ranked = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            ranked.add(candidates.get(index).withRank(index + 1));
        }
        return List.copyOf(ranked);
    }

    private static RankedCandidate candidate(
        String source,
        CandidateGroup group,
        RepresentationCandidateAssessor assessor
    ) {
        CandidateEvidence representative = group.representative();
        RepresentationCandidateAssessment assessment = assessor.assess(
            RepresentationCandidateProposal.whole(
                source,
                representative.expression(),
                representative.assumptions(),
                CandidateProofStatus.OBSERVED
            )
        );
        List<String> structures = assessment
            .newlyExposedStructureMatches().stream()
            .map(match -> match.structureId()
                + "@" + match.occurrencePath())
            .distinct().sorted().toList();
        List<String> consequences = assessment
            .newlyUnlockedConsequences().stream()
            .map(KnownStructureConsequenceUnlock::consequenceId)
            .distinct().sorted().toList();
        boolean recognized = group.equivalencePreserving()
            && (!assessment.candidateTypes().equals(
                    List.of(TYPE_NO_MATERIAL_REPRESENTATION_GAIN))
                || !structures.isEmpty()
                || !consequences.isEmpty());
        return new RankedCandidate(
            representationId(
                representative.expression(), representative.assumptions()),
            representative.expression(),
            representative.assumptions(),
            group.minimumDepth(),
            group.lineageCount(),
            group.equivalencePreserving(),
            group.temporaryComplexityIncrease(),
            SCORER.score(representative.expression()).weightedTotal(),
            assessment.compressionStatus(),
            assessment.scopedCompressionDelta().improvedDimensions(),
            assessment.scopedCompressionDelta().regressedDimensions(),
            assessment.candidateTypes(),
            structures,
            consequences,
            assessment.warnings(),
            recognized,
            0
        );
    }

    private static int compareCandidates(
        RankedCandidate left,
        RankedCandidate right
    ) {
        int compared = Boolean.compare(right.recognized(), left.recognized());
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(
            right.newlyUnlockedConsequences().size(),
            left.newlyUnlockedConsequences().size()
        );
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(
            right.newlyExposedStructures().size(),
            left.newlyExposedStructures().size()
        );
        if (compared != 0) {
            return compared;
        }
        compared = Boolean.compare(
            COMPRESSION_MATERIAL_MULTI_DIMENSIONAL.equals(
                right.compressionStatus()),
            COMPRESSION_MATERIAL_MULTI_DIMENSIONAL.equals(
                left.compressionStatus())
        );
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(
            right.improvedDimensions().size(),
            left.improvedDimensions().size()
        );
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(
            left.regressedDimensions().size(),
            right.regressedDimensions().size()
        );
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(
            left.expressionScore(), right.expressionScore());
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(left.minimumDepth(), right.minimumDepth());
        return compared != 0
            ? compared
            : left.representationId().compareTo(right.representationId());
    }

    private static RepresentationDiscoveryRunWorkspace workspace(
        PlanArtifact plan,
        CaseSpec benchmarkCase,
        PolicySpec policy,
        FreezeRow frozen,
        RepresentationDiscoveryInformationBoundary boundary
    ) {
        RepresentationDiscoveryRunInput input =
            RepresentationDiscoveryRunInput.expression(
                benchmarkCase.sourceExpression(), benchmarkCase.assumptions());
        String budgetHash = KnownStructureCatalog.sha256(canonical(Map.of(
            "configurationId", frozen.configurationId(),
            "checkpoint", frozen.checkpoint(),
            "budget", benchmarkCase.budget()
        )));
        RepresentationDiscoveryRunPlan runPlan =
            RepresentationDiscoveryRunPlan.create(
                benchmarkCase.informationTrack(),
                frozen.informationBoundaryHash(),
                frozen.formationRuleInventoryHash(),
                frozen.formationSelectionCommitment(),
                boundary.candidateFormationCatalog().contentHash(),
                policy.adapterInterface(),
                policy.id(),
                "TARGET_FREE_REPRESENTATION_FORMATION",
                budgetHash,
                policy.deterministicSeed(),
                List.of(policy.adapter())
            );
        TerminalState terminal = NOT_COMPARABLE.equals(frozen.status())
            ? NO_RESULT : COMPLETED;
        RepresentationDiscoveryRunOutcome outcome =
            RepresentationDiscoveryRunOutcome.create(
                terminal,
                frozen.terminalReason(),
                frozen.checkpoint(),
                frozen.work().admittedPrimitiveSteps(),
                frozen.work().contentHash(),
                KnownStructureCatalog.sha256(
                    PILOT_SCHEMA + "/runtime/NOT_MEASURED/"
                        + frozen.configurationId())
            );
        Map<ArtifactRole, RepresentationDiscoveryArtifactReference> artifacts =
            new EnumMap<>(ArtifactRole.class);
        artifacts.put(SEARCH_GRAPH,
            RepresentationDiscoveryArtifactReference.available(
                SEARCH_GRAPH,
                FREEZE_SCHEMA + "/candidate-lineages/v1",
                frozen.candidateBatchHash()));
        artifacts.put(REPRESENTATION_CANDIDATES,
            RepresentationDiscoveryArtifactReference.available(
                REPRESENTATION_CANDIDATES,
                FREEZE_SCHEMA + "/candidate-set/v1",
                frozen.candidateSetHash()));
        artifacts.put(PROGRESS_LEDGER,
            RepresentationDiscoveryArtifactReference.available(
                PROGRESS_LEDGER,
                frozen.work().schema(),
                frozen.work().contentHash()));
        for (ArtifactRole role : List.of(
                CANDIDATE_DOSSIERS,
                PATH_REPLAY,
                RULE_RADAR,
                PROOF_OBLIGATIONS,
                EXPORT_BUNDLE)) {
            artifacts.put(
                role,
                RepresentationDiscoveryArtifactReference.notProduced(role));
        }
        return RepresentationDiscoveryRunWorkspace.create(
            input,
            runPlan,
            outcome,
            List.copyOf(artifacts.values()),
            RepresentationDiscoveryRevisionEvidence.create(
                plan.content().repositoryRevision(),
                PILOT_SCHEMA
            )
        );
    }

    private static void requirePlanFreezeBinding(
        PlanArtifact plan,
        FreezeArtifact freeze
    ) {
        if (!plan.content().repositoryRevision().equals(
                freeze.content().repositoryRevision())
                || !plan.contentHash().equals(freeze.content().planHash())
                || !plan.content().qualificationHash().equals(
                    freeze.content().qualificationHash())
                || freeze.content().rows().size() != 144) {
            throw new IllegalArgumentException(
                "plan and candidate freeze do not share one authority");
        }
    }

    private static void requireCandidateFreezeBinding(
        FreezeRow frozen,
        CandidateFreezeReceipt receipt,
        int proposalCount
    ) {
        if (receipt.candidateCount() != proposalCount
                || receipt.candidateCount() != frozen.candidateSetCount()
                || !receipt.candidateSetHash().equals(
                    frozen.candidateSetHash())
                || !receipt.contentHash().equals(
                    frozen.candidateFreezeReceiptHash())) {
            throw new IllegalArgumentException(
                "candidate set changed before salience ranking");
        }
    }

    private static void requirePilotBinding(
        PlanArtifact plan,
        FreezeArtifact freeze,
        RankingFreezeArtifact ranking,
        QualificationArtifact qualification
    ) {
        requirePlanFreezeBinding(plan, freeze);
        if (!ranking.content().repositoryRevision().equals(
                plan.content().repositoryRevision())
                || !ranking.content().planHash().equals(plan.contentHash())
                || !ranking.content().candidateFreezeHash().equals(
                    freeze.contentHash())
                || !ranking.content().qualificationHash().equals(
                    plan.content().qualificationHash())
                || !qualification.content().planHash().equals(
                    plan.contentHash())
                || !qualification.content().candidateFreezeHash().equals(
                    freeze.contentHash())) {
            throw new IllegalArgumentException(
                "salience pilot artifacts do not share one authority");
        }
    }

    private static <T> Map<String, T> index(
        List<T> values,
        Function<T, String> key
    ) {
        return values.stream().collect(Collectors.toUnmodifiableMap(
            key,
            Function.identity()
        ));
    }

    private static List<String> intersection(
        List<String> left,
        List<String> right
    ) {
        TreeSet<String> result = new TreeSet<>(left);
        result.retainAll(right);
        return List.copyOf(result);
    }

    static String representationId(
        String expression,
        List<String> assumptions
    ) {
        StringBuilder descriptor = new StringBuilder();
        KnownStructureCatalog.appendCanonicalField(
            descriptor, RANKING_SCHEMA + "/representation/v1");
        KnownStructureCatalog.appendCanonicalField(
            descriptor, Objects.requireNonNull(expression, "expression"));
        KnownStructureCatalog.appendCanonicalList(
            descriptor, new TreeSet<>(assumptions).stream().toList());
        return KnownStructureCatalog.sha256(descriptor.toString());
    }

    private record CandidateGroup(
        CandidateEvidence representative,
        int minimumDepth,
        int lineageCount,
        boolean equivalencePreserving,
        boolean temporaryComplexityIncrease
    ) {
        static CandidateGroup first(CandidateEvidence candidate) {
            return new CandidateGroup(
                candidate,
                candidate.depth(),
                1,
                candidate.equivalencePreserving(),
                candidate.temporaryComplexityIncrease()
            );
        }

        CandidateGroup add(CandidateEvidence candidate) {
            CandidateEvidence selected = representative;
            int selectedDepth = minimumDepth;
            boolean preferCandidate = candidate.equivalencePreserving()
                    && !equivalencePreserving
                || candidate.equivalencePreserving()
                    == equivalencePreserving
                    && candidate.depth() < minimumDepth;
            if (preferCandidate) {
                selected = candidate;
                selectedDepth = candidate.depth();
            }
            return new CandidateGroup(
                selected,
                selectedDepth,
                lineageCount + 1,
                equivalencePreserving || candidate.equivalencePreserving(),
                temporaryComplexityIncrease
                    || candidate.equivalencePreserving()
                        && candidate.temporaryComplexityIncrease()
            );
        }
    }

    public record RankedCandidate(
        String representationId,
        String expression,
        List<String> assumptions,
        int minimumDepth,
        int lineageCount,
        boolean equivalencePreserving,
        boolean temporaryComplexityIncrease,
        int expressionScore,
        String compressionStatus,
        List<String> improvedDimensions,
        List<String> regressedDimensions,
        List<String> candidateTypes,
        List<String> newlyExposedStructures,
        List<String> newlyUnlockedConsequences,
        List<String> warnings,
        boolean recognized,
        int rank
    ) {
        public RankedCandidate {
            representationId = requireSha(representationId, "representationId");
            expression = requireText(expression, "expression");
            assumptions = sorted(assumptions);
            if (minimumDepth < 1 || lineageCount < 1 || rank < 0) {
                throw new IllegalArgumentException(
                    "invalid ranked-candidate counters");
            }
            compressionStatus = requireText(
                compressionStatus, "compressionStatus");
            improvedDimensions = sorted(improvedDimensions);
            regressedDimensions = sorted(regressedDimensions);
            candidateTypes = sorted(candidateTypes);
            newlyExposedStructures = sorted(newlyExposedStructures);
            newlyUnlockedConsequences = sorted(
                newlyUnlockedConsequences);
            warnings = sorted(warnings);
        }

        RankedCandidate withRank(int value) {
            return new RankedCandidate(
                representationId,
                expression,
                assumptions,
                minimumDepth,
                lineageCount,
                equivalencePreserving,
                temporaryComplexityIncrease,
                expressionScore,
                compressionStatus,
                improvedDimensions,
                regressedDimensions,
                candidateTypes,
                newlyExposedStructures,
                newlyUnlockedConsequences,
                warnings,
                recognized,
                value
            );
        }
    }

    public record RankingRow(
        int sequence,
        String configurationId,
        String caseId,
        String policyId,
        int checkpoint,
        String informationBoundaryHash,
        String runWorkspaceHash,
        String candidateBatchHash,
        String candidateSetHash,
        String candidateFreezeReceiptHash,
        String postFreezeDisclosureHash,
        String classificationCatalogHash,
        int rankingCutoff,
        List<RankedCandidate> candidates,
        List<String> recognizedRepresentationIds,
        List<String> selectedRepresentationIds
    ) {
        public RankingRow {
            if (sequence < 1 || checkpoint < 1 || rankingCutoff < 1) {
                throw new IllegalArgumentException(
                    "invalid ranking-row identity");
            }
            configurationId = requireSha(
                configurationId, "configurationId");
            caseId = requireText(caseId, "caseId");
            policyId = requireText(policyId, "policyId");
            informationBoundaryHash = requireSha(
                informationBoundaryHash, "informationBoundaryHash");
            runWorkspaceHash = requireSha(
                runWorkspaceHash, "runWorkspaceHash");
            candidateBatchHash = requireSha(
                candidateBatchHash, "candidateBatchHash");
            candidateSetHash = requireSha(
                candidateSetHash, "candidateSetHash");
            candidateFreezeReceiptHash = requireSha(
                candidateFreezeReceiptHash,
                "candidateFreezeReceiptHash");
            postFreezeDisclosureHash = requireSha(
                postFreezeDisclosureHash,
                "postFreezeDisclosureHash");
            classificationCatalogHash = requireSha(
                classificationCatalogHash,
                "classificationCatalogHash");
            candidates = List.copyOf(candidates);
            if (candidates.stream()
                    .map(RankedCandidate::representationId)
                    .distinct().count() != candidates.size()) {
                throw new IllegalArgumentException(
                    "ranking row contains duplicate representations");
            }
            for (int index = 0; index < candidates.size(); index++) {
                if (candidates.get(index).rank() != index + 1) {
                    throw new IllegalArgumentException(
                        "candidate ranks are not canonical");
                }
            }
            recognizedRepresentationIds = sortedHashes(
                recognizedRepresentationIds);
            selectedRepresentationIds = sortedHashes(
                selectedRepresentationIds);
            List<String> expectedRecognized = candidates.stream()
                .filter(RankedCandidate::recognized)
                .map(RankedCandidate::representationId)
                .sorted().toList();
            List<String> expectedSelected = candidates.stream()
                .filter(RankedCandidate::recognized)
                .limit(rankingCutoff)
                .map(RankedCandidate::representationId)
                .sorted().toList();
            if (!recognizedRepresentationIds.equals(expectedRecognized)
                    || !selectedRepresentationIds.equals(expectedSelected)) {
                throw new IllegalArgumentException(
                    "ranking-row selection differs from candidate ranks");
            }
        }
    }

    public record RankingFreezeContent(
        String schema,
        String evidenceStatus,
        String repositoryRevision,
        String planHash,
        String candidateFreezeHash,
        String qualificationHash,
        long qualificationByteLength,
        String qualificationDisclosure,
        String rankingProfile,
        int rankingCutoff,
        List<RankingRow> rows,
        String claimBoundary
    ) {
        public RankingFreezeContent {
            if (!RANKING_SCHEMA.equals(schema)
                    || !RANKING_STATUS.equals(evidenceStatus)
                    || !repositoryRevision.matches("[0-9a-f]{40}")
                    || qualificationByteLength < 1
                    || !QUALIFICATION_NOT_DISCLOSED.equals(
                        qualificationDisclosure)
                    || !RANKING_PROFILE.equals(rankingProfile)
                    || rankingCutoff < 1
                    || !RANKING_CLAIM_BOUNDARY.equals(claimBoundary)) {
                throw new IllegalArgumentException(
                    "invalid ranking-freeze content");
            }
            planHash = requireSha(planHash, "planHash");
            candidateFreezeHash = requireSha(
                candidateFreezeHash, "candidateFreezeHash");
            qualificationHash = requireSha(
                qualificationHash, "qualificationHash");
            rows = List.copyOf(rows);
            if (rows.size() != 144) {
                throw new IllegalArgumentException(
                    "ranking freeze must contain 144 rows");
            }
            for (int index = 0; index < rows.size(); index++) {
                RankingRow row = rows.get(index);
                if (row.sequence() != index + 1
                        || row.rankingCutoff() != rankingCutoff) {
                    throw new IllegalArgumentException(
                        "ranking rows are not canonical");
                }
            }
        }
    }

    public record RankingFreezeArtifact(
        RankingFreezeContent content,
        String contentHash
    ) {
        public RankingFreezeArtifact {
            Objects.requireNonNull(content, "content");
            contentHash = requireSha(contentHash, "contentHash");
            if (!KnownStructureCatalog.sha256(canonical(content)).equals(
                    contentHash)) {
                throw new IllegalArgumentException(
                    "ranking-freeze content hash mismatch");
            }
        }

        static RankingFreezeArtifact create(RankingFreezeContent content) {
            return new RankingFreezeArtifact(
                content,
                KnownStructureCatalog.sha256(canonical(content))
            );
        }

        public String toCanonicalJson() {
            return canonical(this);
        }

        public static RankingFreezeArtifact fromCanonicalJson(String source) {
            try {
                RankingFreezeArtifact artifact =
                    TargetFreeHeldOutMatrixRunner.JSON.readValue(
                        Objects.requireNonNull(source, "source"),
                        RankingFreezeArtifact.class
                    );
                if (!artifact.toCanonicalJson().equals(source)) {
                    throw new IllegalArgumentException(
                        "ranking-freeze JSON is not canonical");
                }
                return artifact;
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException(
                    "invalid ranking-freeze JSON", exception);
            }
        }
    }

    record RankingFreezeResult(
        RankingFreezeArtifact artifact,
        List<RepresentationDiscoveryRunWorkspace> workspaces
    ) {
        RankingFreezeResult {
            Objects.requireNonNull(artifact, "artifact");
            workspaces = List.copyOf(workspaces);
            if (workspaces.size() != artifact.content().rows().size()) {
                throw new IllegalArgumentException(
                    "ranking workspaces do not balance");
            }
        }
    }

    public record PilotRow(
        int sequence,
        String configurationId,
        String caseId,
        String policyId,
        int checkpoint,
        String role,
        int candidateCount,
        int recognizedCandidateCount,
        int selectedCandidateCount,
        int relevantRepresentationCount,
        int recognizedRelevantCount,
        int selectedRelevantCount,
        int bestRelevantRank,
        RepresentationSalienceAudit audit
    ) {
        public PilotRow {
            if (sequence < 1 || checkpoint < 1
                    || candidateCount < 0
                    || recognizedCandidateCount < 0
                    || selectedCandidateCount < 0
                    || relevantRepresentationCount < 0
                    || recognizedRelevantCount < 0
                    || selectedRelevantCount < 0
                    || bestRelevantRank < 0
                    || selectedCandidateCount > recognizedCandidateCount
                    || recognizedRelevantCount > relevantRepresentationCount
                    || selectedRelevantCount > recognizedRelevantCount
                    || bestRelevantRank > candidateCount) {
                throw new IllegalArgumentException(
                    "pilot-row counters do not balance");
            }
            configurationId = requireSha(
                configurationId, "configurationId");
            caseId = requireText(caseId, "caseId");
            policyId = requireText(policyId, "policyId");
            role = requireText(role, "role");
            audit = Objects.requireNonNull(audit, "audit");
        }
    }

    public record PilotCaseSummary(
        String caseId,
        int configuredRows,
        int retainedReferenceRows,
        int recognizedRows,
        int rankedRows,
        int bestRank
    ) {
        public PilotCaseSummary {
            caseId = requireText(caseId, "caseId");
            if (configuredRows < 1 || retainedReferenceRows < 0
                    || recognizedRows < 0 || rankedRows < 0
                    || bestRank < 0
                    || rankedRows > recognizedRows
                    || recognizedRows > retainedReferenceRows
                    || retainedReferenceRows > configuredRows) {
                throw new IllegalArgumentException(
                    "pilot case summary does not balance");
            }
        }
    }

    public record PilotSummary(
        int configuredRows,
        int positiveRows,
        int negativeControlRows,
        int retainedReferencePositiveRows,
        int recognizedPositiveRows,
        int rankedPositiveRows,
        int retainedNotRecognizedRows,
        int recognizedNotRankedRows,
        int preRetentionUnresolvedRows,
        int falsePositiveRows,
        int topOneRows,
        int topThreeRows,
        int topFiveRows,
        List<PilotCaseSummary> cases,
        Map<String, Integer> localizationCounts
    ) {
        public PilotSummary {
            if (configuredRows < 1 || positiveRows < 0
                    || negativeControlRows < 0
                    || positiveRows + negativeControlRows != configuredRows
                    || retainedReferencePositiveRows < 0
                    || recognizedPositiveRows < 0
                    || rankedPositiveRows < 0
                    || retainedNotRecognizedRows < 0
                    || recognizedNotRankedRows < 0
                    || preRetentionUnresolvedRows < 0
                    || falsePositiveRows < 0
                    || recognizedPositiveRows
                        > retainedReferencePositiveRows
                    || rankedPositiveRows > recognizedPositiveRows
                    || retainedNotRecognizedRows
                        > retainedReferencePositiveRows
                    || recognizedNotRankedRows > recognizedPositiveRows
                    || retainedReferencePositiveRows
                        + preRetentionUnresolvedRows != positiveRows
                    || falsePositiveRows > negativeControlRows
                    || topOneRows < 0 || topThreeRows < topOneRows
                    || topFiveRows < topThreeRows
                    || topFiveRows > retainedReferencePositiveRows) {
                throw new IllegalArgumentException(
                    "pilot summary counts do not balance");
            }
            cases = List.copyOf(cases);
            localizationCounts = Map.copyOf(
                new TreeMap<>(localizationCounts));
            int localized = localizationCounts.values().stream()
                .mapToInt(Integer::intValue).sum();
            if (localized != configuredRows) {
                throw new IllegalArgumentException(
                    "pilot localizations do not balance");
            }
        }

        static PilotSummary derive(List<PilotRow> rows) {
            int positives = count(rows, row ->
                "POSITIVE_REFERENCE".equals(row.role()));
            int negatives = rows.size() - positives;
            int reached = count(rows, row -> positive(row)
                && row.relevantRepresentationCount() > 0);
            int recognized = count(rows, row -> positive(row)
                && row.recognizedRelevantCount() > 0);
            int ranked = count(rows, row -> positive(row)
                && row.selectedRelevantCount() > 0);
            Map<String, Integer> localizations = new TreeMap<>();
            for (PilotRow row : rows) {
                String localization = row.audit().cases().getFirst()
                    .localization().name();
                localizations.merge(localization, 1, Integer::sum);
            }
            Map<String, List<PilotRow>> byCase = rows.stream().collect(
                Collectors.groupingBy(
                    PilotRow::caseId,
                    TreeMap::new,
                    Collectors.toList()
                ));
            List<PilotCaseSummary> cases = byCase.entrySet().stream()
                .map(entry -> caseSummary(entry.getKey(), entry.getValue()))
                .toList();
            return new PilotSummary(
                rows.size(),
                positives,
                negatives,
                reached,
                recognized,
                ranked,
                localizations.getOrDefault(
                    "RETAINED_NOT_RECOGNIZED", 0),
                localizations.getOrDefault("RECOGNIZED_NOT_RANKED", 0),
                localizations.getOrDefault("UNSUPPORTED", 0),
                localizations.getOrDefault("INVALID_OR_FALSE_POSITIVE", 0),
                count(rows, row -> positive(row)
                    && row.bestRelevantRank() == 1),
                count(rows, row -> positive(row)
                    && row.bestRelevantRank() > 0
                    && row.bestRelevantRank() <= 3),
                count(rows, row -> positive(row)
                    && row.bestRelevantRank() > 0
                    && row.bestRelevantRank() <= 5),
                cases,
                localizations
            );
        }

        private static PilotCaseSummary caseSummary(
            String caseId,
            List<PilotRow> rows
        ) {
            int reached = count(rows, row -> positive(row)
                && row.relevantRepresentationCount() > 0);
            int recognized = count(rows, row -> positive(row)
                && row.recognizedRelevantCount() > 0);
            int ranked = count(rows, row -> positive(row)
                && row.selectedRelevantCount() > 0);
            int best = rows.stream()
                .filter(PilotSummary::positive)
                .mapToInt(PilotRow::bestRelevantRank)
                .filter(value -> value > 0)
                .min().orElse(0);
            return new PilotCaseSummary(
                caseId, rows.size(), reached, recognized, ranked, best);
        }

        private static boolean positive(PilotRow row) {
            return "POSITIVE_REFERENCE".equals(row.role());
        }

        private static int count(
            List<PilotRow> rows,
            java.util.function.Predicate<PilotRow> predicate
        ) {
            return Math.toIntExact(rows.stream().filter(predicate).count());
        }
    }

    public record PilotContent(
        String schema,
        String evidenceStatus,
        String repositoryRevision,
        String planHash,
        String candidateFreezeHash,
        String rankingFreezeHash,
        String qualificationHash,
        String rankingProfile,
        int rankingCutoff,
        List<PilotRow> rows,
        PilotSummary summary,
        String claimBoundary
    ) {
        public PilotContent {
            if (!PILOT_SCHEMA.equals(schema)
                    || !PILOT_STATUS.equals(evidenceStatus)
                    || !repositoryRevision.matches("[0-9a-f]{40}")
                    || !RANKING_PROFILE.equals(rankingProfile)
                    || rankingCutoff < 1
                    || !PILOT_CLAIM_BOUNDARY.equals(claimBoundary)) {
                throw new IllegalArgumentException(
                    "invalid salience-pilot content");
            }
            planHash = requireSha(planHash, "planHash");
            candidateFreezeHash = requireSha(
                candidateFreezeHash, "candidateFreezeHash");
            rankingFreezeHash = requireSha(
                rankingFreezeHash, "rankingFreezeHash");
            qualificationHash = requireSha(
                qualificationHash, "qualificationHash");
            rows = List.copyOf(rows);
            summary = Objects.requireNonNull(summary, "summary");
            if (rows.size() != 144
                    || !summary.equals(PilotSummary.derive(rows))) {
                throw new IllegalArgumentException(
                    "salience-pilot rows do not balance");
            }
            for (int index = 0; index < rows.size(); index++) {
                if (rows.get(index).sequence() != index + 1) {
                    throw new IllegalArgumentException(
                        "pilot rows are not canonical");
                }
            }
        }
    }

    public record PilotArtifact(
        PilotContent content,
        String contentHash
    ) {
        public PilotArtifact {
            Objects.requireNonNull(content, "content");
            contentHash = requireSha(contentHash, "contentHash");
            if (!KnownStructureCatalog.sha256(canonical(content)).equals(
                    contentHash)) {
                throw new IllegalArgumentException(
                    "salience-pilot content hash mismatch");
            }
        }

        static PilotArtifact create(PilotContent content) {
            return new PilotArtifact(
                content,
                KnownStructureCatalog.sha256(canonical(content))
            );
        }

        public String toCanonicalJson() {
            return canonical(this);
        }

        public static PilotArtifact fromCanonicalJson(String source) {
            try {
                PilotArtifact artifact =
                    TargetFreeHeldOutMatrixRunner.JSON.readValue(
                        Objects.requireNonNull(source, "source"),
                        PilotArtifact.class
                    );
                if (!artifact.toCanonicalJson().equals(source)) {
                    throw new IllegalArgumentException(
                        "salience-pilot JSON is not canonical");
                }
                return artifact;
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException(
                    "invalid salience-pilot JSON", exception);
            }
        }

        public String toMarkdown() {
            StringBuilder markdown = new StringBuilder();
            markdown.append("# Target-free held-out salience pilot\n\n")
                .append("- **Ranking profile:** `")
                .append(content.rankingProfile()).append("`\n")
                .append("- **Top-k cutoff:** `")
                .append(content.rankingCutoff()).append("`\n")
                .append("- **Reference present in retained candidates:** `")
                .append(content.summary().retainedReferencePositiveRows())
                .append(" / ").append(content.summary().positiveRows())
                .append("`\n")
                .append("- **Pre-retention outcome unresolved:** `")
                .append(content.summary().preRetentionUnresolvedRows())
                .append("`\n")
                .append("- **Recognized rows:** `")
                .append(content.summary().recognizedPositiveRows())
                .append(" / ")
                .append(content.summary().retainedReferencePositiveRows())
                .append("`\n")
                .append("- **Surfaced in top-k:** `")
                .append(content.summary().rankedPositiveRows())
                .append(" / ")
                .append(content.summary().recognizedPositiveRows())
                .append("`\n")
                .append("- **False-positive control rows:** `")
                .append(content.summary().falsePositiveRows())
                .append("`\n\n> ")
                .append(content.claimBoundary()).append("\n\n")
                .append("| Case | Rows | Retained reference | Recognized | Top-k | Best rank |\n")
                .append("|---|---:|---:|---:|---:|---:|\n");
            for (PilotCaseSummary value : content.summary().cases()) {
                markdown.append("| ").append(value.caseId())
                    .append(" | ").append(value.configuredRows())
                    .append(" | ").append(value.retainedReferenceRows())
                    .append(" | ").append(value.recognizedRows())
                    .append(" | ").append(value.rankedRows())
                    .append(" | ").append(value.bestRank())
                    .append(" |\n");
            }
            markdown.append("\n## Failure localization\n\n");
            content.summary().localizationCounts().forEach((name, count) ->
                markdown.append("- `").append(name).append("`: ")
                    .append(count).append('\n'));
            return markdown.toString();
        }
    }

    private static String requireSha(String value, String field) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be SHA-256");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static List<String> sorted(List<String> values) {
        return List.copyOf(new TreeSet<>(
            Objects.requireNonNull(values, "values")));
    }

    private static List<String> sortedHashes(List<String> values) {
        List<String> result = sorted(values);
        result.forEach(value -> requireSha(value, "representationId"));
        return result;
    }
}
