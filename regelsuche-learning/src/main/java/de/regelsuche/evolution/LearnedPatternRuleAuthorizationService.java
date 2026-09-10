package de.regelsuche.evolution;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Fail-closed authorization boundary for exactly proved learned pattern rules.
 *
 * <p>The mathematical promoter remains responsible for genome preflight and the
 * exact pattern-identity proof. This service adds the missing semantic evidence
 * boundary: all validation, counterexample, holdout and leakage roots are loaded
 * from disk, structurally and semantically checked, bound to one subject and one
 * repository revision, checked for expiry, and only then handed to the promoter.
 * The resulting receipt authorizes only this narrow assumption-free pattern-rule
 * contract; {@code RewriteProgram}s require a separate program replay contract.</p>
 */
public final class LearnedPatternRuleAuthorizationService {
    public static final String EVIDENCE_ROOT_SCHEMA =
        "regelsuche.learned-rule-promotion-evidence-root/v1";
    public static final String AUTHORIZATION_RECEIPT_SCHEMA =
        "regelsuche.learned-pattern-rule-authorization-receipt/v1";
    public static final String AUTHORIZER_ID =
        "regelsuche.learned-pattern-rule-authorizer/v1";

    private static final Set<String> ROOT_FIELDS = Set.of(
        "schema",
        "role",
        "status",
        "genomeHash",
        "geneId",
        "repositoryRevision",
        "issuedAt",
        "expiresAt",
        "artifactHash",
        "contentHash");

    private static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());

    private final LearnedPatternRulePromoter promoter;

    public LearnedPatternRuleAuthorizationService() {
        this(new LearnedPatternRulePromoter());
    }

    LearnedPatternRuleAuthorizationService(LearnedPatternRulePromoter promoter) {
        this.promoter = Objects.requireNonNull(promoter, "promoter");
    }

    /**
     * Loads every evidence root and authorizes exactly one learned pattern gene.
     *
     * @param asOf explicit evaluation instant; never replaced by an implicit wall clock
     */
    public Authorization authorize(
        EvolutionGenome genome,
        String geneId,
        String repositoryRevision,
        EvidenceFiles evidenceFiles,
        Instant asOf
    ) throws IOException {
        Objects.requireNonNull(genome, "genome");
        requireText(geneId, "geneId");
        requireRevision(repositoryRevision, "repositoryRevision");
        Objects.requireNonNull(evidenceFiles, "evidenceFiles");
        Objects.requireNonNull(asOf, "asOf");

        EnumMap<EvidenceRole, EvidenceRoot> roots =
            new EnumMap<>(EvidenceRole.class);
        roots.put(EvidenceRole.SEMANTIC_VALIDATION, load(
            evidenceFiles.semanticValidation(),
            EvidenceRole.SEMANTIC_VALIDATION,
            genome.contentHash(), geneId, repositoryRevision, asOf));
        roots.put(EvidenceRole.COUNTEREXAMPLE_SEARCH, load(
            evidenceFiles.counterexampleSearch(),
            EvidenceRole.COUNTEREXAMPLE_SEARCH,
            genome.contentHash(), geneId, repositoryRevision, asOf));
        roots.put(EvidenceRole.HOLDOUT_EVALUATION, load(
            evidenceFiles.holdoutEvaluation(),
            EvidenceRole.HOLDOUT_EVALUATION,
            genome.contentHash(), geneId, repositoryRevision, asOf));
        roots.put(EvidenceRole.LEAKAGE_AUDIT, load(
            evidenceFiles.leakageAudit(),
            EvidenceRole.LEAKAGE_AUDIT,
            genome.contentHash(), geneId, repositoryRevision, asOf));

        LearnedPatternRulePromoter.PromotionEvidence evidence =
            new LearnedPatternRulePromoter.PromotionEvidence(
                roots.get(EvidenceRole.SEMANTIC_VALIDATION).contentHash(),
                roots.get(EvidenceRole.COUNTEREXAMPLE_SEARCH).contentHash(),
                roots.get(EvidenceRole.HOLDOUT_EVALUATION).contentHash(),
                roots.get(EvidenceRole.LEAKAGE_AUDIT).contentHash(),
                repositoryRevision);
        LearnedPatternRulePromoter.Promotion promotion =
            promoter.promote(genome, geneId, evidence);

        Instant validUntil = roots.values().stream()
            .map(EvidenceRoot::expiresAt)
            .min(Instant::compareTo)
            .orElseThrow();
        AuthorizationReceipt receipt = AuthorizationReceipt.create(
            genome,
            geneId,
            repositoryRevision,
            asOf,
            validUntil,
            roots,
            promotion);
        return new Authorization(promotion, Map.copyOf(roots), receipt);
    }

    private static EvidenceRoot load(
        Path path,
        EvidenceRole expectedRole,
        String expectedGenomeHash,
        String expectedGeneId,
        String expectedRepositoryRevision,
        Instant asOf
    ) throws IOException {
        Path file = requireFile(path, expectedRole.name());
        ObjectNode value = requireObject(parseStrict(file), expectedRole.name());
        Set<String> actualFields = new HashSet<>();
        value.fieldNames().forEachRemaining(actualFields::add);
        if (!actualFields.equals(ROOT_FIELDS)) {
            Set<String> missing = new HashSet<>(ROOT_FIELDS);
            missing.removeAll(actualFields);
            Set<String> unknown = new HashSet<>(actualFields);
            unknown.removeAll(ROOT_FIELDS);
            throw new IllegalArgumentException(
                "evidence root fields mismatch for " + expectedRole
                    + ": missing=" + missing + ", unknown=" + unknown);
        }

        EvidenceRoot root = new EvidenceRoot(
            requireText(value, "schema"),
            EvidenceRole.parse(requireText(value, "role")),
            requireText(value, "status"),
            requireSha(value, "genomeHash"),
            requireText(value, "geneId"),
            requireRevision(value, "repositoryRevision"),
            requireInstant(value, "issuedAt"),
            requireInstant(value, "expiresAt"),
            requireSha(value, "artifactHash"),
            requireSha(value, "contentHash"));

        if (root.role() != expectedRole) {
            throw new IllegalArgumentException(
                "evidence role mismatch: expected " + expectedRole
                    + ", found " + root.role());
        }
        if (!root.genomeHash().equals(expectedGenomeHash)
                || !root.geneId().equals(expectedGeneId)) {
            throw new IllegalArgumentException(
                "evidence subject mismatch for " + expectedRole);
        }
        if (!root.repositoryRevision().equals(expectedRepositoryRevision)) {
            throw new IllegalArgumentException(
                "evidence repository revision mismatch for " + expectedRole);
        }
        if (asOf.isBefore(root.issuedAt())) {
            throw new IllegalArgumentException(
                "evidence is not yet valid for " + expectedRole);
        }
        if (!asOf.isBefore(root.expiresAt())) {
            throw new IllegalArgumentException(
                "evidence expired for " + expectedRole);
        }
        return root;
    }

    private static JsonNode parseStrict(Path path) throws IOException {
        try (JsonParser parser = JSON.getFactory().createParser(
                Files.newBufferedReader(path, StandardCharsets.UTF_8))) {
            JsonNode value = JSON.readTree(parser);
            if (value == null || parser.nextToken() != null) {
                throw new IllegalArgumentException(
                    "evidence root contains trailing JSON content: " + path);
            }
            return value;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "invalid evidence root JSON: " + path, exception);
        }
    }

    private static ObjectNode requireObject(JsonNode value, String name) {
        if (value instanceof ObjectNode object) {
            return object;
        }
        throw new IllegalArgumentException(name + " evidence root must be an object");
    }

    private static Path requireFile(Path path, String name) {
        Objects.requireNonNull(path, name + " path");
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IllegalArgumentException(
                name + " evidence root is missing: " + normalized);
        }
        if (Files.isSymbolicLink(normalized)) {
            throw new IllegalArgumentException(
                name + " evidence root must not be a symbolic link");
        }
        return normalized;
    }

    private static String requireText(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(field + " must be text");
        }
        return requireText(value.textValue(), field);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String requireSha(ObjectNode node, String field) {
        String value = requireText(node, field);
        EvolutionGenome.requireSha256(value, field);
        return value;
    }

    private static String requireRevision(ObjectNode node, String field) {
        return requireRevision(requireText(node, field), field);
    }

    private static String requireRevision(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException(
                field + " must be a lowercase commit SHA");
        }
        return value;
    }

    private static Instant requireInstant(ObjectNode node, String field) {
        String value = requireText(node, field);
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                field + " must be a canonical UTC instant", exception);
        }
    }

    private static String rootMaterial(
        String schema,
        EvidenceRole role,
        String status,
        String genomeHash,
        String geneId,
        String repositoryRevision,
        Instant issuedAt,
        Instant expiresAt,
        String artifactHash
    ) {
        StringBuilder material = new StringBuilder();
        append(material, schema);
        append(material, role.name());
        append(material, status);
        append(material, genomeHash);
        append(material, geneId);
        append(material, repositoryRevision);
        append(material, issuedAt.toString());
        append(material, expiresAt.toString());
        append(material, artifactHash);
        return material.toString();
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    private static String json(ObjectNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize canonical evidence", exception);
        }
    }

    public enum EvidenceRole {
        SEMANTIC_VALIDATION("PASSED"),
        COUNTEREXAMPLE_SEARCH("NO_COUNTEREXAMPLE_FOUND"),
        HOLDOUT_EVALUATION("PASSED"),
        LEAKAGE_AUDIT("PASSED");

        private final String acceptedStatus;

        EvidenceRole(String acceptedStatus) {
            this.acceptedStatus = acceptedStatus;
        }

        public String acceptedStatus() {
            return acceptedStatus;
        }

        static EvidenceRole parse(String value) {
            try {
                return EvidenceRole.valueOf(value);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                    "unsupported evidence role: " + value, exception);
            }
        }
    }

    /** Exact files used for one authorization attempt. */
    public record EvidenceFiles(
        Path semanticValidation,
        Path counterexampleSearch,
        Path holdoutEvaluation,
        Path leakageAudit
    ) {
        public EvidenceFiles {
            List<Path> files = List.of(
                Objects.requireNonNull(semanticValidation, "semanticValidation"),
                Objects.requireNonNull(counterexampleSearch, "counterexampleSearch"),
                Objects.requireNonNull(holdoutEvaluation, "holdoutEvaluation"),
                Objects.requireNonNull(leakageAudit, "leakageAudit"));
            Set<Path> identities = new HashSet<>();
            for (Path file : files) {
                if (!identities.add(file.toAbsolutePath().normalize())) {
                    throw new IllegalArgumentException(
                        "each promotion evidence role requires a distinct root file");
                }
            }
        }
    }

    /**
     * Content-addressed semantic root around one independently produced evidence artifact.
     */
    public record EvidenceRoot(
        String schema,
        EvidenceRole role,
        String status,
        String genomeHash,
        String geneId,
        String repositoryRevision,
        Instant issuedAt,
        Instant expiresAt,
        String artifactHash,
        String contentHash
    ) {
        public EvidenceRoot {
            if (!EVIDENCE_ROOT_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported evidence root schema: " + schema);
            }
            role = Objects.requireNonNull(role, "role");
            requireText(status, "status");
            EvolutionGenome.requireSha256(genomeHash, "genomeHash");
            requireText(geneId, "geneId");
            requireRevision(repositoryRevision, "repositoryRevision");
            issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
            if (!issuedAt.isBefore(expiresAt)) {
                throw new IllegalArgumentException(
                    "evidence expiresAt must be later than issuedAt");
            }
            if (!role.acceptedStatus().equals(status)) {
                throw new IllegalArgumentException(
                    "non-passing evidence status for " + role + ": " + status);
            }
            EvolutionGenome.requireSha256(artifactHash, "artifactHash");
            EvolutionGenome.requireSha256(contentHash, "contentHash");
            String expected = EvolutionGenome.hash(rootMaterial(
                schema, role, status, genomeHash, geneId,
                repositoryRevision, issuedAt, expiresAt, artifactHash));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "evidence root contentHash mismatch for " + role);
            }
        }

        public static EvidenceRoot create(
            EvidenceRole role,
            String genomeHash,
            String geneId,
            String repositoryRevision,
            Instant issuedAt,
            Instant expiresAt,
            String artifactHash
        ) {
            String material = rootMaterial(
                EVIDENCE_ROOT_SCHEMA,
                role,
                role.acceptedStatus(),
                genomeHash,
                geneId,
                repositoryRevision,
                issuedAt,
                expiresAt,
                artifactHash);
            return new EvidenceRoot(
                EVIDENCE_ROOT_SCHEMA,
                role,
                role.acceptedStatus(),
                genomeHash,
                geneId,
                repositoryRevision,
                issuedAt,
                expiresAt,
                artifactHash,
                EvolutionGenome.hash(material));
        }

        public String toCanonicalJson() {
            ObjectNode node = JSON.createObjectNode();
            node.put("schema", schema);
            node.put("role", role.name());
            node.put("status", status);
            node.put("genomeHash", genomeHash);
            node.put("geneId", geneId);
            node.put("repositoryRevision", repositoryRevision);
            node.put("issuedAt", issuedAt.toString());
            node.put("expiresAt", expiresAt.toString());
            node.put("artifactHash", artifactHash);
            node.put("contentHash", contentHash);
            return json(node);
        }
    }

    /** Narrow authorization receipt; it is not an authorization for learned programs. */
    public record AuthorizationReceipt(
        String schema,
        String authorizerId,
        String genomeHash,
        String geneId,
        String repositoryRevision,
        Instant authorizedAt,
        Instant validUntil,
        String semanticValidationRootHash,
        String counterexampleSearchRootHash,
        String holdoutEvaluationRootHash,
        String leakageAuditRootHash,
        String promotionReceiptHash,
        String promotedRuleId,
        String promotedRuleHash,
        String applicabilitySchemaHash,
        String contentHash
    ) {
        public AuthorizationReceipt {
            if (!AUTHORIZATION_RECEIPT_SCHEMA.equals(schema)
                    || !AUTHORIZER_ID.equals(authorizerId)) {
                throw new IllegalArgumentException(
                    "learned pattern authorization receipt identity is invalid");
            }
            EvolutionGenome.requireSha256(genomeHash, "genomeHash");
            requireText(geneId, "geneId");
            requireRevision(repositoryRevision, "repositoryRevision");
            authorizedAt = Objects.requireNonNull(authorizedAt, "authorizedAt");
            validUntil = Objects.requireNonNull(validUntil, "validUntil");
            if (!authorizedAt.isBefore(validUntil)) {
                throw new IllegalArgumentException(
                    "authorization must expire after authorizedAt");
            }
            for (String hash : List.of(
                    semanticValidationRootHash,
                    counterexampleSearchRootHash,
                    holdoutEvaluationRootHash,
                    leakageAuditRootHash,
                    promotionReceiptHash,
                    promotedRuleHash,
                    applicabilitySchemaHash,
                    contentHash)) {
                EvolutionGenome.requireSha256(hash, "authorization hash");
            }
            requireText(promotedRuleId, "promotedRuleId");
            String expected = EvolutionGenome.hash(material(
                schema, authorizerId, genomeHash, geneId, repositoryRevision,
                authorizedAt, validUntil,
                semanticValidationRootHash, counterexampleSearchRootHash,
                holdoutEvaluationRootHash, leakageAuditRootHash,
                promotionReceiptHash, promotedRuleId, promotedRuleHash,
                applicabilitySchemaHash));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "authorization receipt contentHash mismatch");
            }
        }

        private static AuthorizationReceipt create(
            EvolutionGenome genome,
            String geneId,
            String repositoryRevision,
            Instant authorizedAt,
            Instant validUntil,
            Map<EvidenceRole, EvidenceRoot> roots,
            LearnedPatternRulePromoter.Promotion promotion
        ) {
            String promotionReceiptHash = promotion.receipt().contentHash();
            String promotedRuleHash =
                RuleInventoryFingerprint.ruleContentHash(promotion.rule());
            String schemaHash = promotion.applicabilitySchema().contentHash();
            String material = material(
                AUTHORIZATION_RECEIPT_SCHEMA,
                AUTHORIZER_ID,
                genome.contentHash(),
                geneId,
                repositoryRevision,
                authorizedAt,
                validUntil,
                roots.get(EvidenceRole.SEMANTIC_VALIDATION).contentHash(),
                roots.get(EvidenceRole.COUNTEREXAMPLE_SEARCH).contentHash(),
                roots.get(EvidenceRole.HOLDOUT_EVALUATION).contentHash(),
                roots.get(EvidenceRole.LEAKAGE_AUDIT).contentHash(),
                promotionReceiptHash,
                promotion.rule().id(),
                promotedRuleHash,
                schemaHash);
            return new AuthorizationReceipt(
                AUTHORIZATION_RECEIPT_SCHEMA,
                AUTHORIZER_ID,
                genome.contentHash(),
                geneId,
                repositoryRevision,
                authorizedAt,
                validUntil,
                roots.get(EvidenceRole.SEMANTIC_VALIDATION).contentHash(),
                roots.get(EvidenceRole.COUNTEREXAMPLE_SEARCH).contentHash(),
                roots.get(EvidenceRole.HOLDOUT_EVALUATION).contentHash(),
                roots.get(EvidenceRole.LEAKAGE_AUDIT).contentHash(),
                promotionReceiptHash,
                promotion.rule().id(),
                promotedRuleHash,
                schemaHash,
                EvolutionGenome.hash(material));
        }

        private static String material(
            String schema,
            String authorizerId,
            String genomeHash,
            String geneId,
            String repositoryRevision,
            Instant authorizedAt,
            Instant validUntil,
            String semanticValidationRootHash,
            String counterexampleSearchRootHash,
            String holdoutEvaluationRootHash,
            String leakageAuditRootHash,
            String promotionReceiptHash,
            String promotedRuleId,
            String promotedRuleHash,
            String applicabilitySchemaHash
        ) {
            StringBuilder material = new StringBuilder();
            append(material, schema);
            append(material, authorizerId);
            append(material, genomeHash);
            append(material, geneId);
            append(material, repositoryRevision);
            append(material, authorizedAt.toString());
            append(material, validUntil.toString());
            append(material, semanticValidationRootHash);
            append(material, counterexampleSearchRootHash);
            append(material, holdoutEvaluationRootHash);
            append(material, leakageAuditRootHash);
            append(material, promotionReceiptHash);
            append(material, promotedRuleId);
            append(material, promotedRuleHash);
            append(material, applicabilitySchemaHash);
            return material.toString();
        }

        public void requireUsableAt(
            Instant asOf,
            String expectedRepositoryRevision,
            String expectedPromotedRuleHash
        ) {
            Objects.requireNonNull(asOf, "asOf");
            if (asOf.isBefore(authorizedAt) || !asOf.isBefore(validUntil)) {
                throw new IllegalArgumentException(
                    "learned rule authorization is not valid at " + asOf);
            }
            if (!repositoryRevision.equals(expectedRepositoryRevision)
                    || !promotedRuleHash.equals(expectedPromotedRuleHash)) {
                throw new IllegalArgumentException(
                    "learned rule authorization identity mismatch");
            }
        }

        public String toCanonicalJson() {
            ObjectNode node = JSON.createObjectNode();
            node.put("schema", schema);
            node.put("authorizerId", authorizerId);
            node.put("genomeHash", genomeHash);
            node.put("geneId", geneId);
            node.put("repositoryRevision", repositoryRevision);
            node.put("authorizedAt", authorizedAt.toString());
            node.put("validUntil", validUntil.toString());
            node.put("semanticValidationRootHash", semanticValidationRootHash);
            node.put("counterexampleSearchRootHash", counterexampleSearchRootHash);
            node.put("holdoutEvaluationRootHash", holdoutEvaluationRootHash);
            node.put("leakageAuditRootHash", leakageAuditRootHash);
            node.put("promotionReceiptHash", promotionReceiptHash);
            node.put("promotedRuleId", promotedRuleId);
            node.put("promotedRuleHash", promotedRuleHash);
            node.put("applicabilitySchemaHash", applicabilitySchemaHash);
            node.put("contentHash", contentHash);
            return json(node);
        }
    }

    public record Authorization(
        LearnedPatternRulePromoter.Promotion promotion,
        Map<EvidenceRole, EvidenceRoot> evidenceRoots,
        AuthorizationReceipt receipt
    ) {
        public Authorization {
            promotion = Objects.requireNonNull(promotion, "promotion");
            evidenceRoots = Map.copyOf(
                Objects.requireNonNull(evidenceRoots, "evidenceRoots"));
            receipt = Objects.requireNonNull(receipt, "receipt");
            if (evidenceRoots.size() != EvidenceRole.values().length
                    || !receipt.promotionReceiptHash().equals(
                        promotion.receipt().contentHash())
                    || !receipt.promotedRuleId().equals(promotion.rule().id())
                    || !receipt.promotedRuleHash().equals(
                        RuleInventoryFingerprint.ruleContentHash(promotion.rule()))
                    || !receipt.applicabilitySchemaHash().equals(
                        promotion.applicabilitySchema().contentHash())) {
                throw new IllegalArgumentException(
                    "learned rule authorization products are inconsistent");
            }
            for (EvidenceRole role : EvidenceRole.values()) {
                EvidenceRoot root = evidenceRoots.get(role);
                if (root == null || root.role() != role) {
                    throw new IllegalArgumentException(
                        "learned rule authorization lacks evidence for " + role);
                }
            }
        }
    }
}
