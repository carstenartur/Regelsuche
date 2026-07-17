package de.regelsuche.benchmarks;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.solver.ir.SolverIr;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Canonical, domain-neutral and track-scoped comparative benchmark contracts.
 *
 * <p>The report deliberately has no universal score. Capability outcomes,
 * mathematical status, resources, proof evidence and non-canonical runtime
 * telemetry remain separate.</p>
 */
public final class ComparativeBenchmark {
    public static final String SCHEMA = "regelsuche.comparative-benchmark/v2";
    public static final String SCORE_POLICY =
        "NO_UNIVERSAL_SCORE_TRACK_SCOPED_CLAIMS_ONLY";

    private ComparativeBenchmark() {
    }

    public enum Track {
        TARGET_DIRECTED_SEARCH,
        HIDDEN_RULE_REDISCOVERY,
        OPEN_TARGET_DISCOVERY,
        CROSS_FAMILY_TRANSFER,
        EQUALITY_VALIDATION,
        AUTONOMOUS_CAMPAIGN,
        DISCOVERY_COMPONENT_ABLATION,
        CONTROLLER_ABLATION
    }

    public enum SystemKind {
        REGELSUCHE,
        EXTERNAL_BASELINE,
        ABLATION,
        REFERENCE
    }

    public enum Role {
        SEARCH,
        DISCOVERY,
        EQUALITY_REWRITE,
        VALIDATION,
        COUNTEREXAMPLE,
        PROOF,
        CONTROLLER
    }

    public enum MaterialVisibility {
        VISIBLE,
        OPAQUE_HASH,
        ABSENT
    }

    public enum CapabilityOutcome {
        TARGET_REACHED,
        TARGET_NOT_REACHED,
        REDISCOVERED,
        NOT_REDISCOVERED,
        CANDIDATE_FORMED,
        NO_CANDIDATE,
        TRANSFER_SUCCEEDED,
        TRANSFER_FAILED,
        VALIDATED,
        REFUTED,
        CAMPAIGN_COMPLETED,
        CAMPAIGN_INCOMPLETE,
        ABLATION_IMPROVED,
        ABLATION_NO_IMPROVEMENT,
        NOT_APPLICABLE
    }

    public enum MathematicalStatus {
        CONFIRMED,
        REFUTED,
        UNKNOWN,
        NOT_APPLICABLE
    }

    public enum Disposition {
        EXECUTED,
        FILTERED_UNSUPPORTED,
        TIMED_OUT,
        FAILED
    }

    public enum ClaimStatus {
        SUPPORTED,
        NEGATIVE,
        INSUFFICIENT_EVIDENCE
    }

    /** Material visible to a system, represented only by a hash, or absent. */
    public record Subject(
        String semanticType,
        MaterialVisibility visibility,
        String material,
        String materialHash,
        String contentHash
    ) {
        public Subject {
            semanticType = normalizeRequired(semanticType, "subject semanticType");
            Objects.requireNonNull(visibility, "visibility");
            material = normalize(material);
            materialHash = normalize(materialHash);
            switch (visibility) {
                case VISIBLE -> {
                    requireText(material, "visible subject material");
                    requireSha(materialHash, "visible subject materialHash");
                    if (!SolverIr.sha256(material).equals(materialHash)) {
                        throw new IllegalArgumentException(
                            "visible subject materialHash does not match material");
                    }
                }
                case OPAQUE_HASH -> {
                    if (!material.isEmpty()) {
                        throw new IllegalArgumentException(
                            "opaque subject must not disclose material");
                    }
                    requireSha(materialHash, "opaque subject materialHash");
                }
                case ABSENT -> {
                    if (!material.isEmpty() || !materialHash.isEmpty()) {
                        throw new IllegalArgumentException(
                            "absent subject cannot contain material or hash");
                    }
                }
            }
            requireSha(contentHash, "subject contentHash");
            if (!hash(semanticType, visibility, material, materialHash)
                    .equals(contentHash)) {
                throw new IllegalArgumentException("subject hash mismatch");
            }
        }

        public static Subject visible(String semanticType, String material) {
            String normalized = normalizeRequired(material, "subject material");
            return create(
                semanticType, MaterialVisibility.VISIBLE, normalized,
                SolverIr.sha256(normalized));
        }

        public static Subject opaque(String semanticType, String materialHash) {
            return create(
                semanticType, MaterialVisibility.OPAQUE_HASH, "",
                materialHash);
        }

        public static Subject absent(String semanticType) {
            return create(
                semanticType, MaterialVisibility.ABSENT, "", "");
        }

        private static Subject create(
            String semanticType,
            MaterialVisibility visibility,
            String material,
            String materialHash
        ) {
            String type = normalizeRequired(semanticType, "subject semanticType");
            String normalizedMaterial = normalize(material);
            String normalizedHash = normalize(materialHash);
            return new Subject(
                type, visibility, normalizedMaterial, normalizedHash,
                hash(type, visibility, normalizedMaterial, normalizedHash));
        }

        public String requireVisibleMaterial() {
            if (visibility != MaterialVisibility.VISIBLE) {
                throw new IllegalStateException(
                    "subject material is not visible: " + semanticType);
            }
            return material;
        }

        JsonWriter write(JsonWriter json) {
            return json.property("semanticType", semanticType)
                .property("visibility", visibility.name())
                .property("material", material)
                .property("materialHash", materialHash)
                .property("contentHash", contentHash);
        }

        private static String hash(
            String semanticType,
            MaterialVisibility visibility,
            String material,
            String materialHash
        ) {
            return SolverIr.sha256(
                "subject/v2\ntype=" + semanticType
                    + "\nvisibility=" + visibility.name()
                    + "\nmaterial=" + material
                    + "\nmaterialHash=" + materialHash);
        }
    }

    public record InformationParityManifest(
        String id,
        Track track,
        boolean targetVisible,
        boolean hiddenReferenceVisible,
        boolean familyLabelVisible,
        boolean testLabelVisible,
        boolean qualificationLabelVisible,
        boolean reviewLabelVisible,
        String inputCorpusHash,
        String inventoryHash,
        String budgetHash,
        String researchBriefHash,
        String qualificationSplitHash,
        List<String> mandatoryEvaluations,
        String contentHash
    ) {
        public InformationParityManifest {
            requireText(id, "parity manifest id");
            Objects.requireNonNull(track, "track");
            requireSha(inputCorpusHash, "inputCorpusHash");
            requireSha(inventoryHash, "inventoryHash");
            requireSha(budgetHash, "budgetHash");
            requireSha(researchBriefHash, "researchBriefHash");
            requireSha(qualificationSplitHash, "qualificationSplitHash");
            mandatoryEvaluations = sortedStrings(mandatoryEvaluations);
            requireSha(contentHash, "contentHash");
            if (!hash(id, track, targetVisible, hiddenReferenceVisible,
                    familyLabelVisible, testLabelVisible, qualificationLabelVisible,
                    reviewLabelVisible, inputCorpusHash, inventoryHash, budgetHash,
                    researchBriefHash, qualificationSplitHash, mandatoryEvaluations)
                    .equals(contentHash)) {
                throw new IllegalArgumentException("parity manifest hash mismatch");
            }
        }

        public static InformationParityManifest create(
            String id,
            Track track,
            boolean targetVisible,
            boolean hiddenReferenceVisible,
            boolean familyLabelVisible,
            boolean testLabelVisible,
            boolean qualificationLabelVisible,
            boolean reviewLabelVisible,
            String inputCorpusHash,
            String inventoryHash,
            String budgetHash,
            String researchBriefHash,
            String qualificationSplitHash,
            List<String> mandatoryEvaluations
        ) {
            List<String> evaluations = sortedStrings(mandatoryEvaluations);
            return new InformationParityManifest(
                id, track, targetVisible, hiddenReferenceVisible,
                familyLabelVisible, testLabelVisible, qualificationLabelVisible,
                reviewLabelVisible, inputCorpusHash, inventoryHash, budgetHash,
                researchBriefHash, qualificationSplitHash, evaluations,
                hash(id, track, targetVisible, hiddenReferenceVisible,
                    familyLabelVisible, testLabelVisible, qualificationLabelVisible,
                    reviewLabelVisible, inputCorpusHash, inventoryHash, budgetHash,
                    researchBriefHash, qualificationSplitHash, evaluations));
        }

        public String toCanonicalJson() {
            return write(new JsonWriter().beginObject()).endObject().toString();
        }

        JsonWriter write(JsonWriter json) {
            return json.property("id", id)
                .property("track", track.name())
                .property("targetVisible", targetVisible)
                .property("hiddenReferenceVisible", hiddenReferenceVisible)
                .property("familyLabelVisible", familyLabelVisible)
                .property("testLabelVisible", testLabelVisible)
                .property("qualificationLabelVisible", qualificationLabelVisible)
                .property("reviewLabelVisible", reviewLabelVisible)
                .property("inputCorpusHash", inputCorpusHash)
                .property("inventoryHash", inventoryHash)
                .property("budgetHash", budgetHash)
                .property("researchBriefHash", researchBriefHash)
                .property("qualificationSplitHash", qualificationSplitHash)
                .stringArray("mandatoryEvaluations", mandatoryEvaluations)
                .property("contentHash", contentHash);
        }

        private static String hash(
            String id,
            Track track,
            boolean targetVisible,
            boolean hiddenReferenceVisible,
            boolean familyLabelVisible,
            boolean testLabelVisible,
            boolean qualificationLabelVisible,
            boolean reviewLabelVisible,
            String inputCorpusHash,
            String inventoryHash,
            String budgetHash,
            String researchBriefHash,
            String qualificationSplitHash,
            List<String> mandatoryEvaluations
        ) {
            return SolverIr.sha256(
                "parity/v2\nid=" + id
                    + "\ntrack=" + track.name()
                    + "\ntargetVisible=" + targetVisible
                    + "\nhiddenReferenceVisible=" + hiddenReferenceVisible
                    + "\nfamilyLabelVisible=" + familyLabelVisible
                    + "\ntestLabelVisible=" + testLabelVisible
                    + "\nqualificationLabelVisible=" + qualificationLabelVisible
                    + "\nreviewLabelVisible=" + reviewLabelVisible
                    + "\ninputCorpus=" + inputCorpusHash
                    + "\ninventory=" + inventoryHash
                    + "\nbudget=" + budgetHash
                    + "\nresearchBrief=" + researchBriefHash
                    + "\nqualificationSplit=" + qualificationSplitHash
                    + "\nmandatoryEvaluations=" + mandatoryEvaluations);
        }
    }

    public record Configuration(
        String id,
        Track track,
        SystemKind kind,
        List<Role> roles,
        String parityManifestHash,
        String backendId,
        String backendVersion,
        String policyHash,
        String modelHash,
        String environmentHash,
        boolean deterministic,
        List<String> limitations,
        String contentHash
    ) {
        public Configuration {
            requireText(id, "configuration id");
            Objects.requireNonNull(track, "track");
            Objects.requireNonNull(kind, "kind");
            roles = sortedEnums(roles);
            if (roles.isEmpty()) {
                throw new IllegalArgumentException(
                    "configuration roles must not be empty");
            }
            requireSha(parityManifestHash, "parityManifestHash");
            requireText(backendId, "backendId");
            requireText(backendVersion, "backendVersion");
            requireSha(policyHash, "policyHash");
            requireSha(modelHash, "modelHash");
            requireSha(environmentHash, "environmentHash");
            limitations = sortedStrings(limitations);
            requireSha(contentHash, "contentHash");
            if (!hash(id, track, kind, roles, parityManifestHash, backendId,
                    backendVersion, policyHash, modelHash, environmentHash,
                    deterministic, limitations).equals(contentHash)) {
                throw new IllegalArgumentException("configuration hash mismatch");
            }
        }

        public static Configuration create(
            String id,
            Track track,
            SystemKind kind,
            List<Role> roles,
            String parityManifestHash,
            String backendId,
            String backendVersion,
            String policyHash,
            String modelHash,
            String environmentHash,
            boolean deterministic,
            List<String> limitations
        ) {
            List<Role> orderedRoles = sortedEnums(roles);
            List<String> orderedLimitations = sortedStrings(limitations);
            return new Configuration(
                id, track, kind, orderedRoles, parityManifestHash, backendId,
                backendVersion, policyHash, modelHash, environmentHash,
                deterministic, orderedLimitations,
                hash(id, track, kind, orderedRoles, parityManifestHash, backendId,
                    backendVersion, policyHash, modelHash, environmentHash,
                    deterministic, orderedLimitations));
        }

        public String toCanonicalJson() {
            return write(new JsonWriter().beginObject()).endObject().toString();
        }

        JsonWriter write(JsonWriter json) {
            return json.property("id", id)
                .property("track", track.name())
                .property("kind", kind.name())
                .stringArray("roles", roles.stream().map(Enum::name).toList())
                .property("parityManifestHash", parityManifestHash)
                .property("backendId", backendId)
                .property("backendVersion", backendVersion)
                .property("policyHash", policyHash)
                .property("modelHash", modelHash)
                .property("environmentHash", environmentHash)
                .property("deterministic", deterministic)
                .stringArray("limitations", limitations)
                .property("contentHash", contentHash);
        }

        private static String hash(
            String id,
            Track track,
            SystemKind kind,
            List<Role> roles,
            String parityManifestHash,
            String backendId,
            String backendVersion,
            String policyHash,
            String modelHash,
            String environmentHash,
            boolean deterministic,
            List<String> limitations
        ) {
            return SolverIr.sha256(
                "configuration/v2\nid=" + id
                    + "\ntrack=" + track.name()
                    + "\nkind=" + kind.name()
                    + "\nroles=" + roles
                    + "\nparity=" + parityManifestHash
                    + "\nbackend=" + backendId + '@' + backendVersion
                    + "\npolicy=" + policyHash
                    + "\nmodel=" + modelHash
                    + "\nenvironment=" + environmentHash
                    + "\ndeterministic=" + deterministic
                    + "\nlimitations=" + limitations);
        }
    }

    public record Case(
        String id,
        Track track,
        String reportingFamily,
        Subject input,
        Subject target,
        List<String> assumptions,
        CapabilityOutcome expectedCapabilityOutcome,
        MathematicalStatus expectedMathematicalStatus,
        String budgetHash,
        String sourceEvidenceHash,
        Map<String, String> attributes,
        String contentHash
    ) {
        public Case {
            requireText(id, "case id");
            Objects.requireNonNull(track, "track");
            reportingFamily = normalize(reportingFamily);
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(target, "target");
            assumptions = sortedStrings(assumptions);
            Objects.requireNonNull(
                expectedCapabilityOutcome, "expectedCapabilityOutcome");
            Objects.requireNonNull(
                expectedMathematicalStatus, "expectedMathematicalStatus");
            requireSha(budgetHash, "budgetHash");
            requireSha(sourceEvidenceHash, "sourceEvidenceHash");
            attributes = orderedStringMap(attributes);
            requireSha(contentHash, "contentHash");
            if (!hash(id, track, reportingFamily, input, target, assumptions,
                    expectedCapabilityOutcome, expectedMathematicalStatus,
                    budgetHash, sourceEvidenceHash, attributes).equals(contentHash)) {
                throw new IllegalArgumentException("case hash mismatch");
            }
        }

        public static Case create(
            String id,
            Track track,
            String reportingFamily,
            Subject input,
            Subject target,
            List<String> assumptions,
            CapabilityOutcome expectedCapabilityOutcome,
            MathematicalStatus expectedMathematicalStatus,
            String budgetHash,
            String sourceEvidenceHash,
            Map<String, String> attributes
        ) {
            String family = normalize(reportingFamily);
            List<String> orderedAssumptions = sortedStrings(assumptions);
            Map<String, String> orderedAttributes = orderedStringMap(attributes);
            return new Case(
                id, track, family, input, target, orderedAssumptions,
                expectedCapabilityOutcome, expectedMathematicalStatus,
                budgetHash, sourceEvidenceHash, orderedAttributes,
                hash(id, track, family, input, target, orderedAssumptions,
                    expectedCapabilityOutcome, expectedMathematicalStatus,
                    budgetHash, sourceEvidenceHash, orderedAttributes));
        }

        public String toCanonicalJson() {
            return write(new JsonWriter().beginObject()).endObject().toString();
        }

        JsonWriter write(JsonWriter json) {
            return json.property("id", id)
                .property("track", track.name())
                .property("reportingFamily", reportingFamily)
                .object("input", input::write)
                .object("target", target::write)
                .stringArray("assumptions", assumptions)
                .property(
                    "expectedCapabilityOutcome",
                    expectedCapabilityOutcome.name())
                .property(
                    "expectedMathematicalStatus",
                    expectedMathematicalStatus.name())
                .property("budgetHash", budgetHash)
                .property("sourceEvidenceHash", sourceEvidenceHash)
                .object("attributes", object ->
                    attributes.forEach(object::property))
                .property("contentHash", contentHash);
        }

        private static String hash(
            String id,
            Track track,
            String reportingFamily,
            Subject input,
            Subject target,
            List<String> assumptions,
            CapabilityOutcome expectedCapabilityOutcome,
            MathematicalStatus expectedMathematicalStatus,
            String budgetHash,
            String sourceEvidenceHash,
            Map<String, String> attributes
        ) {
            return SolverIr.sha256(
                "case/v2\nid=" + id
                    + "\ntrack=" + track.name()
                    + "\nreportingFamily=" + reportingFamily
                    + "\ninput=" + input.contentHash()
                    + "\ntarget=" + target.contentHash()
                    + "\nassumptions=" + assumptions
                    + "\nexpectedCapability=" + expectedCapabilityOutcome.name()
                    + "\nexpectedMathematical="
                        + expectedMathematicalStatus.name()
                    + "\nbudget=" + budgetHash
                    + "\nsourceEvidence=" + sourceEvidenceHash
                    + "\nattributes=" + attributes);
        }
    }

    public record ResourceMetrics(
        int configuredWork,
        int executedWork,
        int skippedWork,
        int remainingWork,
        int exploredStates,
        int generatedSuccessors,
        int pathLength,
        long consumedCostUnits,
        int mandatoryEvaluations,
        int completedMandatoryEvaluations
    ) {
        public ResourceMetrics {
            if (configuredWork < 0 || executedWork < 0 || skippedWork < 0
                    || remainingWork < 0 || exploredStates < 0
                    || generatedSuccessors < 0 || pathLength < -1
                    || consumedCostUnits < 0 || mandatoryEvaluations < 0
                    || completedMandatoryEvaluations < 0) {
                throw new IllegalArgumentException(
                    "resource metrics must not be negative");
            }
            if (configuredWork
                    != executedWork + skippedWork + remainingWork) {
                throw new IllegalArgumentException(
                    "configured work must balance");
            }
            if (completedMandatoryEvaluations > mandatoryEvaluations) {
                throw new IllegalArgumentException(
                    "completed mandatory evaluations exceed configured");
            }
        }

        JsonWriter write(JsonWriter json) {
            return json.property("configuredWork", configuredWork)
                .property("executedWork", executedWork)
                .property("skippedWork", skippedWork)
                .property("remainingWork", remainingWork)
                .property("exploredStates", exploredStates)
                .property("generatedSuccessors", generatedSuccessors)
                .property("pathLength", pathLength)
                .property("consumedCostUnits", consumedCostUnits)
                .property("mandatoryEvaluations", mandatoryEvaluations)
                .property(
                    "completedMandatoryEvaluations",
                    completedMandatoryEvaluations);
        }

        String canonicalMaterial() {
            return configuredWork + "|" + executedWork + "|" + skippedWork
                + "|" + remainingWork + "|" + exploredStates + "|"
                + generatedSuccessors + "|" + pathLength + "|"
                + consumedCostUnits + "|" + mandatoryEvaluations + "|"
                + completedMandatoryEvaluations;
        }
    }

    public record Evidence(
        MathematicalStatus mathematicalStatus,
        String validationStatus,
        String counterexampleStatus,
        String proofStatus,
        String certificateHash,
        List<String> unsupportedReasons,
        Map<String, String> attributes
    ) {
        public Evidence {
            Objects.requireNonNull(
                mathematicalStatus, "mathematicalStatus");
            validationStatus = normalizeRequired(
                validationStatus, "validationStatus");
            counterexampleStatus = normalizeRequired(
                counterexampleStatus, "counterexampleStatus");
            proofStatus = normalizeRequired(proofStatus, "proofStatus");
            certificateHash = normalize(certificateHash);
            if (!certificateHash.isEmpty()) {
                requireSha(certificateHash, "certificateHash");
            }
            unsupportedReasons = sortedStrings(unsupportedReasons);
            attributes = orderedStringMap(attributes);
        }

        JsonWriter write(JsonWriter json) {
            return json.property(
                    "mathematicalStatus", mathematicalStatus.name())
                .property("validationStatus", validationStatus)
                .property("counterexampleStatus", counterexampleStatus)
                .property("proofStatus", proofStatus)
                .property("certificateHash", certificateHash)
                .stringArray("unsupportedReasons", unsupportedReasons)
                .object("attributes", object ->
                    attributes.forEach(object::property));
        }

        String canonicalMaterial() {
            return mathematicalStatus.name() + '|' + validationStatus + '|'
                + counterexampleStatus + '|' + proofStatus + '|'
                + certificateHash + '|' + unsupportedReasons + '|'
                + attributes;
        }
    }

    public record Result(
        String configurationHash,
        String caseHash,
        Track track,
        Disposition disposition,
        CapabilityOutcome capabilityOutcome,
        boolean expectationApplicable,
        boolean expectationMatched,
        ResourceMetrics resources,
        Evidence evidence,
        Map<String, Long> trackMetrics,
        List<String> lineageHashes,
        String contentHash
    ) {
        public Result {
            requireSha(configurationHash, "configurationHash");
            requireSha(caseHash, "caseHash");
            Objects.requireNonNull(track, "track");
            Objects.requireNonNull(disposition, "disposition");
            Objects.requireNonNull(capabilityOutcome, "capabilityOutcome");
            Objects.requireNonNull(resources, "resources");
            Objects.requireNonNull(evidence, "evidence");
            if (!expectationApplicable && expectationMatched) {
                throw new IllegalArgumentException(
                    "inapplicable expectation cannot be matched");
            }
            trackMetrics = orderedLongMap(trackMetrics);
            lineageHashes = sortedStrings(lineageHashes);
            lineageHashes.forEach(value ->
                requireSha(value, "lineageHash"));
            requireSha(contentHash, "contentHash");
            if (!hash(configurationHash, caseHash, track, disposition,
                    capabilityOutcome, expectationApplicable,
                    expectationMatched, resources, evidence,
                    trackMetrics, lineageHashes).equals(contentHash)) {
                throw new IllegalArgumentException("result hash mismatch");
            }
        }

        public static Result create(
            Configuration configuration,
            Case benchmarkCase,
            Disposition disposition,
            CapabilityOutcome capabilityOutcome,
            ResourceMetrics resources,
            Evidence evidence,
            Map<String, Long> trackMetrics,
            List<String> lineageHashes
        ) {
            Objects.requireNonNull(configuration, "configuration");
            Objects.requireNonNull(benchmarkCase, "benchmarkCase");
            if (configuration.track() != benchmarkCase.track()) {
                throw new IllegalArgumentException(
                    "configuration and case track differ");
            }
            boolean capabilityExpected =
                benchmarkCase.expectedCapabilityOutcome()
                    != CapabilityOutcome.NOT_APPLICABLE;
            boolean mathematicalExpected =
                benchmarkCase.expectedMathematicalStatus()
                    != MathematicalStatus.NOT_APPLICABLE;
            boolean expectationApplicable =
                capabilityExpected || mathematicalExpected;
            boolean expectationMatched = expectationApplicable
                && (!capabilityExpected
                    || benchmarkCase.expectedCapabilityOutcome()
                        == capabilityOutcome)
                && (!mathematicalExpected
                    || benchmarkCase.expectedMathematicalStatus()
                        == evidence.mathematicalStatus());
            Map<String, Long> metrics = orderedLongMap(trackMetrics);
            List<String> lineage = sortedStrings(lineageHashes);
            return new Result(
                configuration.contentHash(), benchmarkCase.contentHash(),
                benchmarkCase.track(), disposition, capabilityOutcome,
                expectationApplicable, expectationMatched, resources,
                evidence, metrics, lineage,
                hash(configuration.contentHash(), benchmarkCase.contentHash(),
                    benchmarkCase.track(), disposition, capabilityOutcome,
                    expectationApplicable, expectationMatched, resources,
                    evidence, metrics, lineage));
        }

        public String toCanonicalJson() {
            return write(new JsonWriter().beginObject()).endObject().toString();
        }

        JsonWriter write(JsonWriter json) {
            return json.property("configurationHash", configurationHash)
                .property("caseHash", caseHash)
                .property("track", track.name())
                .property("disposition", disposition.name())
                .property("capabilityOutcome", capabilityOutcome.name())
                .property("expectationApplicable", expectationApplicable)
                .property("expectationMatched", expectationMatched)
                .object("resources", resources::write)
                .object("evidence", evidence::write)
                .object("trackMetrics", object ->
                    trackMetrics.forEach(object::property))
                .stringArray("lineageHashes", lineageHashes)
                .property("contentHash", contentHash);
        }

        private static String hash(
            String configurationHash,
            String caseHash,
            Track track,
            Disposition disposition,
            CapabilityOutcome capabilityOutcome,
            boolean expectationApplicable,
            boolean expectationMatched,
            ResourceMetrics resources,
            Evidence evidence,
            Map<String, Long> trackMetrics,
            List<String> lineageHashes
        ) {
            return SolverIr.sha256(
                "result/v2\nconfiguration=" + configurationHash
                    + "\ncase=" + caseHash
                    + "\ntrack=" + track.name()
                    + "\ndisposition=" + disposition.name()
                    + "\ncapability=" + capabilityOutcome.name()
                    + "\nexpectationApplicable=" + expectationApplicable
                    + "\nexpectationMatched=" + expectationMatched
                    + "\nresources=" + resources.canonicalMaterial()
                    + "\nevidence=" + evidence.canonicalMaterial()
                    + "\ntrackMetrics=" + trackMetrics
                    + "\nlineage=" + lineageHashes);
        }
    }

    public record CapabilityClaim(
        String id,
        Track track,
        ClaimStatus status,
        String statement,
        List<String> evidenceResultHashes,
        List<String> limitations,
        String contentHash
    ) {
        public CapabilityClaim {
            requireText(id, "claim id");
            Objects.requireNonNull(track, "track");
            Objects.requireNonNull(status, "status");
            statement = normalizeRequired(statement, "statement");
            evidenceResultHashes = sortedStrings(evidenceResultHashes);
            evidenceResultHashes.forEach(value ->
                requireSha(value, "evidenceResultHash"));
            limitations = sortedStrings(limitations);
            if (evidenceResultHashes.isEmpty()) {
                throw new IllegalArgumentException(
                    "claim requires result evidence");
            }
            requireSha(contentHash, "contentHash");
            if (!hash(id, track, status, statement,
                    evidenceResultHashes, limitations).equals(contentHash)) {
                throw new IllegalArgumentException("claim hash mismatch");
            }
        }

        public static CapabilityClaim create(
            String id,
            Track track,
            ClaimStatus status,
            String statement,
            List<String> evidenceResultHashes,
            List<String> limitations
        ) {
            List<String> evidence = sortedStrings(evidenceResultHashes);
            List<String> orderedLimitations = sortedStrings(limitations);
            return new CapabilityClaim(
                id, track, status, statement, evidence,
                orderedLimitations,
                hash(id, track, status, statement,
                    evidence, orderedLimitations));
        }

        JsonWriter write(JsonWriter json) {
            return json.property("id", id)
                .property("track", track.name())
                .property("status", status.name())
                .property("statement", statement)
                .stringArray(
                    "evidenceResultHashes", evidenceResultHashes)
                .stringArray("limitations", limitations)
                .property("contentHash", contentHash);
        }

        private static String hash(
            String id,
            Track track,
            ClaimStatus status,
            String statement,
            List<String> evidenceResultHashes,
            List<String> limitations
        ) {
            return SolverIr.sha256(
                "claim/v2\nid=" + id
                    + "\ntrack=" + track.name()
                    + "\nstatus=" + status.name()
                    + "\nstatement=" + statement
                    + "\nevidence=" + evidenceResultHashes
                    + "\nlimitations=" + limitations);
        }
    }

    public record CoverageGap(
        String id,
        Track track,
        String reason,
        List<String> requiredEvidence,
        String contentHash
    ) {
        public CoverageGap {
            requireText(id, "coverage gap id");
            Objects.requireNonNull(track, "track");
            reason = normalizeRequired(reason, "reason");
            requiredEvidence = sortedStrings(requiredEvidence);
            if (requiredEvidence.isEmpty()) {
                throw new IllegalArgumentException(
                    "coverage gap needs required evidence");
            }
            requireSha(contentHash, "contentHash");
            if (!hash(id, track, reason, requiredEvidence)
                    .equals(contentHash)) {
                throw new IllegalArgumentException(
                    "coverage gap hash mismatch");
            }
        }

        public static CoverageGap create(
            String id,
            Track track,
            String reason,
            List<String> requiredEvidence
        ) {
            List<String> evidence = sortedStrings(requiredEvidence);
            return new CoverageGap(
                id, track, reason, evidence,
                hash(id, track, reason, evidence));
        }

        JsonWriter write(JsonWriter json) {
            return json.property("id", id)
                .property("track", track.name())
                .property("reason", reason)
                .stringArray("requiredEvidence", requiredEvidence)
                .property("contentHash", contentHash);
        }

        private static String hash(
            String id,
            Track track,
            String reason,
            List<String> requiredEvidence
        ) {
            return SolverIr.sha256(
                "coverage-gap/v2\nid=" + id
                    + "\ntrack=" + track.name()
                    + "\nreason=" + reason
                    + "\nrequiredEvidence=" + requiredEvidence);
        }
    }

    public record Report(
        String schema,
        String suiteId,
        List<InformationParityManifest> parityManifests,
        List<Configuration> configurations,
        List<Case> cases,
        List<Result> results,
        List<CapabilityClaim> claims,
        List<CoverageGap> coverageGaps,
        String scorePolicy,
        String contentHash
    ) {
        public Report {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported comparative benchmark schema");
            }
            requireText(suiteId, "suiteId");
            parityManifests = sorted(
                parityManifests,
                Comparator.comparing(
                    InformationParityManifest::id));
            configurations = sorted(
                configurations,
                Comparator.comparing(Configuration::id));
            cases = sorted(
                cases, Comparator.comparing(Case::id));
            results = sorted(
                results,
                Comparator.comparing(Result::configurationHash)
                    .thenComparing(Result::caseHash));
            claims = sorted(
                claims,
                Comparator.comparing(CapabilityClaim::id));
            coverageGaps = sorted(
                coverageGaps,
                Comparator.comparing(CoverageGap::id));
            if (!SCORE_POLICY.equals(scorePolicy)) {
                throw new IllegalArgumentException(
                    "universal scores are not permitted");
            }
            validateReferences(
                parityManifests, configurations, cases, results,
                claims, coverageGaps);
            requireSha(contentHash, "contentHash");
            if (!hash(
                    suiteId, parityManifests, configurations, cases,
                    results, claims, coverageGaps).equals(contentHash)) {
                throw new IllegalArgumentException("report hash mismatch");
            }
        }

        public static Report create(
            String suiteId,
            List<InformationParityManifest> parityManifests,
            List<Configuration> configurations,
            List<Case> cases,
            List<Result> results,
            List<CapabilityClaim> claims,
            List<CoverageGap> coverageGaps
        ) {
            List<InformationParityManifest> manifests = sorted(
                parityManifests,
                Comparator.comparing(
                    InformationParityManifest::id));
            List<Configuration> configs = sorted(
                configurations,
                Comparator.comparing(Configuration::id));
            List<Case> orderedCases = sorted(
                cases, Comparator.comparing(Case::id));
            List<Result> orderedResults = sorted(
                results,
                Comparator.comparing(Result::configurationHash)
                    .thenComparing(Result::caseHash));
            List<CapabilityClaim> orderedClaims = sorted(
                claims, Comparator.comparing(CapabilityClaim::id));
            List<CoverageGap> gaps = sorted(
                coverageGaps, Comparator.comparing(CoverageGap::id));
            validateReferences(
                manifests, configs, orderedCases, orderedResults,
                orderedClaims, gaps);
            return new Report(
                SCHEMA, suiteId, manifests, configs, orderedCases,
                orderedResults, orderedClaims, gaps, SCORE_POLICY,
                hash(
                    suiteId, manifests, configs, orderedCases,
                    orderedResults, orderedClaims, gaps));
        }

        public String toCanonicalJson() {
            JsonWriter json = new JsonWriter().beginObject()
                .property("schema", schema)
                .property("suiteId", suiteId)
                .array("parityManifests", array ->
                    parityManifests.forEach(item ->
                        array.objectValue(item::write)))
                .array("configurations", array ->
                    configurations.forEach(item ->
                        array.objectValue(item::write)))
                .array("cases", array ->
                    cases.forEach(item ->
                        array.objectValue(item::write)))
                .array("results", array ->
                    results.forEach(item ->
                        array.objectValue(item::write)))
                .array("claims", array ->
                    claims.forEach(item ->
                        array.objectValue(item::write)))
                .array("coverageGaps", array ->
                    coverageGaps.forEach(item ->
                        array.objectValue(item::write)))
                .property("scorePolicy", scorePolicy)
                .property("contentHash", contentHash);
            return json.endObject().toString();
        }

        private static void validateReferences(
            List<InformationParityManifest> manifests,
            List<Configuration> configurations,
            List<Case> cases,
            List<Result> results,
            List<CapabilityClaim> claims,
            List<CoverageGap> gaps
        ) {
            unique(
                manifests.stream()
                    .map(InformationParityManifest::id).toList(),
                "parity manifest id");
            unique(
                configurations.stream()
                    .map(Configuration::id).toList(),
                "configuration id");
            unique(
                cases.stream().map(Case::id).toList(),
                "case id");
            unique(
                claims.stream().map(CapabilityClaim::id).toList(),
                "claim id");
            unique(
                gaps.stream().map(CoverageGap::id).toList(),
                "coverage gap id");

            Map<String, InformationParityManifest> manifestByHash =
                new HashMap<>();
            manifests.forEach(item ->
                manifestByHash.put(item.contentHash(), item));
            Map<String, Configuration> configurationByHash =
                new HashMap<>();
            configurations.forEach(item ->
                configurationByHash.put(item.contentHash(), item));
            Map<String, Case> caseByHash = new HashMap<>();
            cases.forEach(item ->
                caseByHash.put(item.contentHash(), item));
            Map<String, Result> resultByHash = new HashMap<>();

            for (Configuration configuration : configurations) {
                InformationParityManifest manifest = manifestByHash.get(
                    configuration.parityManifestHash());
                if (manifest == null
                        || manifest.track() != configuration.track()) {
                    throw new IllegalArgumentException(
                        "configuration references missing or wrong-track "
                            + "parity manifest");
                }
            }

            Set<String> actualPairs = new HashSet<>();
            for (Result result : results) {
                Configuration configuration = configurationByHash.get(
                    result.configurationHash());
                Case benchmarkCase = caseByHash.get(
                    result.caseHash());
                if (configuration == null || benchmarkCase == null
                        || configuration.track() != result.track()
                        || benchmarkCase.track() != result.track()) {
                    throw new IllegalArgumentException(
                        "result references invalid configuration/case");
                }
                String pair = pair(
                    result.configurationHash(), result.caseHash());
                if (!actualPairs.add(pair)) {
                    throw new IllegalArgumentException(
                        "duplicate configuration/case result");
                }
                resultByHash.put(result.contentHash(), result);
            }

            Set<String> expectedPairs = new HashSet<>();
            for (Configuration configuration : configurations) {
                boolean hasCase = false;
                for (Case benchmarkCase : cases) {
                    if (benchmarkCase.track() == configuration.track()) {
                        hasCase = true;
                        expectedPairs.add(pair(
                            configuration.contentHash(),
                            benchmarkCase.contentHash()));
                    }
                }
                if (!hasCase) {
                    throw new IllegalArgumentException(
                        "configuration has no case in its track: "
                            + configuration.id());
                }
            }
            for (Case benchmarkCase : cases) {
                boolean hasConfiguration = configurations.stream()
                    .anyMatch(configuration ->
                        configuration.track() == benchmarkCase.track());
                if (!hasConfiguration) {
                    throw new IllegalArgumentException(
                        "case has no configuration in its track: "
                            + benchmarkCase.id());
                }
            }
            if (!expectedPairs.equals(actualPairs)) {
                Set<String> missing = new HashSet<>(expectedPairs);
                missing.removeAll(actualPairs);
                Set<String> unexpected = new HashSet<>(actualPairs);
                unexpected.removeAll(expectedPairs);
                throw new IllegalArgumentException(
                    "comparative result matrix is incomplete; missing="
                        + missing + "; unexpected=" + unexpected);
            }

            for (CapabilityClaim claim : claims) {
                if (!resultByHash.keySet().containsAll(
                        claim.evidenceResultHashes())) {
                    throw new IllegalArgumentException(
                        "claim references missing result evidence");
                }
                boolean wrongTrack = claim.evidenceResultHashes().stream()
                    .map(resultByHash::get)
                    .anyMatch(result ->
                        result.track() != claim.track());
                if (wrongTrack) {
                    throw new IllegalArgumentException(
                        "claim mixes benchmark tracks");
                }
            }

            for (Case benchmarkCase : cases) {
                List<InformationParityManifest> trackManifests =
                    configurations.stream()
                        .filter(configuration ->
                            configuration.track() == benchmarkCase.track())
                        .map(configuration ->
                            manifestByHash.get(
                                configuration.parityManifestHash()))
                        .distinct()
                        .toList();
                if (trackManifests.stream()
                        .anyMatch(
                            InformationParityManifest::targetVisible)
                        && benchmarkCase.target().visibility()
                            != MaterialVisibility.VISIBLE) {
                    throw new IllegalArgumentException(
                        "visible-target track case lacks visible target");
                }
            }
        }

        private static String pair(
            String configurationHash,
            String caseHash
        ) {
            return configurationHash + '|' + caseHash;
        }

        private static String hash(
            String suiteId,
            List<InformationParityManifest> manifests,
            List<Configuration> configurations,
            List<Case> cases,
            List<Result> results,
            List<CapabilityClaim> claims,
            List<CoverageGap> gaps
        ) {
            return SolverIr.sha256(
                SCHEMA + "\nsuite=" + suiteId
                    + "\nmanifests=" + manifests.stream()
                        .map(InformationParityManifest::contentHash)
                        .toList()
                    + "\nconfigurations=" + configurations.stream()
                        .map(Configuration::contentHash).toList()
                    + "\ncases=" + cases.stream()
                        .map(Case::contentHash).toList()
                    + "\nresults=" + results.stream()
                        .map(Result::contentHash).toList()
                    + "\nclaims=" + claims.stream()
                        .map(CapabilityClaim::contentHash).toList()
                    + "\ngaps=" + gaps.stream()
                        .map(CoverageGap::contentHash).toList()
                    + "\nscorePolicy=" + SCORE_POLICY);
        }
    }

    private static Map<String, Long> orderedLongMap(
        Map<String, Long> values
    ) {
        TreeMap<String, Long> result = new TreeMap<>();
        if (values != null) {
            values.forEach((key, value) -> {
                requireText(key, "metric name");
                if (value == null || value < 0L) {
                    throw new IllegalArgumentException(
                        "metric values must not be negative");
                }
                result.put(key, value);
            });
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private static Map<String, String> orderedStringMap(
        Map<String, String> values
    ) {
        TreeMap<String, String> result = new TreeMap<>();
        if (values != null) {
            values.forEach((key, value) -> {
                requireText(key, "attribute name");
                result.put(
                    normalizeRequired(key, "attribute name"),
                    normalizeRequired(value, "attribute value"));
            });
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private static List<String> sortedStrings(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .filter(Objects::nonNull)
            .map(ComparativeBenchmark::normalize)
            .filter(value -> !value.isEmpty())
            .distinct()
            .sorted()
            .toList();
    }

    private static <E extends Enum<E>> List<E> sortedEnums(
        List<E> values
    ) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .filter(Objects::nonNull)
            .distinct()
            .sorted(Comparator.comparing(Enum::name))
            .toList();
    }

    private static <T> List<T> sorted(
        List<T> values,
        Comparator<T> comparator
    ) {
        List<T> result = values == null
            ? new ArrayList<>()
            : new ArrayList<>(values);
        result.forEach(item ->
            Objects.requireNonNull(item, "list element"));
        result.sort(comparator);
        return List.copyOf(result);
    }

    private static void unique(
        List<String> values,
        String description
    ) {
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(
                "duplicate " + description);
        }
    }

    private static String normalize(String value) {
        return value == null
            ? ""
            : value.trim().replaceAll("\\s+", " ");
    }

    private static String normalizeRequired(
        String value,
        String name
    ) {
        String normalized = normalize(value);
        requireText(normalized, name);
        return normalized;
    }

    private static void requireText(
        String value,
        String name
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                name + " must not be blank");
        }
    }

    private static void requireSha(
        String value,
        String name
    ) {
        if (value == null
                || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                name + " must be SHA-256");
        }
    }
}
