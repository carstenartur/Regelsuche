package de.regelsuche.release;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.experiments.autopilot.AutonomousProductionCampaignRunner.CampaignRun;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2;
import de.regelsuche.release.ProductionCandidateUtilityEvaluator.UtilityCase;
import de.regelsuche.release.ProductionCandidateUtilityEvaluator.UtilityReport;
import de.regelsuche.release.ReleaseReadinessRunner.ReleaseRun;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/** Canonical, claim-bounded presentation of a qualified autonomous discovery run. */
public final class AutonomousDiscoveryResultCard {
    public static final String SCHEMA =
        "regelsuche.autonomous-discovery-result-card/v1";
    public static final String CLAIM_BANNER = "NO EXTERNAL NOVELTY CLAIM";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObjectNode document;
    private final String canonicalJson;
    private final String markdown;
    private final List<ArtifactBinding> artifacts;

    private AutonomousDiscoveryResultCard(
        ObjectNode document,
        String canonicalJson,
        String markdown,
        List<ArtifactBinding> artifacts
    ) {
        this.document = document;
        this.canonicalJson = canonicalJson;
        this.markdown = markdown;
        this.artifacts = artifacts;
    }

    public static AutonomousDiscoveryResultCard create(
        String repositoryRevision,
        ReleaseRun run,
        Path outputDirectory
    ) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        requireRevision(repositoryRevision);
        requireQualifiedClaim(run);

        CampaignRun campaign = run.retainedCampaign();
        var lifecycle = campaign.lifecycle();
        var generation = lifecycle.mining().generation();
        var qualificationRun = Objects.requireNonNull(
            run.qualificationRun(), "qualificationRun");
        var qualification = Objects.requireNonNull(
            run.qualificationEvidence(), "qualificationEvidence");
        UtilityReport utility = qualificationRun.utility();

        List<ArtifactBinding> bindings = List.of(
            bind(outputDirectory, "campaignManifest",
                "evidence/campaign/production-campaign-manifest.json",
                campaign.contentHash()),
            bind(outputDirectory, "candidateLineage",
                "evidence/campaign/full-lineage-v2.json",
                lifecycle.mining().fullBatch().lineage().contentHash()),
            bind(outputDirectory, "counterexampleReport",
                "evidence/campaign/counterexample-report.json",
                lifecycle.counterexampleHash()),
            bind(outputDirectory, "fullMiningEvidence",
                "evidence/campaign/full-mining-evidence.json",
                lifecycle.mining().fullBatch().evidence().contentHash()),
            bind(outputDirectory, "observationBundle",
                "evidence/campaign/observations.json",
                generation.observationBundle().contentHash()),
            bind(outputDirectory, "pairedUtility",
                "evidence/qualification/qualification-utility.json",
                utility.contentHash()),
            bind(outputDirectory, "projectNoveltyReport",
                "evidence/campaign/project-novelty-report.json",
                lifecycle.noveltyHash()),
            bind(outputDirectory, "proofReport",
                "evidence/campaign/proof-report.json",
                lifecycle.proof().evidenceHash()),
            bind(outputDirectory, "qualificationEvaluation",
                "evidence/qualification/qualification-evaluation.json",
                qualificationRun.evaluationHash()),
            bind(outputDirectory, "qualificationEvidence",
                "evidence/qualification/candidate-qualification-evidence.json",
                qualification.contentHash()),
            bind(outputDirectory, "releaseEvidence",
                "evidence/evidence-summary.json",
                run.evidence().evidenceHash()),
            bind(outputDirectory, "releaseReadinessMatrix",
                "evidence/release-readiness-report.json",
                run.matrix().contentHash()),
            bind(outputDirectory, "releaseReadinessRun",
                "evidence/release-readiness-run.json",
                run.contentHash()),
            bind(outputDirectory, "researchBrief",
                "evidence/campaign/brief-v2.json",
                generation.brief().contentHash()),
            bind(outputDirectory, "validationReport",
                "evidence/campaign/validation-report.json",
                lifecycle.validationHash())
        ).stream().sorted(Comparator.comparing(ArtifactBinding::role)).toList();

        PairedSummary pairedSummary = PairedSummary.from(utility.cases());
        UtilityCase representative = utility.cases().stream()
            .filter(UtilityCase::materialGain)
            .findFirst()
            .orElse(utility.cases().getFirst());

        ObjectNode root = MAPPER.createObjectNode();
        root.put("schema", SCHEMA);
        root.put("repositoryRevision", repositoryRevision);
        root.put("runIdentity", run.contentHash());
        root.put("claimBanner", CLAIM_BANNER);

        ObjectNode brief = root.putObject("researchBrief");
        brief.put("briefHash", generation.brief().contentHash());
        brief.put("inventoryHash", generation.brief().inventoryHash());
        brief.put("modelHash", generation.brief().modelHash());
        brief.put("targetOrExpectedAnswerAccess", "ABSENT");
        brief.put("targetProvided", false);
        brief.put("seedFamilyCount", campaign.seedFamilyCount());
        brief.put("observationCount", campaign.observationCount());
        brief.put("sourceArtifact", "researchBrief");
        brief.put("countSourceArtifact", "campaignManifest");

        ObjectNode candidate = root.putObject("candidate");
        candidate.put("conjectureId", qualification.conjectureId());
        candidate.put("candidateBranchId", qualification.candidateBranchId());
        candidate.put("leftPattern", qualification.leftPattern());
        candidate.put("rightPattern", qualification.rightPattern());
        addStrings(candidate, "parameterRelations",
            qualification.parameterRelations());
        addStrings(candidate, "assumptions", qualification.assumptions());
        candidate.put("lineageRoot", qualification.lineageHash());
        candidate.put("miningEvidenceHash", qualification.miningEvidenceHash());
        candidate.put("supportingObservationCount",
            qualification.supportingObservationIds().size());
        addStrings(candidate, "supportingObservationIds",
            qualification.supportingObservationIds());
        addStrings(candidate, "sourceObservationBranchHashes",
            qualification.sourceObservationBranchHashes());
        candidate.put("sourceArtifact", "qualificationEvidence");
        candidate.put("lineageSourceArtifact", "candidateLineage");

        ObjectNode lifecycleNode = root.putObject("lifecycle");
        lifecycleNode.put("validationStatus",
            lifecycle.evaluation().status().name());
        lifecycleNode.put("counterexampleStatus",
            lifecycle.evaluation().counterexample().status());
        lifecycleNode.put("counterexampleStrategyCount",
            lifecycle.evaluation().counterexample().attemptedSources().stream()
                .distinct().count());
        lifecycleNode.put("projectNoveltyStatus",
            lifecycle.novelty().status().name());
        lifecycleNode.put("externalNoveltyStatus",
            lifecycle.novelty().externalNoveltyStatus());
        lifecycleNode.put("proofEvidenceStatus",
            lifecycle.proof().proofStatus().name());
        lifecycleNode.put("proofBackendStatus",
            lifecycle.proof().result().status().name());
        lifecycleNode.put("proofTranslationStatus",
            lifecycle.proof().result().translationStatus().name());
        lifecycleNode.put("formalProofStatus",
            lifecycle.proof().formalProofStatus());
        lifecycleNode.put("lifecycleOutcome",
            lifecycle.lifecycleDecision().outcome().name());
        lifecycleNode.put("validationSourceArtifact", "validationReport");
        lifecycleNode.put("counterexampleSourceArtifact",
            "counterexampleReport");
        lifecycleNode.put("noveltySourceArtifact", "projectNoveltyReport");
        lifecycleNode.put("proofSourceArtifact", "proofReport");

        ObjectNode qualificationNode = root.putObject("qualification");
        qualificationNode.put("qualified", qualification.qualified());
        qualificationNode.put("heldOutFamilyOrClusterCount",
            qualification.heldOutFamilyOrClusterCount());
        qualificationNode.put("configuredPositiveHoldouts",
            qualification.configuredPositiveHoldouts());
        qualificationNode.put("executedPositiveHoldouts",
            qualification.executedPositiveHoldouts());
        qualificationNode.put("configuredNegativeHoldouts",
            qualification.configuredNegativeHoldouts());
        qualificationNode.put("executedNegativeHoldouts",
            qualification.executedNegativeHoldouts());
        qualificationNode.put("mandatorySkippedWorkCount",
            qualification.mandatorySkippedWorkCount());
        qualificationNode.put("refutingHoldouts",
            qualification.refutingHoldouts());
        qualificationNode.put("counterexamplesFound",
            qualification.counterexamplesFound());
        qualificationNode.put("correctnessRegressionCount",
            qualification.correctnessRegressionCount());
        qualificationNode.put("failureCount", Math.addExact(
            Math.addExact(qualification.mandatorySkippedWorkCount(),
                qualification.refutingHoldouts()),
            Math.addExact(qualification.counterexamplesFound(),
                qualification.correctnessRegressionCount())));
        qualificationNode.put("evaluationStatus",
            qualificationRun.evaluation().status().name());
        qualificationNode.put("counterexampleStatus",
            qualificationRun.evaluation().counterexample().status());
        qualificationNode.put("sourceArtifact", "qualificationEvidence");
        qualificationNode.put("evaluationSourceArtifact",
            "qualificationEvaluation");

        ObjectNode paired = root.putObject("pairedUtility");
        paired.put("suiteRevision", utility.suiteRevision());
        paired.put("pairedUtilityEvaluated", utility.pairedUtilityEvaluated());
        paired.put("beneficial", utility.beneficial());
        paired.put("gainPermille", utility.gainPermille());
        paired.put("representativeCaseId", representative.id());
        paired.set("summary", pairedSummary.toJson());
        ArrayNode cases = paired.putArray("cases");
        utility.cases().forEach(item -> cases.add(utilityCase(item)));
        paired.put("sourceArtifact", "pairedUtility");

        ObjectNode reproducibility = root.putObject("reproducibility");
        reproducibility.put("cleanRunCount", run.evidence().cleanRunCount());
        reproducibility.put("cleanRunsIdentical",
            run.evidence().cleanRunsIdentical());
        reproducibility.put("cleanRunStatus",
            run.evidence().cleanRunsIdentical()
                ? "VERIFIED_IDENTICAL" : "FAILED");
        addStrings(reproducibility, "cleanRunManifestHashes",
            run.evidence().cleanRunManifestHashes());
        reproducibility.put("containerParityStatus", "ENFORCED_BY_CI");
        reproducibility.put("containerParityComparison", "BYTE_FOR_BYTE");
        reproducibility.put("containerParityWorkflow",
            ".github/workflows/autonomous-discovery-walkthrough.yml");
        reproducibility.put("sourceArtifact", "releaseEvidence");

        ObjectNode boundaries = root.putObject("claimBoundaries");
        boundaries.put("autonomyClaimAuthorized",
            run.matrix().autonomyClaimAuthorized());
        boundaries.put("externalNoveltyStatus",
            lifecycle.novelty().externalNoveltyStatus());
        boundaries.put("externalNoveltyClaimAuthorized", false);
        boundaries.put("promotionStatus", campaign.promotionStatus());
        boundaries.put("publicEvidenceStatus", campaign.publicEvidenceStatus());
        boundaries.put("releaseSourceArtifact", "releaseReadinessRun");
        boundaries.put("noveltySourceArtifact", "projectNoveltyReport");

        ArrayNode artifactArray = root.putArray("artifacts");
        bindings.forEach(binding -> artifactArray.add(binding.toJson()));

        String material = canonical(root);
        String contentHash = AutonomousResearchBriefV2.hash(material);
        root.put("contentHash", contentHash);
        String json = canonical(root) + "\n";
        String markdown = markdown(root, bindings, representative);
        return new AutonomousDiscoveryResultCard(
            root, json, markdown, bindings);
    }

    public String toCanonicalJson() {
        return canonicalJson;
    }

    public String toMarkdown() {
        return markdown;
    }

    public String contentHash() {
        return document.path("contentHash").asText();
    }

    public List<ArtifactBinding> artifacts() {
        return artifacts;
    }

    ObjectNode document() {
        return document.deepCopy();
    }

    private static void requireQualifiedClaim(ReleaseRun run) {
        var campaign = run.retainedCampaign();
        var lifecycle = campaign.lifecycle();
        var qualification = run.qualificationEvidence();
        if (!run.autonomousCampaignReady()
                || qualification == null
                || !qualification.qualified()
                || !run.evidence().targetFree()
                || campaign.targetProvided()
                || lifecycle.targetProvided()
                || qualification.correctnessRegressionCount() != 0
                || !"NOT_EVALUATED".equals(
                    lifecycle.novelty().externalNoveltyStatus())
                || !"NOT_EVALUATED".equals(campaign.promotionStatus())
                || !"NOT_EVALUATED".equals(campaign.publicEvidenceStatus())) {
            throw new IllegalStateException(
                "qualified autonomous discovery evidence is missing or overclaims its scope");
        }
    }

    private static void requireRevision(String value) {
        if (value == null || !value.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException(
                "repositoryRevision must be a 40-character lowercase Git commit");
        }
    }

    private static ArtifactBinding bind(
        Path outputDirectory,
        String role,
        String relativePath,
        String semanticHash
    ) {
        Path root = outputDirectory.toAbsolutePath().normalize();
        Path file = root.resolve(relativePath).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new IllegalStateException(
                "required walkthrough artifact is missing: " + relativePath);
        }
        requireSha256(semanticHash, "semanticHash");
        return new ArtifactBinding(
            role, relativePath, semanticHash, fileHash(file));
    }

    private static String fileHash(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not hash walkthrough artifact " + path, exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static ObjectNode utilityCase(UtilityCase item) {
        ObjectNode object = MAPPER.createObjectNode();
        object.put("id", item.id());
        object.put("baselineStatus", item.baselineStatus());
        object.put("candidateStatus", item.candidateStatus());
        object.put("baselineReached", item.baselineReached());
        object.put("candidateReached", item.candidateReached());
        object.put("baselinePathLength", item.baselinePathLength());
        object.put("candidatePathLength", item.candidatePathLength());
        object.put("baselineExploredStates", item.baselineExploredStates());
        object.put("candidateExploredStates", item.candidateExploredStates());
        object.put("materialGain", item.materialGain());
        object.put("regression", item.regression());
        return object;
    }

    private static void addStrings(
        ObjectNode object,
        String field,
        List<String> values
    ) {
        ArrayNode array = object.putArray(field);
        values.forEach(array::add);
    }

    private static String canonical(JsonNode value) {
        try {
            return MAPPER.writeValueAsString(sorted(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "Could not render canonical result card", exception);
        }
    }

    private static JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = MAPPER.createObjectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            value.fields().forEachRemaining(entry ->
                fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((name, child) -> result.set(name, sorted(child)));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = MAPPER.createArrayNode();
            value.forEach(child -> result.add(sorted(child)));
            return result;
        }
        return value.deepCopy();
    }

    private static String markdown(
        ObjectNode root,
        List<ArtifactBinding> artifacts,
        UtilityCase representative
    ) {
        StringBuilder text = new StringBuilder();
        text.append("# Autonomous Discovery Result Card\n\n")
            .append("> **").append(CLAIM_BANNER).append("**  \n")
            .append("> The retained candidate is qualified for the project's ")
            .append("autonomous-campaign profile. External mathematical novelty, ")
            .append("promotion and Public Evidence remain separate and unevaluated.\n\n")
            .append("- Repository revision: `")
            .append(root.path("repositoryRevision").asText()).append("`\n")
            .append("- Release run: `")
            .append(root.path("runIdentity").asText()).append("`\n")
            .append("- Result-card identity: `")
            .append(root.path("contentHash").asText()).append("`\n\n")
            .append("## Evidence sequence\n\n")
            .append("```text\n")
            .append("Research Brief\n")
            .append("  -> Seed families\n")
            .append("  -> Untargeted observations\n")
            .append("  -> Aggregate candidate formation\n")
            .append("  -> Validation and counterexample search\n")
            .append("  -> Project novelty and proof evidence\n")
            .append("  -> Qualification\n")
            .append("  -> Paired held-out reuse\n")
            .append("```\n\n")
            .append("![Generated evidence sequence](figures/sequence.svg)\n\n")
            .append("## Headline evidence\n\n")
            .append("| Metric | Value | Authority |\n")
            .append("|---|---:|---|\n");

        ObjectNode brief = (ObjectNode) root.path("researchBrief");
        ObjectNode candidate = (ObjectNode) root.path("candidate");
        ObjectNode lifecycle = (ObjectNode) root.path("lifecycle");
        ObjectNode qualification = (ObjectNode) root.path("qualification");
        ObjectNode paired = (ObjectNode) root.path("pairedUtility");
        ObjectNode summary = (ObjectNode) paired.path("summary");
        ObjectNode reproducibility = (ObjectNode) root.path("reproducibility");
        ObjectNode boundaries = (ObjectNode) root.path("claimBoundaries");

        row(text, "Target/expected-answer access",
            brief.path("targetOrExpectedAnswerAccess").asText(),
            authority(artifacts, "campaignManifest"));
        row(text, "Seed families", brief.path("seedFamilyCount").asText(),
            authority(artifacts, "campaignManifest"));
        row(text, "Untargeted observations",
            brief.path("observationCount").asText(),
            authority(artifacts, "campaignManifest"));
        row(text, "Supporting observations",
            candidate.path("supportingObservationCount").asText(),
            authority(artifacts, "qualificationEvidence"));
        row(text, "Production validation",
            lifecycle.path("validationStatus").asText(),
            authority(artifacts, "validationReport"));
        row(text, "Counterexample search",
            lifecycle.path("counterexampleStatus").asText(),
            authority(artifacts, "counterexampleReport"));
        row(text, "Project novelty",
            lifecycle.path("projectNoveltyStatus").asText(),
            authority(artifacts, "projectNoveltyReport"));
        row(text, "Proof evidence",
            lifecycle.path("proofEvidenceStatus").asText(),
            authority(artifacts, "proofReport"));
        row(text, "Formal proof",
            lifecycle.path("formalProofStatus").asText(),
            authority(artifacts, "proofReport"));
        row(text, "Qualified positive holdouts",
            qualification.path("executedPositiveHoldouts").asText()
                + "/" + qualification.path("configuredPositiveHoldouts").asText(),
            authority(artifacts, "qualificationEvidence"));
        row(text, "Qualified negative holdouts",
            qualification.path("executedNegativeHoldouts").asText()
                + "/" + qualification.path("configuredNegativeHoldouts").asText(),
            authority(artifacts, "qualificationEvidence"));
        row(text, "Qualification failures",
            qualification.path("failureCount").asText(),
            authority(artifacts, "qualificationEvidence"));
        row(text, "Baseline reachability",
            summary.path("baselineReachedCount").asText()
                + "/" + summary.path("caseCount").asText(),
            authority(artifacts, "pairedUtility"));
        row(text, "Candidate-enabled reachability",
            summary.path("candidateReachedCount").asText()
                + "/" + summary.path("caseCount").asText(),
            authority(artifacts, "pairedUtility"));
        row(text, "Paired path-length delta",
            summary.path("pairedPathLengthDeltaForBothReached").asText(),
            authority(artifacts, "pairedUtility"));
        row(text, "Explored-state delta",
            summary.path("exploredStatesDelta").asText(),
            authority(artifacts, "pairedUtility"));
        row(text, "Unsupported candidate cases",
            summary.path("unsupportedCandidateCaseCount").asText(),
            authority(artifacts, "pairedUtility"));
        row(text, "Correctness regressions",
            qualification.path("correctnessRegressionCount").asText(),
            authority(artifacts, "qualificationEvidence"));
        row(text, "Clean runs",
            reproducibility.path("cleanRunCount").asText()
                + " / " + reproducibility.path("cleanRunStatus").asText(),
            authority(artifacts, "releaseEvidence"));
        row(text, "Container parity",
            reproducibility.path("containerParityStatus").asText()
                + " / "
                + reproducibility.path("containerParityComparison").asText(),
            "`.github/workflows/autonomous-discovery-walkthrough.yml`");
        row(text, "External novelty",
            boundaries.path("externalNoveltyStatus").asText(),
            authority(artifacts, "projectNoveltyReport"));
        row(text, "Promotion",
            boundaries.path("promotionStatus").asText(),
            authority(artifacts, "campaignManifest"));
        row(text, "Public Evidence",
            boundaries.path("publicEvidenceStatus").asText(),
            authority(artifacts, "campaignManifest"));

        text.append("\n## Retained candidate\n\n")
            .append("```text\n")
            .append(candidate.path("leftPattern").asText())
            .append("\n  -> ")
            .append(candidate.path("rightPattern").asText())
            .append("\n```\n\n")
            .append("- Candidate: `")
            .append(candidate.path("conjectureId").asText()).append("`\n")
            .append("- Lineage root: `")
            .append(candidate.path("lineageRoot").asText()).append("`\n")
            .append("- Assumptions: ")
            .append(renderList(candidate.path("assumptions"))).append("\n")
            .append("- Parameter relations: ")
            .append(renderList(candidate.path("parameterRelations")))
            .append("\n\n")
            .append("![Generated candidate lineage](figures/candidate-lineage.svg)\n\n")
            .append("## Paired held-out utility\n\n")
            .append("Every number in the following table is derived from ")
            .append(authority(artifacts, "pairedUtility")).append(".\n\n")
            .append("| Case | Baseline status | Candidate status | ")
            .append("Reached B/C | Path length B/C | States B/C | Gain | Regression |\n")
            .append("|---|---|---|---:|---:|---:|---:|---:|\n");
        root.path("pairedUtility").path("cases").forEach(item -> text
            .append('|').append(escape(item.path("id").asText()))
            .append('|').append(escape(item.path("baselineStatus").asText()))
            .append('|').append(escape(item.path("candidateStatus").asText()))
            .append('|').append(item.path("baselineReached").asBoolean())
            .append('/').append(item.path("candidateReached").asBoolean())
            .append('|').append(item.path("baselinePathLength").asInt())
            .append('/').append(item.path("candidatePathLength").asInt())
            .append('|').append(item.path("baselineExploredStates").asLong())
            .append('/').append(item.path("candidateExploredStates").asLong())
            .append('|').append(item.path("materialGain").asBoolean())
            .append('|').append(item.path("regression").asBoolean())
            .append("|\n"));
        text.append("\nRepresentative case: `")
            .append(representative.id()).append("`.\n\n")
            .append("![Generated paired utility](figures/paired-utility.svg)\n\n")
            .append("![Generated representative search comparison]")
            .append("(figures/representative-search.svg)\n\n")
            .append("## Authoritative artifacts\n\n")
            .append("| Role | Relative path | Semantic hash | File SHA-256 |\n")
            .append("|---|---|---|---|\n");
        artifacts.forEach(item -> text
            .append('|').append(item.role())
            .append("|[").append(item.relativePath()).append("](")
            .append(item.relativePath()).append(")")
            .append("|`").append(item.semanticHash()).append("`")
            .append("|`").append(item.fileSha256()).append("`|\n"));
        text.append("\nThe JSON card is canonical and hash-bound. The Markdown and ")
            .append("figures are generated from the same verified in-memory run; ")
            .append("none authorizes an external novelty claim.\n");
        return text.toString();
    }

    private static String renderList(JsonNode values) {
        List<String> rendered = new ArrayList<>();
        values.forEach(value -> rendered.add('`' + value.asText() + '`'));
        return rendered.isEmpty() ? "_none_" : String.join(", ", rendered);
    }

    private static void row(
        StringBuilder text,
        String metric,
        String value,
        String authority
    ) {
        text.append('|').append(escape(metric))
            .append('|').append(escape(value))
            .append('|').append(authority).append("|\n");
    }

    private static String authority(
        List<ArtifactBinding> artifacts,
        String role
    ) {
        ArtifactBinding artifact = artifacts.stream()
            .filter(item -> item.role().equals(role))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "missing authority " + role));
        return "[" + artifact.relativePath() + "](" + artifact.relativePath()
            + ") · `" + artifact.semanticHash() + '`';
    }

    private static String escape(String value) {
        return value.replace("|", "\\|").replace("\n", " ");
    }

    private static void requireSha256(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
    }

    public record ArtifactBinding(
        String role,
        String relativePath,
        String semanticHash,
        String fileSha256
    ) {
        public ArtifactBinding {
            if (role == null || !role.matches("[a-z][A-Za-z0-9]+")) {
                throw new IllegalArgumentException("invalid artifact role");
            }
            if (relativePath == null || relativePath.isBlank()
                    || relativePath.startsWith("/")
                    || relativePath.contains("..")) {
                throw new IllegalArgumentException("invalid relative artifact path");
            }
            requireSha256(semanticHash, "semanticHash");
            requireSha256(fileSha256, "fileSha256");
        }

        ObjectNode toJson() {
            ObjectNode object = MAPPER.createObjectNode();
            object.put("role", role);
            object.put("relativePath", relativePath);
            object.put("semanticHash", semanticHash);
            object.put("fileSha256", fileSha256);
            return object;
        }
    }

    private record PairedSummary(
        int caseCount,
        int baselineReachedCount,
        int candidateReachedCount,
        int bothReachedCount,
        int baselinePathLengthTotalForReachedCases,
        int candidatePathLengthTotalForReachedCases,
        int pairedPathLengthDeltaForBothReached,
        long baselineExploredStatesTotal,
        long candidateExploredStatesTotal,
        long exploredStatesDelta,
        int materialGainCount,
        int correctnessRegressionCount,
        int unsupportedCandidateCaseCount
    ) {
        static PairedSummary from(List<UtilityCase> cases) {
            int baselineReached = (int) cases.stream()
                .filter(UtilityCase::baselineReached).count();
            int candidateReached = (int) cases.stream()
                .filter(UtilityCase::candidateReached).count();
            int bothReached = (int) cases.stream()
                .filter(item -> item.baselineReached() && item.candidateReached())
                .count();
            int baselinePath = cases.stream()
                .filter(UtilityCase::baselineReached)
                .mapToInt(UtilityCase::baselinePathLength).sum();
            int candidatePath = cases.stream()
                .filter(UtilityCase::candidateReached)
                .mapToInt(UtilityCase::candidatePathLength).sum();
            int pairedDelta = cases.stream()
                .filter(item -> item.baselineReached() && item.candidateReached())
                .mapToInt(item -> item.candidatePathLength()
                    - item.baselinePathLength()).sum();
            long baselineStates = cases.stream()
                .mapToLong(UtilityCase::baselineExploredStates).sum();
            long candidateStates = cases.stream()
                .mapToLong(UtilityCase::candidateExploredStates).sum();
            int gains = (int) cases.stream()
                .filter(UtilityCase::materialGain).count();
            int regressions = (int) cases.stream()
                .filter(UtilityCase::regression).count();
            return new PairedSummary(
                cases.size(), baselineReached, candidateReached, bothReached,
                baselinePath, candidatePath, pairedDelta,
                baselineStates, candidateStates,
                Math.subtractExact(candidateStates, baselineStates),
                gains, regressions, cases.size() - candidateReached);
        }

        ObjectNode toJson() {
            ObjectNode object = MAPPER.createObjectNode();
            object.put("caseCount", caseCount);
            object.put("baselineReachedCount", baselineReachedCount);
            object.put("candidateReachedCount", candidateReachedCount);
            object.put("bothReachedCount", bothReachedCount);
            object.put("baselinePathLengthTotalForReachedCases",
                baselinePathLengthTotalForReachedCases);
            object.put("candidatePathLengthTotalForReachedCases",
                candidatePathLengthTotalForReachedCases);
            object.put("pairedPathLengthDeltaForBothReached",
                pairedPathLengthDeltaForBothReached);
            object.put("baselineExploredStatesTotal",
                baselineExploredStatesTotal);
            object.put("candidateExploredStatesTotal",
                candidateExploredStatesTotal);
            object.put("exploredStatesDelta", exploredStatesDelta);
            object.put("materialGainCount", materialGainCount);
            object.put("correctnessRegressionCount",
                correctnessRegressionCount);
            object.put("unsupportedCandidateCaseCount",
                unsupportedCandidateCaseCount);
            return object;
        }
    }
}
