package de.regelsuche.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.discovery.domain.DomainDiscoveryExport.ArtifactRole;
import de.regelsuche.discovery.domain.DomainDiscoveryExportVerifier.VerifiedDomainExport;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2;
import de.regelsuche.json.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Fail-closed qualification of a reproducible multi-domain discovery run. */
public final class DomainGenericDiscoveryQualification {
    public static final String SCHEMA =
        "regelsuche.domain-generic-discovery-qualification/v1";
    public static final String NOT_EVALUATED = "NOT_EVALUATED";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> EXPECTED_RESOURCES = Set.of(
        "EXPLORED_STATES",
        "GENERATED_SUCCESSORS",
        "CANDIDATE_EVALUATIONS",
        "COUNTEREXAMPLE_ATTEMPTS",
        "CERTIFICATE_ATTEMPTS");
    private static final Set<String> FORBIDDEN_HANDOFF_FIELDS = Set.of(
        "payload",
        "canonicalState",
        "seedExpression",
        "selectedExpression",
        "sequenceTerms",
        "states",
        "path");

    public QualificationReport evaluate(
        List<VerifiedDomainExport> exports,
        int cleanRunCount,
        boolean cleanRunsIdentical
    ) {
        Objects.requireNonNull(exports, "exports");
        if (exports.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("exports must not contain null");
        }
        if (cleanRunCount < 0) {
            throw new IllegalArgumentException("cleanRunCount must be non-negative");
        }

        List<DomainSummary> domains = exports.stream()
            .map(DomainGenericDiscoveryQualification::summarize)
            .sorted(Comparator.comparing(DomainSummary::domainId))
            .toList();
        List<RequirementCheck> checks = checks(
            domains,
            cleanRunCount,
            cleanRunsIdentical);
        QualificationStatus status = checks.stream()
            .allMatch(RequirementCheck::passed)
                ? QualificationStatus.READY
                : QualificationStatus.BLOCKED;
        List<String> blockers = checks.stream()
            .filter(check -> !check.passed())
            .map(RequirementCheck::code)
            .sorted()
            .toList();
        boolean claimAuthorized = status == QualificationStatus.READY
            && DomainGenericEvidenceProfile.DOMAIN_GENERIC_DISCOVERY
                .authorizesDomainGenericClaim();
        return QualificationReport.create(
            DomainGenericEvidenceProfile.DOMAIN_GENERIC_DISCOVERY,
            status,
            claimAuthorized,
            false,
            cleanRunCount,
            cleanRunsIdentical,
            domains,
            checks,
            blockers,
            NOT_EVALUATED,
            NOT_EVALUATED,
            NOT_EVALUATED,
            NOT_EVALUATED);
    }

    private static List<RequirementCheck> checks(
        List<DomainSummary> domains,
        int cleanRunCount,
        boolean cleanRunsIdentical
    ) {
        Set<String> domainIds = domains.stream()
            .map(DomainSummary::domainId)
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        Set<String> stateTypes = domains.stream()
            .map(DomainSummary::stateType)
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        boolean resourcesShared = !domains.isEmpty()
            && domains.stream().allMatch(domain ->
                domain.resourceNames().equals(domains.getFirst().resourceNames()))
            && domains.getFirst().resourceNames().equals(
                EXPECTED_RESOURCES.stream().sorted().toList());
        return List.of(
            check("VERIFIED_EXPORT_SNAPSHOTS",
                domains.size() > 0 && domains.stream().allMatch(
                    domain -> "VERIFIED".equals(domain.identityBindingStatus())),
                domains.stream().map(DomainSummary::identityBindingStatus).toList(),
                "all VERIFIED"),
            check("AT_LEAST_TWO_DISTINCT_DOMAINS",
                domainIds.size() >= 2,
                domainIds,
                ">=2 distinct domain IDs"),
            check("DISTINCT_MATHEMATICAL_STATE_TYPES",
                stateTypes.size() >= 2,
                stateTypes,
                ">=2 distinct state types"),
            check("EXPRESSION_REWRITE_DOMAIN_RETAINED",
                domainIds.contains("expression-rewrite"),
                domainIds,
                "expression-rewrite"),
            check("NON_EXPRESSION_DOMAIN_RETAINED",
                domainIds.stream().anyMatch(id -> !"expression-rewrite".equals(id)),
                domainIds,
                ">=1 non-expression domain"),
            check("CONFIRMED_CANDIDATES_WITH_CERTIFICATES",
                domains.size() > 0 && domains.stream().allMatch(
                    domain -> "CONFIRMED".equals(domain.outcome())
                        && !domain.selectedCandidateHash().isEmpty()
                        && !domain.certificateHash().isEmpty()),
                domains.stream().map(domain -> domain.domainId() + ':'
                    + domain.outcome() + ':'
                    + !domain.selectedCandidateHash().isEmpty() + ':'
                    + !domain.certificateHash().isEmpty()).toList(),
                "every domain CONFIRMED with selected candidate and certificate"),
            check("SHARED_RESOURCE_ACCOUNTING",
                resourcesShared,
                domains.stream().map(DomainSummary::resourceNames).toList(),
                EXPECTED_RESOURCES.stream().sorted().toList()),
            check("BALANCED_RESOURCE_ACCOUNTING",
                domains.size() > 0 && domains.stream().allMatch(
                    DomainSummary::resourcesBalanced),
                domains.stream().map(domain -> domain.domainId() + ':'
                    + domain.resourcesBalanced()).toList(),
                "all configured=executed+skipped+remaining"),
            check("REPRESENTATION_FREE_LIFECYCLE_HANDOFF",
                domains.size() > 0 && domains.stream().allMatch(
                    DomainSummary::representationFreeHandoff),
                domains.stream().map(domain -> domain.domainId() + ':'
                    + domain.representationFreeHandoff()).toList(),
                true),
            check("THREE_CLEAN_MULTI_DOMAIN_RUNS",
                cleanRunCount >= 3 && cleanRunsIdentical,
                cleanRunCount + " runs; identical=" + cleanRunsIdentical,
                ">=3 byte-identical runs"),
            check("PROOF_STATUS_NOT_EVALUATED",
                allStatus(domains, DomainSummary::proofStatus),
                statuses(domains, DomainSummary::proofStatus),
                NOT_EVALUATED),
            check("EXTERNAL_NOVELTY_STATUS_NOT_EVALUATED",
                allStatus(domains, DomainSummary::externalNoveltyStatus),
                statuses(domains, DomainSummary::externalNoveltyStatus),
                NOT_EVALUATED),
            check("PROMOTION_STATUS_NOT_EVALUATED",
                allStatus(domains, DomainSummary::promotionStatus),
                statuses(domains, DomainSummary::promotionStatus),
                NOT_EVALUATED),
            check("PUBLIC_EVIDENCE_STATUS_NOT_EVALUATED",
                allStatus(domains, DomainSummary::publicEvidenceStatus),
                statuses(domains, DomainSummary::publicEvidenceStatus),
                NOT_EVALUATED));
    }

    private static boolean allStatus(
        List<DomainSummary> domains,
        java.util.function.Function<DomainSummary, String> getter
    ) {
        return !domains.isEmpty()
            && domains.stream().map(getter).allMatch(NOT_EVALUATED::equals);
    }

    private static List<String> statuses(
        List<DomainSummary> domains,
        java.util.function.Function<DomainSummary, String> getter
    ) {
        return domains.stream()
            .map(domain -> domain.domainId() + ':' + getter.apply(domain))
            .toList();
    }

    private static DomainSummary summarize(VerifiedDomainExport export) {
        try {
            JsonNode descriptor = JSON.readTree(export.artifactBytes(
                ArtifactRole.DOMAIN_DESCRIPTOR));
            JsonNode evidence = JSON.readTree(export.artifactBytes(
                ArtifactRole.DISCOVERY_EVIDENCE));
            JsonNode handoff = JSON.readTree(export.artifactBytes(
                ArtifactRole.LIFECYCLE_HANDOFF));
            String domainId = text(descriptor, "domainId");
            String selectedCandidateHash = optionalText(
                evidence, "selectedCandidateHash");
            JsonNode certificate = evidence.get("certificate");
            String certificateHash = certificate == null || certificate.isNull()
                ? ""
                : text(certificate, "contentHash");
            List<String> resources = new ArrayList<>();
            boolean balanced = true;
            for (JsonNode resource : evidence.withArray("resources")) {
                resources.add(text(resource, "resource"));
                long configured = integer(resource, "configured");
                long executed = integer(resource, "executed");
                long skipped = integer(resource, "skipped");
                long remaining = integer(resource, "remaining");
                balanced &= configured == executed + skipped + remaining;
            }
            resources.sort(String::compareTo);
            Set<String> nestedHandoffFields = new HashSet<>();
            collectFieldNames(handoff, nestedHandoffFields);
            boolean representationFree = FORBIDDEN_HANDOFF_FIELDS.stream()
                .noneMatch(nestedHandoffFields::contains);
            requireSameStatus(evidence, handoff, "proofStatus");
            requireSameStatus(evidence, handoff, "externalNoveltyStatus");
            requireSameStatus(evidence, handoff, "promotionStatus");
            requireSameStatus(evidence, handoff, "publicEvidenceStatus");
            return new DomainSummary(
                domainId,
                text(descriptor, "revision"),
                text(descriptor, "stateType"),
                text(descriptor, "candidateType"),
                text(descriptor, "certificateType"),
                text(descriptor, "contentHash"),
                text(evidence, "contentHash"),
                text(handoff, "contentHash"),
                export.verification().contentHash(),
                export.verification().identityBindingStatus(),
                text(evidence, "outcome"),
                selectedCandidateHash,
                certificateHash,
                List.copyOf(resources),
                balanced,
                representationFree,
                text(evidence, "proofStatus"),
                text(evidence, "externalNoveltyStatus"),
                text(evidence, "promotionStatus"),
                text(evidence, "publicEvidenceStatus"));
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                "verified domain export snapshot is not readable JSON", exception);
        }
    }

    private static void requireSameStatus(
        JsonNode evidence,
        JsonNode handoff,
        String field
    ) {
        if (!text(evidence, field).equals(text(handoff, field))) {
            throw new IllegalArgumentException(
                "evidence and handoff status mismatch: " + field);
        }
    }

    private static void collectFieldNames(JsonNode node, Set<String> target) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                target.add(entry.getKey());
                collectFieldNames(entry.getValue(), target);
            });
        } else if (node.isArray()) {
            node.forEach(item -> collectFieldNames(item, target));
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(field + " must be text");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(field + " must be text or null");
        }
        return value.textValue();
    }

    private static long integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return value.longValue();
    }

    private static RequirementCheck check(
        String code,
        boolean passed,
        Object actual,
        Object required
    ) {
        return new RequirementCheck(
            code,
            passed,
            String.valueOf(actual),
            String.valueOf(required));
    }

    public enum QualificationStatus {
        READY,
        BLOCKED
    }

    public record RequirementCheck(
        String code,
        boolean passed,
        String actual,
        String required
    ) {
        public RequirementCheck {
            requireText(code, "code");
            requireText(actual, "actual");
            requireText(required, "required");
        }

        private String canonicalMaterial() {
            return code + "|passed=" + passed
                + "|actual=" + actual
                + "|required=" + required;
        }
    }

    public record DomainSummary(
        String domainId,
        String domainRevision,
        String stateType,
        String candidateType,
        String certificateType,
        String domainDescriptorHash,
        String discoveryEvidenceHash,
        String lifecycleHandoffHash,
        String exportVerificationHash,
        String identityBindingStatus,
        String outcome,
        String selectedCandidateHash,
        String certificateHash,
        List<String> resourceNames,
        boolean resourcesBalanced,
        boolean representationFreeHandoff,
        String proofStatus,
        String externalNoveltyStatus,
        String promotionStatus,
        String publicEvidenceStatus
    ) {
        public DomainSummary {
            domainId = requireIdentifier(domainId, "domainId");
            domainRevision = requireIdentifier(domainRevision, "domainRevision");
            stateType = requireIdentifier(stateType, "stateType");
            candidateType = requireIdentifier(candidateType, "candidateType");
            certificateType = requireIdentifier(certificateType, "certificateType");
            requireSha256(domainDescriptorHash, "domainDescriptorHash");
            requireSha256(discoveryEvidenceHash, "discoveryEvidenceHash");
            requireSha256(lifecycleHandoffHash, "lifecycleHandoffHash");
            requireSha256(exportVerificationHash, "exportVerificationHash");
            requireText(identityBindingStatus, "identityBindingStatus");
            requireText(outcome, "outcome");
            selectedCandidateHash = optionalSha256(
                selectedCandidateHash, "selectedCandidateHash");
            certificateHash = optionalSha256(certificateHash, "certificateHash");
            resourceNames = resourceNames == null ? List.of() : resourceNames.stream()
                .map(name -> requireIdentifier(name, "resourceName"))
                .distinct()
                .sorted()
                .toList();
            requireText(proofStatus, "proofStatus");
            requireText(externalNoveltyStatus, "externalNoveltyStatus");
            requireText(promotionStatus, "promotionStatus");
            requireText(publicEvidenceStatus, "publicEvidenceStatus");
        }

        private String canonicalMaterial() {
            return domainId + '|' + domainRevision + '|' + stateType + '|'
                + candidateType + '|' + certificateType + '|'
                + domainDescriptorHash + '|' + discoveryEvidenceHash + '|'
                + lifecycleHandoffHash + '|' + exportVerificationHash + '|'
                + identityBindingStatus + '|' + outcome + '|'
                + selectedCandidateHash + '|' + certificateHash + '|'
                + resourceNames + '|' + resourcesBalanced + '|'
                + representationFreeHandoff + '|' + proofStatus + '|'
                + externalNoveltyStatus + '|' + promotionStatus + '|'
                + publicEvidenceStatus;
        }
    }

    public record QualificationReport(
        String schema,
        DomainGenericEvidenceProfile profile,
        QualificationStatus status,
        boolean domainGenericClaimAuthorized,
        boolean autonomousCampaignClaimAuthorized,
        int cleanRunCount,
        boolean cleanRunsIdentical,
        List<DomainSummary> domains,
        List<RequirementCheck> checks,
        List<String> blockers,
        String proofStatus,
        String externalNoveltyStatus,
        String promotionStatus,
        String publicEvidenceStatus,
        String contentHash
    ) {
        public QualificationReport {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported domain-generic qualification schema");
            }
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(status, "status");
            if (cleanRunCount < 0) {
                throw new IllegalArgumentException(
                    "cleanRunCount must be non-negative");
            }
            domains = domains == null ? List.of() : domains.stream()
                .map(domain -> Objects.requireNonNull(domain, "domain"))
                .sorted(Comparator.comparing(DomainSummary::domainId))
                .toList();
            checks = checks == null ? List.of() : checks.stream()
                .map(check -> Objects.requireNonNull(check, "check"))
                .sorted(Comparator.comparing(RequirementCheck::code))
                .toList();
            blockers = blockers == null ? List.of() : blockers.stream()
                .map(blocker -> requireIdentifier(blocker, "blocker"))
                .distinct()
                .sorted()
                .toList();
            requireText(proofStatus, "proofStatus");
            requireText(externalNoveltyStatus, "externalNoveltyStatus");
            requireText(promotionStatus, "promotionStatus");
            requireText(publicEvidenceStatus, "publicEvidenceStatus");
            boolean ready = status == QualificationStatus.READY;
            if (domainGenericClaimAuthorized != ready
                    || autonomousCampaignClaimAuthorized
                    || ready != blockers.isEmpty()
                    || ready != checks.stream().allMatch(RequirementCheck::passed)
                    || !NOT_EVALUATED.equals(proofStatus)
                    || !NOT_EVALUATED.equals(externalNoveltyStatus)
                    || !NOT_EVALUATED.equals(promotionStatus)
                    || !NOT_EVALUATED.equals(publicEvidenceStatus)) {
                throw new IllegalArgumentException(
                    "domain-generic qualification flags or scientific boundaries disagree");
            }
            requireSha256(contentHash, "contentHash");
            String expected = hash(
                profile,
                status,
                domainGenericClaimAuthorized,
                autonomousCampaignClaimAuthorized,
                cleanRunCount,
                cleanRunsIdentical,
                domains,
                checks,
                blockers,
                proofStatus,
                externalNoveltyStatus,
                promotionStatus,
                publicEvidenceStatus);
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "domain-generic qualification contentHash mismatch");
            }
        }

        private static QualificationReport create(
            DomainGenericEvidenceProfile profile,
            QualificationStatus status,
            boolean domainGenericClaimAuthorized,
            boolean autonomousCampaignClaimAuthorized,
            int cleanRunCount,
            boolean cleanRunsIdentical,
            List<DomainSummary> domains,
            List<RequirementCheck> checks,
            List<String> blockers,
            String proofStatus,
            String externalNoveltyStatus,
            String promotionStatus,
            String publicEvidenceStatus
        ) {
            List<DomainSummary> orderedDomains = domains.stream()
                .sorted(Comparator.comparing(DomainSummary::domainId))
                .toList();
            List<RequirementCheck> orderedChecks = checks.stream()
                .sorted(Comparator.comparing(RequirementCheck::code))
                .toList();
            List<String> orderedBlockers = blockers.stream().distinct().sorted().toList();
            String contentHash = hash(
                profile,
                status,
                domainGenericClaimAuthorized,
                autonomousCampaignClaimAuthorized,
                cleanRunCount,
                cleanRunsIdentical,
                orderedDomains,
                orderedChecks,
                orderedBlockers,
                proofStatus,
                externalNoveltyStatus,
                promotionStatus,
                publicEvidenceStatus);
            return new QualificationReport(
                SCHEMA,
                profile,
                status,
                domainGenericClaimAuthorized,
                autonomousCampaignClaimAuthorized,
                cleanRunCount,
                cleanRunsIdentical,
                orderedDomains,
                orderedChecks,
                orderedBlockers,
                proofStatus,
                externalNoveltyStatus,
                promotionStatus,
                publicEvidenceStatus,
                contentHash);
        }

        public String toCanonicalJson() {
            return render(true);
        }

        private String render(boolean includeHash) {
            JsonWriter json = new JsonWriter().beginObject()
                .property("schema", schema)
                .property("profile", profile.name())
                .property("claim", profile.claim())
                .property("status", status.name())
                .property("domainGenericClaimAuthorized",
                    domainGenericClaimAuthorized)
                .property("autonomousCampaignClaimAuthorized",
                    autonomousCampaignClaimAuthorized)
                .property("cleanRunCount", cleanRunCount)
                .property("cleanRunsIdentical", cleanRunsIdentical)
                .array("domains", array -> domains.forEach(domain ->
                    array.objectValue(object -> object
                        .property("domainId", domain.domainId())
                        .property("domainRevision", domain.domainRevision())
                        .property("stateType", domain.stateType())
                        .property("candidateType", domain.candidateType())
                        .property("certificateType", domain.certificateType())
                        .property("domainDescriptorHash",
                            domain.domainDescriptorHash())
                        .property("discoveryEvidenceHash",
                            domain.discoveryEvidenceHash())
                        .property("lifecycleHandoffHash",
                            domain.lifecycleHandoffHash())
                        .property("exportVerificationHash",
                            domain.exportVerificationHash())
                        .property("identityBindingStatus",
                            domain.identityBindingStatus())
                        .property("outcome", domain.outcome())
                        .property("selectedCandidateHash",
                            domain.selectedCandidateHash())
                        .property("certificateHash", domain.certificateHash())
                        .stringArray("resourceNames", domain.resourceNames())
                        .property("resourcesBalanced", domain.resourcesBalanced())
                        .property("representationFreeHandoff",
                            domain.representationFreeHandoff())
                        .property("proofStatus", domain.proofStatus())
                        .property("externalNoveltyStatus",
                            domain.externalNoveltyStatus())
                        .property("promotionStatus", domain.promotionStatus())
                        .property("publicEvidenceStatus",
                            domain.publicEvidenceStatus()))))
                .array("checks", array -> checks.forEach(check ->
                    array.objectValue(object -> object
                        .property("code", check.code())
                        .property("passed", check.passed())
                        .property("actual", check.actual())
                        .property("required", check.required()))))
                .stringArray("blockers", blockers)
                .property("proofStatus", proofStatus)
                .property("externalNoveltyStatus", externalNoveltyStatus)
                .property("promotionStatus", promotionStatus)
                .property("publicEvidenceStatus", publicEvidenceStatus);
            if (includeHash) {
                json.property("contentHash", contentHash);
            }
            return json.endObject().toString();
        }

        private static String hash(
            DomainGenericEvidenceProfile profile,
            QualificationStatus status,
            boolean domainGenericClaimAuthorized,
            boolean autonomousCampaignClaimAuthorized,
            int cleanRunCount,
            boolean cleanRunsIdentical,
            List<DomainSummary> domains,
            List<RequirementCheck> checks,
            List<String> blockers,
            String proofStatus,
            String externalNoveltyStatus,
            String promotionStatus,
            String publicEvidenceStatus
        ) {
            return AutonomousResearchBriefV2.hash(
                SCHEMA
                    + "\nprofile=" + profile.name()
                    + "\nstatus=" + status.name()
                    + "\ndomainGenericClaimAuthorized="
                        + domainGenericClaimAuthorized
                    + "\nautonomousCampaignClaimAuthorized="
                        + autonomousCampaignClaimAuthorized
                    + "\ncleanRunCount=" + cleanRunCount
                    + "\ncleanRunsIdentical=" + cleanRunsIdentical
                    + "\ndomains=" + domains.stream()
                        .map(DomainSummary::canonicalMaterial).toList()
                    + "\nchecks=" + checks.stream()
                        .map(RequirementCheck::canonicalMaterial).toList()
                    + "\nblockers=" + blockers
                    + "\nproofStatus=" + proofStatus
                    + "\nexternalNoveltyStatus=" + externalNoveltyStatus
                    + "\npromotionStatus=" + promotionStatus
                    + "\npublicEvidenceStatus=" + publicEvidenceStatus);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String requireIdentifier(String value, String field) {
        requireText(value, field);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,191}")) {
            throw new IllegalArgumentException(field + " is not an identifier");
        }
        return value;
    }

    private static String requireSha256(String value, String field) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be SHA-256");
        }
        return value;
    }

    private static String optionalSha256(String value, String field) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return requireSha256(value, field);
    }
}
