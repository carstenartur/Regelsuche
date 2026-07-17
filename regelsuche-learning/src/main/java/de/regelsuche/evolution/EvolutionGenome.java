package de.regelsuche.evolution;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.transform.RewriteKind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Versioned, canonical and independently serializable genome for bounded
 * evolutionary search.
 *
 * <p>The content hash binds the full TRAIN provenance and seed lineage. The
 * alpha-structural hash intentionally excludes provenance and placeholder names
 * so structurally identical candidates can be suppressed across populations.</p>
 */
public record EvolutionGenome(
    String schema,
    Objective objective,
    TrainingScope trainingScope,
    List<RewriteGene> rewrites,
    List<FeatureWeight> rankingFeatures,
    GuardPolicy guardPolicy,
    ResourceBudget budget,
    List<String> requiredCapabilities,
    List<String> seedGenomeHashes,
    String alphaStructuralHash,
    String contentHash
) {
    public static final String SCHEMA = "regelsuche.evolution-genome/v1";
    private static final Pattern PLACEHOLDER =
        Pattern.compile("\\?([A-Za-z][A-Za-z0-9_]*)");
    private static final Pattern GENE_ID =
        Pattern.compile("[a-z][a-z0-9_-]{2,127}");
    private static final Pattern CAPABILITY =
        Pattern.compile("[a-z][a-z0-9.-]{1,127}");
    private static final Pattern SHA256 =
        Pattern.compile("sha256:[0-9a-f]{64}");

    public EvolutionGenome {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported evolution genome schema");
        }
        Objects.requireNonNull(objective, "objective");
        Objects.requireNonNull(trainingScope, "trainingScope");
        rewrites = normalizeRewrites(rewrites);
        rankingFeatures = normalizeFeatures(rankingFeatures);
        Objects.requireNonNull(guardPolicy, "guardPolicy");
        Objects.requireNonNull(budget, "budget");
        requiredCapabilities = normalizeCapabilities(requiredCapabilities);
        seedGenomeHashes = normalizeHashes(seedGenomeHashes, "seedGenomeHashes");
        requireSha256(alphaStructuralHash, "alphaStructuralHash");
        requireSha256(contentHash, "contentHash");

        String expectedAlpha = hash(alphaStructuralMaterial(
            objective,
            rewrites,
            rankingFeatures,
            guardPolicy,
            budget,
            requiredCapabilities));
        if (!expectedAlpha.equals(alphaStructuralHash)) {
            throw new IllegalArgumentException("alphaStructuralHash does not match genome structure");
        }
        String expectedContent = hash(canonicalPayload(
            objective,
            trainingScope,
            rewrites,
            rankingFeatures,
            guardPolicy,
            budget,
            requiredCapabilities,
            seedGenomeHashes,
            alphaStructuralHash));
        if (!expectedContent.equals(contentHash)) {
            throw new IllegalArgumentException("contentHash does not match genome payload");
        }
    }

    public static EvolutionGenome create(
        Objective objective,
        TrainingScope trainingScope,
        List<RewriteGene> rewrites,
        List<FeatureWeight> rankingFeatures,
        GuardPolicy guardPolicy,
        ResourceBudget budget,
        List<String> requiredCapabilities,
        List<String> seedGenomeHashes
    ) {
        List<RewriteGene> normalizedRewrites = normalizeRewrites(rewrites);
        List<FeatureWeight> normalizedFeatures = normalizeFeatures(rankingFeatures);
        List<String> normalizedCapabilities = normalizeCapabilities(requiredCapabilities);
        List<String> normalizedSeeds = normalizeHashes(seedGenomeHashes, "seedGenomeHashes");
        String alphaHash = hash(alphaStructuralMaterial(
            objective,
            normalizedRewrites,
            normalizedFeatures,
            guardPolicy,
            budget,
            normalizedCapabilities));
        String payload = canonicalPayload(
            objective,
            trainingScope,
            normalizedRewrites,
            normalizedFeatures,
            guardPolicy,
            budget,
            normalizedCapabilities,
            normalizedSeeds,
            alphaHash);
        return new EvolutionGenome(
            SCHEMA,
            objective,
            trainingScope,
            normalizedRewrites,
            normalizedFeatures,
            guardPolicy,
            budget,
            normalizedCapabilities,
            normalizedSeeds,
            alphaHash,
            hash(payload));
    }

    public EvolutionGenome withRewrites(List<RewriteGene> replacements) {
        return recreate(replacements, rankingFeatures, guardPolicy, budget);
    }

    public EvolutionGenome withRankingFeatures(List<FeatureWeight> replacements) {
        return recreate(rewrites, replacements, guardPolicy, budget);
    }

    public EvolutionGenome withGuardPolicy(GuardPolicy replacement) {
        return recreate(rewrites, rankingFeatures, replacement, budget);
    }

    public EvolutionGenome withBudget(ResourceBudget replacement) {
        return recreate(rewrites, rankingFeatures, guardPolicy, replacement);
    }

    private EvolutionGenome recreate(
        List<RewriteGene> replacementRewrites,
        List<FeatureWeight> replacementFeatures,
        GuardPolicy replacementGuards,
        ResourceBudget replacementBudget
    ) {
        List<String> lineage = new ArrayList<>(seedGenomeHashes);
        lineage.add(contentHash);
        return create(
            objective,
            trainingScope,
            replacementRewrites,
            replacementFeatures,
            replacementGuards,
            replacementBudget,
            requiredCapabilities,
            lineage);
    }

    public String toCanonicalJson() {
        return render(
            objective,
            trainingScope,
            rewrites,
            rankingFeatures,
            guardPolicy,
            budget,
            requiredCapabilities,
            seedGenomeHashes,
            alphaStructuralHash,
            contentHash);
    }

    static String normalizePattern(String pattern) {
        requireText(pattern, "pattern");
        return pattern.replaceAll("\\s+", "");
    }

    static Set<String> placeholders(String value) {
        Matcher matcher = PLACEHOLDER.matcher(value == null ? "" : value);
        Set<String> result = new LinkedHashSet<>();
        while (matcher.find()) {
            result.add("?" + matcher.group(1));
        }
        return Set.copyOf(result);
    }

    static String transformPlaceholders(String value, UnaryOperator<String> transformer) {
        Matcher matcher = PLACEHOLDER.matcher(value);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String token = "?" + matcher.group(1);
            matcher.appendReplacement(output, Matcher.quoteReplacement(transformer.apply(token)));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    static void requireSha256(String value, String name) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
    }

    private static String canonicalPayload(
        Objective objective,
        TrainingScope trainingScope,
        List<RewriteGene> rewrites,
        List<FeatureWeight> features,
        GuardPolicy guardPolicy,
        ResourceBudget budget,
        List<String> capabilities,
        List<String> seedHashes,
        String alphaHash
    ) {
        return render(
            objective,
            trainingScope,
            rewrites,
            features,
            guardPolicy,
            budget,
            capabilities,
            seedHashes,
            alphaHash,
            null);
    }

    private static String render(
        Objective objective,
        TrainingScope trainingScope,
        List<RewriteGene> rewrites,
        List<FeatureWeight> features,
        GuardPolicy guardPolicy,
        ResourceBudget budget,
        List<String> capabilities,
        List<String> seedHashes,
        String alphaHash,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("objective", objective.name())
            .object("trainingScope", object -> writeTrainingScope(object, trainingScope))
            .array("rewrites", array -> rewrites.forEach(gene ->
                array.objectValue(object -> writeRewrite(object, gene))))
            .array("rankingFeatures", array -> features.forEach(feature ->
                array.objectValue(object -> object
                    .property("signal", feature.signal().name())
                    .property("weightPermille", feature.weightPermille()))))
            .object("guardPolicy", object -> writeGuardPolicy(object, guardPolicy))
            .object("budget", object -> writeBudget(object, budget))
            .stringArray("requiredCapabilities", capabilities)
            .stringArray("seedGenomeHashes", seedHashes)
            .property("alphaStructuralHash", alphaHash);
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    private static void writeTrainingScope(JsonWriter json, TrainingScope scope) {
        json.property("sourceSplit", scope.sourceSplit().name())
            .property("corpusHash", scope.corpusHash())
            .property("familyPartitionHash", scope.familyPartitionHash())
            .property("signaturePartitionHash", scope.signaturePartitionHash())
            .property("featureSchemaHash", scope.featureSchemaHash());
    }

    private static void writeRewrite(JsonWriter json, RewriteGene gene) {
        json.property("geneId", gene.geneId())
            .property("sourcePattern", gene.sourcePattern())
            .property("targetPattern", gene.targetPattern())
            .property("kind", gene.kind().name())
            .property("reversible", gene.reversible())
            .property("estimatedCostDelta", gene.estimatedCostDelta())
            .property("maxApplicationsPerPath", gene.maxApplicationsPerPath())
            .property("maxAstGrowth", gene.maxAstGrowth())
            .array("assumptions", array -> gene.assumptions().forEach(assumption ->
                array.objectValue(object -> object
                    .property("kind", assumption.kind().name())
                    .property("expression", assumption.expression())
                    .stringArray("symbols", assumption.symbols()))))
            .stringArray("evidenceObligations", gene.evidenceObligations().stream()
                .map(Enum::name)
                .toList());
    }

    private static void writeGuardPolicy(JsonWriter json, GuardPolicy policy) {
        json.property("rejectCycles", policy.rejectCycles())
            .property("rejectUnboundedGrowth", policy.rejectUnboundedGrowth())
            .property("requireApplicabilityChecks", policy.requireApplicabilityChecks())
            .property("enforceDuplicateSuppression", policy.enforceDuplicateSuppression())
            .property("deterministicTieBreaking", policy.deterministicTieBreaking());
    }

    private static void writeBudget(JsonWriter json, ResourceBudget value) {
        json.property("maxProgramLength", value.maxProgramLength())
            .property("maxAstNodes", value.maxAstNodes())
            .property("maxAstGrowthPerStep", value.maxAstGrowthPerStep())
            .property("maxApplicationsPerPath", value.maxApplicationsPerPath())
            .property("maxCandidatesPerState", value.maxCandidatesPerState());
    }

    private static String alphaStructuralMaterial(
        Objective objective,
        List<RewriteGene> rewrites,
        List<FeatureWeight> features,
        GuardPolicy guardPolicy,
        ResourceBudget budget,
        List<String> capabilities
    ) {
        List<String> alphaGenes = rewrites.stream()
            .map(EvolutionGenome::alphaGeneMaterial)
            .sorted()
            .toList();
        return SCHEMA
            + "\nobjective=" + objective.name()
            + "\nrewrites=" + alphaGenes
            + "\nfeatures=" + features
            + "\nguards=" + guardPolicy.canonicalMaterial()
            + "\nbudget=" + budget.canonicalMaterial()
            + "\ncapabilities=" + capabilities;
    }

    private static String alphaGeneMaterial(RewriteGene gene) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        UnaryOperator<String> alpha = token -> placeholders.computeIfAbsent(
            token,
            ignored -> "?P" + placeholders.size());
        String source = transformPlaceholders(gene.sourcePattern(), alpha);
        String target = transformPlaceholders(gene.targetPattern(), alpha);
        List<String> assumptions = gene.assumptions().stream()
            .map(item -> item.kind().name()
                + ":" + transformPlaceholders(item.expression(), alpha)
                + ":" + item.symbols().stream()
                    .map(symbol -> transformPlaceholders(symbol, alpha))
                    .sorted()
                    .toList())
            .sorted()
            .toList();
        return source + "->" + target
            + "|" + gene.kind().name()
            + "|rev=" + gene.reversible()
            + "|cost=" + gene.estimatedCostDelta()
            + "|applications=" + gene.maxApplicationsPerPath()
            + "|growth=" + gene.maxAstGrowth()
            + "|assumptions=" + assumptions
            + "|obligations=" + gene.evidenceObligations();
    }

    private static List<RewriteGene> normalizeRewrites(List<RewriteGene> values) {
        List<RewriteGene> result = values == null
            ? List.of()
            : values.stream().filter(Objects::nonNull)
                .sorted(Comparator.comparing(RewriteGene::geneId))
                .toList();
        long distinct = result.stream().map(RewriteGene::geneId).distinct().count();
        if (distinct != result.size()) {
            throw new IllegalArgumentException("rewrite gene IDs must be unique");
        }
        return result;
    }

    private static List<FeatureWeight> normalizeFeatures(List<FeatureWeight> values) {
        List<FeatureWeight> result = values == null
            ? List.of()
            : values.stream().filter(Objects::nonNull)
                .sorted(Comparator.comparing(item -> item.signal().name()))
                .toList();
        long distinct = result.stream().map(FeatureWeight::signal).distinct().count();
        if (distinct != result.size()) {
            throw new IllegalArgumentException("ranking feature signals must be unique");
        }
        return result;
    }

    private static List<String> normalizeCapabilities(List<String> values) {
        TreeSet<String> result = new TreeSet<>();
        if (values != null) {
            for (String value : values) {
                requireText(value, "required capability");
                String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
                if (!CAPABILITY.matcher(normalized).matches()) {
                    throw new IllegalArgumentException("invalid capability: " + value);
                }
                result.add(normalized);
            }
        }
        return List.copyOf(result);
    }

    private static List<String> normalizeHashes(List<String> values, String name) {
        TreeSet<String> result = new TreeSet<>();
        if (values != null) {
            for (String value : values) {
                requireSha256(value, name);
                result.add(value);
            }
        }
        return List.copyOf(result);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public enum Objective {
        OPEN_TARGET_OPERATOR,
        TARGET_DIRECTED_POLICY
    }

    public enum SourceSplit {
        TRAIN
    }

    public enum FitnessSignal {
        UNSEEN_TRAIN_CASES_SOLVED(false, 1),
        PROVISIONAL_PATH_REDUCTION(false, 1),
        OPEN_TARGET_SUPPORT_DIVERSITY(false, 1),
        STRUCTURAL_NOVELTY(false, 1),
        ASSUMPTION_SIMPLICITY(false, 1),
        PROOF_COST(false, -1),
        RUNTIME_COST(false, -1),
        CANDIDATE_COMPLEXITY(false, -1),
        COUNTEREXAMPLE_RISK(false, -1),
        REGRESSION_RISK(false, -1),
        INCOMPLETE_EVIDENCE(false, -1),
        DECLARED_TARGET_DISTANCE(true, -1),
        DECLARED_GOAL_MATCH(true, 1);

        private final boolean targetDirected;
        private final int expectedSign;

        FitnessSignal(boolean targetDirected, int expectedSign) {
            this.targetDirected = targetDirected;
            this.expectedSign = expectedSign;
        }

        public boolean targetDirected() {
            return targetDirected;
        }

        public int expectedSign() {
            return expectedSign;
        }
    }

    public enum EvidenceObligation {
        SEMANTIC_VALIDATION,
        COUNTEREXAMPLE_SEARCH,
        PROOF_OR_CERTIFICATE,
        NOVELTY_REVIEW,
        HOLDOUT_EVALUATION
    }

    public record TrainingScope(
        SourceSplit sourceSplit,
        String corpusHash,
        String familyPartitionHash,
        String signaturePartitionHash,
        String featureSchemaHash
    ) {
        public TrainingScope {
            if (sourceSplit != SourceSplit.TRAIN) {
                throw new IllegalArgumentException("genome formation is restricted to TRAIN");
            }
            requireSha256(corpusHash, "corpusHash");
            requireSha256(familyPartitionHash, "familyPartitionHash");
            requireSha256(signaturePartitionHash, "signaturePartitionHash");
            requireSha256(featureSchemaHash, "featureSchemaHash");
        }
    }

    public record AssumptionTemplate(
        Assumption.Kind kind,
        String expression,
        List<String> symbols
    ) {
        public AssumptionTemplate {
            Objects.requireNonNull(kind, "kind");
            requireText(expression, "assumption expression");
            expression = expression.trim().replaceAll("\\s+", " ");
            symbols = symbols == null
                ? List.of()
                : symbols.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .distinct()
                    .sorted()
                    .toList();
        }

        String canonicalMaterial() {
            return kind.name() + "|" + expression + "|" + symbols;
        }
    }

    public record RewriteGene(
        String geneId,
        String sourcePattern,
        String targetPattern,
        RewriteKind kind,
        boolean reversible,
        int estimatedCostDelta,
        int maxApplicationsPerPath,
        int maxAstGrowth,
        List<AssumptionTemplate> assumptions,
        List<EvidenceObligation> evidenceObligations
    ) {
        public RewriteGene {
            requireText(geneId, "geneId");
            geneId = geneId.trim().toLowerCase(java.util.Locale.ROOT);
            if (!GENE_ID.matcher(geneId).matches()) {
                throw new IllegalArgumentException("invalid geneId: " + geneId);
            }
            sourcePattern = normalizePattern(sourcePattern);
            targetPattern = normalizePattern(targetPattern);
            Objects.requireNonNull(kind, "kind");
            if (estimatedCostDelta < -1000 || estimatedCostDelta > 1000) {
                throw new IllegalArgumentException("estimatedCostDelta must be in [-1000,1000]");
            }
            if (maxApplicationsPerPath < 1 || maxApplicationsPerPath > 1024) {
                throw new IllegalArgumentException("maxApplicationsPerPath must be in [1,1024]");
            }
            if (maxAstGrowth < 0 || maxAstGrowth > 1024) {
                throw new IllegalArgumentException("maxAstGrowth must be in [0,1024]");
            }
            assumptions = assumptions == null
                ? List.of()
                : assumptions.stream().filter(Objects::nonNull)
                    .distinct()
                    .sorted(Comparator.comparing(AssumptionTemplate::canonicalMaterial))
                    .toList();
            evidenceObligations = evidenceObligations == null
                ? List.of()
                : evidenceObligations.stream().filter(Objects::nonNull)
                    .distinct()
                    .sorted(Comparator.comparing(Enum::name))
                    .toList();
        }

        public RewriteGene withPatterns(String source, String target) {
            return new RewriteGene(
                geneId,
                source,
                target,
                kind,
                reversible,
                estimatedCostDelta,
                maxApplicationsPerPath,
                maxAstGrowth,
                assumptions,
                evidenceObligations);
        }

        public RewriteGene withAssumptions(List<AssumptionTemplate> replacements) {
            return new RewriteGene(
                geneId,
                sourcePattern,
                targetPattern,
                kind,
                reversible,
                estimatedCostDelta,
                maxApplicationsPerPath,
                maxAstGrowth,
                replacements,
                evidenceObligations);
        }
    }

    public record FeatureWeight(FitnessSignal signal, int weightPermille) {
        public FeatureWeight {
            Objects.requireNonNull(signal, "signal");
            if (weightPermille < -1000 || weightPermille > 1000 || weightPermille == 0) {
                throw new IllegalArgumentException("weightPermille must be non-zero in [-1000,1000]");
            }
        }
    }

    public record GuardPolicy(
        boolean rejectCycles,
        boolean rejectUnboundedGrowth,
        boolean requireApplicabilityChecks,
        boolean enforceDuplicateSuppression,
        boolean deterministicTieBreaking
    ) {
        public static GuardPolicy strictDefault() {
            return new GuardPolicy(true, true, true, true, true);
        }

        String canonicalMaterial() {
            return rejectCycles + "|" + rejectUnboundedGrowth + "|"
                + requireApplicabilityChecks + "|" + enforceDuplicateSuppression
                + "|" + deterministicTieBreaking;
        }
    }

    public record ResourceBudget(
        int maxProgramLength,
        int maxAstNodes,
        int maxAstGrowthPerStep,
        int maxApplicationsPerPath,
        int maxCandidatesPerState
    ) {
        public ResourceBudget {
            requireRange(maxProgramLength, 1, 1024, "maxProgramLength");
            requireRange(maxAstNodes, 1, 100_000, "maxAstNodes");
            requireRange(maxAstGrowthPerStep, 0, 1024, "maxAstGrowthPerStep");
            requireRange(maxApplicationsPerPath, 1, 100_000, "maxApplicationsPerPath");
            requireRange(maxCandidatesPerState, 1, 100_000, "maxCandidatesPerState");
        }

        public static ResourceBudget conservativeDefault() {
            return new ResourceBudget(16, 256, 12, 32, 80);
        }

        String canonicalMaterial() {
            return maxProgramLength + "|" + maxAstNodes + "|"
                + maxAstGrowthPerStep + "|" + maxApplicationsPerPath
                + "|" + maxCandidatesPerState;
        }
    }

    private static void requireRange(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                name + " must be in [" + minimum + "," + maximum + "]");
        }
    }
}
