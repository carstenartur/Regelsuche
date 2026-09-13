package de.regelsuche.runtime;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.evolution.EvolutionGenomeCodec;
import de.regelsuche.evolution.EvolutionRewriteProgramCandidate;
import de.regelsuche.evolution.EvolutionRewriteProgramPlanCodec;
import de.regelsuche.evolution.LearnedPatternRuleAuthorizationService;
import de.regelsuche.evolution.LearnedRewriteProgramAuthorizationReceipt;
import de.regelsuche.evolution.LearnedRewriteProgramAuthorizationService;
import de.regelsuche.evolution.LearnedRewriteProgramReplayEvidence;
import de.regelsuche.transform.RewriteRule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reloadable server-side authority for retained learned executors. */
public final class LearnedRuntimeAuthority {
    private static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public static final String MANIFEST_PROPERTY =
        "regelsuche.runtime.learnedManifest";
    public static final String MANIFEST_SCHEMA =
        "regelsuche.learned-runtime-authority-manifest/v1";
    public static final String SNAPSHOT_SCHEMA =
        "regelsuche.learned-runtime-authority-snapshot/v1";
    /**
     * One unit is one complete pattern or program authorization replay batch.
     * It is a logical product budget, not elapsed time, CPU, or file-I/O work.
     */
    public static final String SETUP_WORK_REVISION =
        "regelsuche.learned-runtime-authority-revalidation-work/v1";

    private final String repositoryRevision;
    private final List<PatternRoot> patternRoots;
    private final List<ProgramRoot> programRoots;
    private final Clock clock;

    public LearnedRuntimeAuthority(
        String repositoryRevision,
        List<PatternRoot> patternRoots,
        List<ProgramRoot> programRoots,
        Clock clock
    ) {
        this.repositoryRevision = requireRevision(repositoryRevision);
        this.patternRoots = List.copyOf(Objects.requireNonNull(
            patternRoots, "patternRoots")).stream()
            .sorted(Comparator.comparing(PatternRoot::id))
            .toList();
        this.programRoots = List.copyOf(Objects.requireNonNull(
            programRoots, "programRoots")).stream()
            .sorted(Comparator.comparing(ProgramRoot::id))
            .toList();
        requireDistinctConfiguration(this.patternRoots, this.programRoots);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    private LearnedRuntimeAuthority(Clock clock) {
        this.repositoryRevision = "";
        this.patternRoots = List.of();
        this.programRoots = List.of();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static LearnedRuntimeAuthority fromConfiguredProperty() {
        String configured = System.getProperty(MANIFEST_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return new LearnedRuntimeAuthority(Clock.systemUTC());
        }
        return readManifest(Path.of(configured));
    }

    public Snapshot load() {
        if (patternRoots.isEmpty() && programRoots.isEmpty()) {
            return new Snapshot(
                List.of(),
                List.of(),
                Map.of(
                    "schema", SNAPSHOT_SCHEMA,
                    "repositoryRevision", repositoryRevision,
                    "setupWorkRevision", SETUP_WORK_REVISION,
                    "patterns", List.of(),
                    "programs", List.of()),
                0L);
        }
        Instant asOf = clock.instant();
        List<RewriteRule> rules = new ArrayList<>();
        List<Map<String, Object>> patternIdentity = new ArrayList<>();
        Map<String, LearnedPatternRuleAuthorizationService.Authorization>
            patternsById = new LinkedHashMap<>();
        Set<String> runtimeIds = new HashSet<>();
        try {
            for (PatternRoot root : patternRoots) {
                LearnedPatternRuleAuthorizationService.Authorization loaded =
                    loadPattern(root, asOf);
                if (patternsById.put(root.id(), loaded) != null) {
                    throw new IllegalArgumentException(
                        "duplicate pattern authority id: " + root.id());
                }
                RewriteRule exactRule = loaded.promotion().rule();
                if (!runtimeIds.add(exactRule.id())) {
                    throw new IllegalArgumentException(
                        "duplicate promoted runtime rule id: "
                            + exactRule.id());
                }
                rules.add(exactRule);
                patternIdentity.add(patternIdentity(root.id(), loaded));
            }

            List<SafeRuntimeAdapter.DirectSource> sources = new ArrayList<>();
            List<Map<String, Object>> programIdentity = new ArrayList<>();
            for (ProgramRoot root : programRoots) {
                LearnedRewriteProgramAuthorizationService.Authorization loaded =
                    loadProgram(root, patternsById, asOf);
                if (!runtimeIds.add(root.id())) {
                    throw new IllegalArgumentException(
                        "duplicate learned runtime source id: " + root.id());
                }
                sources.add(new SafeRuntimeAdapter.DirectSource(
                    root.id(),
                    loaded.receipt().contentHash(),
                    loaded.compiledProgram().engine()));
                programIdentity.add(programIdentity(root.id(), loaded));
            }
            long setupWorkUnits = Math.addExact(
                (long) patternRoots.size(), (long) programRoots.size());
            return new Snapshot(
                rules,
                sources,
                Map.of(
                    "schema", SNAPSHOT_SCHEMA,
                    "repositoryRevision", repositoryRevision,
                    "setupWorkRevision", SETUP_WORK_REVISION,
                    "patterns", List.copyOf(patternIdentity),
                    "programs", List.copyOf(programIdentity)),
                setupWorkUnits);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                "cannot reload retained learned runtime authority", exception);
        }
    }

    public record PatternRoot(String id, Path root) {
        public PatternRoot {
            id = requireText(id, "pattern authority id");
            root = normalize(root, "pattern root");
        }
    }

    public record ProgramRoot(
        String id,
        Path root,
        List<String> leafAuthorityIds
    ) {
        public ProgramRoot {
            id = requireText(id, "program authority id");
            root = normalize(root, "program root");
            leafAuthorityIds = List.copyOf(Objects.requireNonNull(
                leafAuthorityIds, "leafAuthorityIds")).stream()
                .map(value -> requireText(value, "leaf authority id"))
                .sorted()
                .toList();
            if (leafAuthorityIds.isEmpty()) {
                throw new IllegalArgumentException(
                    "program authority requires leaf authorities");
            }
            if (new HashSet<>(leafAuthorityIds).size()
                    != leafAuthorityIds.size()) {
                throw new IllegalArgumentException(
                    "program authority has duplicate leaf authorities");
            }
        }
    }

    /** Immutable result of one fresh retained-authority revalidation pass. */
    public record Snapshot(
        List<RewriteRule> rules,
        List<SafeRuntimeAdapter.DirectSource> sources,
        Map<String, Object> identity,
        long setupWorkUnits
    ) {
        public Snapshot {
            rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
            sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
            identity = Map.copyOf(Objects.requireNonNull(identity, "identity"));
            if (setupWorkUnits < 0) {
                throw new IllegalArgumentException(
                    "setupWorkUnits must not be negative");
            }
        }
    }

    private static String requireRevision(String revision) {
        if (revision == null || !revision.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException(
                "repositoryRevision must be a lowercase 40-hex revision");
        }
        return revision;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Path normalize(Path value, String name) {
        return Objects.requireNonNull(value, name)
            .toAbsolutePath()
            .normalize();
    }

    private static LearnedRuntimeAuthority readManifest(Path configured) {
        Path manifest = normalize(configured, "learned authority manifest");
        if (!Files.isRegularFile(manifest) || Files.isSymbolicLink(manifest)) {
            throw new IllegalArgumentException(
                "learned authority manifest must be a regular non-symlink file: "
                    + manifest);
        }
        try {
            ObjectNode root = object(
                JSON.readTree(Files.readString(manifest, StandardCharsets.UTF_8)),
                "manifest");
            exact(root, Set.of(
                "schema", "repositoryRevision", "patterns", "programs"),
                "manifest");
            if (!MANIFEST_SCHEMA.equals(text(root, "schema", "manifest"))) {
                throw new IllegalArgumentException(
                    "unsupported learned authority manifest schema");
            }
            Path base = Objects.requireNonNullElse(
                manifest.getParent(), Path.of(".").toAbsolutePath().normalize());
            List<PatternRoot> patterns = new ArrayList<>();
            for (JsonNode value : array(root, "patterns", "manifest")) {
                ObjectNode pattern = object(value, "pattern authority");
                exact(pattern, Set.of("id", "root"), "pattern authority");
                patterns.add(new PatternRoot(
                    text(pattern, "id", "pattern authority"),
                    resolve(base, text(pattern, "root", "pattern authority"))));
            }
            List<ProgramRoot> programs = new ArrayList<>();
            for (JsonNode value : array(root, "programs", "manifest")) {
                ObjectNode program = object(value, "program authority");
                exact(program, Set.of("id", "root", "leafAuthorityIds"),
                    "program authority");
                programs.add(new ProgramRoot(
                    text(program, "id", "program authority"),
                    resolve(base, text(program, "root", "program authority")),
                    strings(program, "leafAuthorityIds", "program authority")));
            }
            return new LearnedRuntimeAuthority(
                text(root, "repositoryRevision", "manifest"),
                patterns,
                programs,
                Clock.systemUTC());
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                "cannot read learned authority manifest", exception);
        }
    }

    private static void requireDistinctConfiguration(
        List<PatternRoot> patterns,
        List<ProgramRoot> programs
    ) {
        Set<String> patternIds = new HashSet<>();
        for (PatternRoot pattern : patterns) {
            if (!patternIds.add(pattern.id())) {
                throw new IllegalArgumentException(
                    "duplicate pattern authority id: " + pattern.id());
            }
        }
        Set<String> programIds = new HashSet<>();
        for (ProgramRoot program : programs) {
            if (!programIds.add(program.id())) {
                throw new IllegalArgumentException(
                    "duplicate program authority id: " + program.id());
            }
        }
    }

    private static Path resolve(Path base, String configured) {
        Path path = Path.of(configured);
        return normalize(path.isAbsolute() ? path : base.resolve(path),
            "authority root");
    }

    private static ObjectNode object(JsonNode value, String context) {
        if (!(value instanceof ObjectNode object)) {
            throw new IllegalArgumentException(context + " must be an object");
        }
        return object;
    }

    private static List<JsonNode> array(
        ObjectNode parent,
        String field,
        String context
    ) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException(
                context + "." + field + " must be an array");
        }
        List<JsonNode> result = new ArrayList<>();
        value.forEach(result::add);
        return List.copyOf(result);
    }

    private static List<String> strings(
        ObjectNode parent,
        String field,
        String context
    ) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : array(parent, field, context)) {
            if (!value.isTextual() || value.textValue().isBlank()) {
                throw new IllegalArgumentException(
                    context + "." + field + " must contain non-blank text");
            }
            result.add(value.textValue());
        }
        return List.copyOf(result);
    }

    private static String text(
        ObjectNode parent,
        String field,
        String context
    ) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(
                context + "." + field + " must be non-blank text");
        }
        return value.textValue();
    }

    private static void exact(
        ObjectNode value,
        Set<String> expected,
        String context
    ) {
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(
                context + " fields must be exactly " + expected);
        }
    }

    private LearnedPatternRuleAuthorizationService.Authorization loadPattern(
        PatternRoot configured,
        Instant asOf
    ) throws IOException {
        Path root = requireRoot(configured.root(), "pattern authority root");
        var genome = new EvolutionGenomeCodec().read(
            requiredFile(root, "genome.json", "pattern genome"));
        var evidence = new LearnedPatternRuleAuthorizationService.EvidenceFiles(
            requiredFile(root, "authorization-bundle.json",
                "pattern authorization bundle"),
            requiredFile(root, "split-manifest.json", "pattern split manifest"),
            requiredFile(root, "validation-selection.json",
                "pattern validation selection"),
            requiredFile(root, "final-test-evaluation.json",
                "pattern final-test evaluation"),
            requiredFile(root, "counterexample-evidence.json",
                "pattern counterexample evidence"));
        var authorization = new LearnedPatternRuleAuthorizationService()
            .verifyAuthorization(
                genome,
                configured.id(),
                repositoryRevision,
                evidence,
                requiredFile(root, "authorization-receipt.json",
                    "pattern authorization receipt"),
                asOf);
        String retainedSchemaHash =
            authorization.receipt().applicabilitySchemaHash();
        String actualSchemaHash =
            authorization.promotion().applicabilitySchema().contentHash();
        if (!retainedSchemaHash.equals(actualSchemaHash)) {
            throw new IllegalArgumentException(
                "pattern applicability schema differs from retained receipt: "
                    + configured.id());
        }
        return authorization;
    }

    private LearnedRewriteProgramAuthorizationService.Authorization
            loadProgram(
                ProgramRoot configured,
                Map<String,
                    LearnedPatternRuleAuthorizationService.Authorization>
                    patternsById,
                Instant asOf
            ) throws IOException {
        Path root = requireRoot(configured.root(), "program authority root");
        var genome = new EvolutionGenomeCodec().read(
            requiredFile(root, "genome.json", "program genome"));
        var plan = new EvolutionRewriteProgramPlanCodec().read(
            requiredFile(root, "program-plan.json", "program plan"));
        var candidate = EvolutionRewriteProgramCandidate.create(genome, plan);
        if (!Set.copyOf(configured.leafAuthorityIds()).equals(
                Set.copyOf(plan.referencedGeneIds()))) {
            throw new IllegalArgumentException(
                "configured program leaves differ from program topology: "
                    + configured.id());
        }
        List<LearnedPatternRuleAuthorizationService.Authorization> leaves =
            configured.leafAuthorityIds().stream()
                .map(id -> {
                    var leaf = patternsById.get(id);
                    if (leaf == null) {
                        throw new IllegalArgumentException(
                            "program references unconfigured pattern authority: "
                                + id);
                    }
                    return leaf;
                })
                .toList();
        LearnedRewriteProgramReplayEvidence replay =
            LearnedRewriteProgramReplayEvidence.fromCanonicalJson(
                readRegularFile(requiredFile(
                    root,
                    "program-replay-evidence.json",
                    "program replay evidence")));
        LearnedRewriteProgramAuthorizationReceipt receipt =
            LearnedRewriteProgramAuthorizationReceipt.fromCanonicalJson(
                readRegularFile(requiredFile(
                    root,
                    "program-authorization-receipt.json",
                    "program authorization receipt")));
        return new LearnedRewriteProgramAuthorizationService()
            .replayStoredAuthorization(
                candidate,
                leaves,
                replay,
                receipt,
                repositoryRevision,
                asOf);
    }

    private static Map<String, Object> patternIdentity(
        String authorityId,
        LearnedPatternRuleAuthorizationService.Authorization authorization
    ) {
        var receipt = authorization.receipt();
        return Map.copyOf(RuntimeJson.fields(
            "authorityId", authorityId,
            "receiptHash", receipt.contentHash(),
            "validUntil", receipt.validUntil().toString(),
            "repositoryRevision", receipt.repositoryRevision(),
            "genomeHash", receipt.genomeHash(),
            "geneId", receipt.geneId(),
            "promotedRuleId", receipt.promotedRuleId(),
            "promotedRuleHash", receipt.promotedRuleHash(),
            "applicabilitySchemaHash", receipt.applicabilitySchemaHash()));
    }

    private static Map<String, Object> programIdentity(
        String authorityId,
        LearnedRewriteProgramAuthorizationService.Authorization authorization
    ) {
        var receipt = authorization.receipt();
        return Map.copyOf(RuntimeJson.fields(
            "authorityId", authorityId,
            "receiptHash", receipt.contentHash(),
            "validUntil", receipt.validUntil().toString(),
            "repositoryRevision", receipt.repositoryRevision(),
            "candidateHash", receipt.candidateHash(),
            "genomeHash", receipt.genomeHash(),
            "genomeAlphaStructuralHash", receipt.genomeAlphaStructuralHash(),
            "topologyHash", receipt.planHash(),
            "topologyAlphaStructuralHash", receipt.planAlphaStructuralHash(),
            "replayEvidenceHash", receipt.replayEvidenceHash(),
            "leafAuthorizationHashes", receipt.leafAuthorizationHashes(),
            "leafApplicabilitySchemaHashes",
                receipt.leafApplicabilitySchemaHashes(),
            "workRevisions", receipt.workRevisions()));
    }

    private static Path requireRoot(Path root, String name) {
        Path normalized = normalize(root, name);
        if (!Files.isDirectory(normalized) || Files.isSymbolicLink(normalized)) {
            throw new IllegalArgumentException(
                name + " must be a non-symlink directory: " + normalized);
        }
        return normalized;
    }

    private static Path requiredFile(Path root, String filename, String name) {
        Path file = root.resolve(filename).normalize();
        if (!file.getParent().equals(root)
                || !Files.isRegularFile(file)
                || Files.isSymbolicLink(file)) {
            throw new IllegalArgumentException(
                name + " must be a regular non-symlink file: " + file);
        }
        return file;
    }

    private static String readRegularFile(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
