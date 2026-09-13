package de.regelsuche.benchmark.amplification;

import de.regelsuche.knowledge.KnowledgePackRegistry;
import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.knowledge.RuleProfile;
import de.regelsuche.math.sympy.SymPyNamedOperationEngine;
import de.regelsuche.math.sympy.SymPyNamedOperationEngine.Operation;
import de.regelsuche.search.reachability.AblatableRulePreparationRunner;
import de.regelsuche.search.reachability.AblatableRulePreparationRunner.Profile;
import de.regelsuche.search.reachability.AblatableRulePreparationRunner.Run;
import de.regelsuche.search.reachability.AblatableRulePreparationRunner.Source;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import static de.regelsuche.search.reachability.AmplificationJson.*;

/** Checkout-owned plan -> complete candidate freeze -> post-freeze qualification. */
public final class AblatableRuleAmplificationExperiment {
    public static final String PLAN = "regelsuche.amplification-plan/v1";
    public static final String FREEZE = "regelsuche.amplification-candidate-freeze/v1";
    public static final String REPORT = "regelsuche.amplification-qualification/v1";
    private static final List<String> PRINCIPALS = List.of("sympy.trig.pythagorean", "sympy.poly.factor.diff_squares",
        "sympy.rational.partial_fraction.telescoping");

    private AblatableRuleAmplificationExperiment() { }
    @FunctionalInterface public interface NamedFormation { SymPyNamedOperationEngine.Outcome execute(Operation operation, Source source); }
    public record Qualification(String caseId, String family, String principalId, String reference, String kind) { }
    public record NativeObservation(int sourceIndex, Run run) { }
    public record ExternalObservation(int sourceIndex, Operation operation, SymPyNamedOperationEngine.Outcome outcome) { }

    public static final class Plan {
        private final String revision, sourceHash, qualificationHash, canonicalJson;
        private Plan(String revision, String sourceHash, String qualificationHash) {
            this.revision = revision; this.sourceHash = sourceHash; this.qualificationHash = qualificationHash;
            var nativeRunner = runner(revision);
            canonicalJson = canonical(fields("schema", PLAN, "repositoryRevision", revision,
                "sourceHash", sourceHash, "qualificationHash", qualificationHash,
                "nativeProfiles", Arrays.stream(Profile.values()).map(nativeRunner::configuration).toList(),
                "externalOperations", Arrays.stream(Operation.values()).map(SymPyNamedOperationEngine::configuration).toList(),
                "selection", "FIRST_VERIFIED_PER_PRINCIPAL_CHEAPEST_STAGE_AST_PREORDER",
                "qualification", "EXACT_FROZEN_REFERENCE_FORM_AFTER_ALL_CANDIDATES",
                "historicalPilot", "sympy-three-family-safe-preparation-matrix-v2_UNCHANGED",
                "comparativeMatchedWork", "BLOCKED_UNTIL_ALL_REQUIRED_INTERNAL_WORK_IS_MEASURED",
                "reproduction", "TWO_DISTINCT_CLEAN_HOSTS_AND_ONE_PINNED_CONTAINER_REQUIRE_EQUAL_CANONICAL_BYTES"));
        }
        public String canonicalJson() { return canonicalJson; }
        public String contentHash() { return bytesHash(canonicalJson); }
    }

    public static final class Frozen {
        private final Plan plan;
        private final List<Source> sources;
        private final List<NativeObservation> nativeRuns;
        private final List<ExternalObservation> externalRuns;
        private final String canonicalJson;
        private Frozen(Plan plan, List<Source> sources, List<NativeObservation> nativeRuns, List<ExternalObservation> externalRuns) {
            this.plan = plan; this.sources = List.copyOf(sources); this.nativeRuns = List.copyOf(nativeRuns); this.externalRuns = List.copyOf(externalRuns);
            if (nativeRuns.size() != sources.size() * Profile.values().length || externalRuns.size() != sources.size() * Operation.values().length)
                throw new IllegalArgumentException("candidate batch is incomplete");
            canonicalJson = canonical(fields("schema", FREEZE, "planHash", plan.contentHash(), "sourceHash", plan.sourceHash,
                "qualificationHash", plan.qualificationHash, "qualificationExposure", "HASH_ONLY_NOT_OPENED",
                "status", "ALL_PLANNED_CANDIDATE_ROWS_FROZEN", "native", nativeRuns, "external", externalRuns));
        }
        public List<NativeObservation> nativeRuns() { return nativeRuns; }
        public String canonicalJson() { return canonicalJson; }
        public String contentHash() { return bytesHash(canonicalJson); }
    }

    public static Plan plan(String revision, List<Source> sources, byte[] sealedQualification) {
        if (sources.isEmpty() || sources.size() > 64 || sealedQualification.length > 1_000_000)
            throw new IllegalArgumentException("bounded nonempty plan inputs required");
        // The qualification payload is only hashed here. Formation receives no labels or expected outputs.
        return new Plan(revision, hashBytes(sourceBytes(sources)), hashBytes(sealedQualification));
    }

    public static Frozen freeze(Plan plan, List<Source> sources, NamedFormation external) {
        if (!plan.sourceHash.equals(hashBytes(sourceBytes(sources)))) throw new IllegalArgumentException("source corpus differs from plan");
        var nativeRunner = runner(plan.revision);
        var nativeRows = new ArrayList<NativeObservation>();
        var externalRows = new ArrayList<ExternalObservation>();
        for (int index = 0; index < sources.size(); index++) {
            Source source = sources.get(index);
            for (Profile profile : Profile.values()) nativeRows.add(new NativeObservation(index, nativeRunner.analyze(profile, source)));
            for (Operation operation : Operation.values()) {
                var outcome = external.execute(operation, source);
                if (!SymPyNamedOperationEngine.configurationHash(operation).equals(outcome.configurationHash())
                    || !bytesHash(outcome.canonicalJson()).equals(outcome.contentHash()))
                    throw new IllegalArgumentException("external operation/configuration observation is unbound");
                var observed = read(outcome.canonicalJson());
                var input = object(observed.get("input"));
                if (!outcome.status().equals(observed.get("status")) || !outcome.output().equals(observed.get("output"))
                    || !source.expression().equals(input.get("sourceExpression")) || !source.assumptions().equals(input.get("declaredAssumptions"))
                    || !operation.name().equals(input.get("operation")) || !hash(input).equals(observed.get("inputHash")))
                    throw new IllegalArgumentException("external source/outcome projection differs from retained input");
                externalRows.add(new ExternalObservation(index, operation, outcome));
            }
        }
        return new Frozen(plan, sources, nativeRows, externalRows);
    }

    /** Opens qualification only after the complete freeze and fresh independent native replay. */
    public static String qualify(Plan plan, Frozen frozen, Supplier<byte[]> sealedQualification) {
        if (!plan.contentHash().equals(frozen.plan.contentHash())) throw new IllegalArgumentException("freeze belongs to another plan");
        var nativeRunner = runner(plan.revision);
        var verifications = new ArrayList<Object>();
        int invalidReplays = 0;
        for (var observation : frozen.nativeRuns) {
            var retained = observation.run();
            Run replay = nativeRunner.analyze(retained.profile(), retained.source());
            boolean valid = retained.equals(replay);
            if (!valid) invalidReplays++;
            verifications.add(fields("sourceIndex", observation.sourceIndex(), "profile", retained.profile(), "verified", valid,
                "replayHash", replay.contentHash(), "verificationWork", read(replay.canonicalJson()).get("work"),
                "verificationExactPreparationWork", read(replay.canonicalJson()).get("exactPreparationWork")));
        }
        byte[] opened = sealedQualification.get();
        if (!plan.qualificationHash.equals(hashBytes(opened))) throw new IllegalArgumentException("sealed qualification differs from plan");
        var qualification = readQualification(opened);
        if (qualification.size() != frozen.sources.size()) throw new IllegalArgumentException("qualification cardinality differs");
        var rows = new ArrayList<Object>();
        var coverage = new TreeMap<String, Long>();
        var byFamily = new TreeMap<String, Map<String, Long>>();
        var statuses = new TreeMap<String, Long>();
        var reuse = new TreeMap<String, java.util.Set<String>>();
        var profileWork = new TreeMap<String, Map<String, Long>>();
        var positiveCasesByFamily = new TreeMap<String, Long>();
        qualification.stream().filter(label -> !label.reference().isEmpty())
            .forEach(label -> positiveCasesByFamily.merge(label.family(), 1L, Long::sum));
        for (Profile profile : Profile.values()) {
            coverage.put(profile.name(), 0L);
            profileWork.put(profile.name(), new TreeMap<>(Map.of("logicalContractUnits", 0L, "exactPreparationContractUnits", 0L)));
            positiveCasesByFamily.keySet().forEach(family -> byFamily.computeIfAbsent(family, ignored -> new TreeMap<>()).put(profile.name(), 0L));
        }
        int falsePositives = 0;
        for (var observation : frozen.nativeRuns) {
            var run = observation.run(); var label = qualification.get(observation.sourceIndex());
            var candidate = run.candidates().stream().filter(value -> value.principalId().equals(label.principalId())).findFirst();
            boolean negative = label.reference().isEmpty();
            boolean reached = !negative && candidate.isPresent() && sameForm(candidate.get().output(), label.reference());
            boolean falsePositive = negative && candidate.isPresent();
            if (falsePositive) falsePositives++;
            if (reached) {
                coverage.merge(run.profile().name(), 1L, Long::sum);
                byFamily.computeIfAbsent(label.family(), ignored -> new TreeMap<>()).merge(run.profile().name(), 1L, Long::sum);
                java.util.stream.Stream.concat(candidate.orElseThrow().primitiveRuleIds().stream().filter(id -> !id.equals(label.principalId())),
                    candidate.orElseThrow().exactPreparationStepIds().stream())
                    .forEach(id -> reuse.computeIfAbsent(id, ignored -> new java.util.TreeSet<>()).add(label.principalId()));
            }
            var runObservation = read(run.canonicalJson());
            var work = object(runObservation.get("work"));
            var exactWork = object(runObservation.get("exactPreparationWork"));
            profileWork.get(run.profile().name()).merge("logicalContractUnits", ((Number) work.get("chargedUnits")).longValue(), Math::addExact);
            profileWork.get(run.profile().name()).merge("exactPreparationContractUnits", ((Number) exactWork.get("chargedUnits")).longValue(), Math::addExact);
            String status = falsePositive ? "FALSE_POSITIVE" : reached ? "REFERENCE_REACHED" : negative ? "NEGATIVE_NO_CANDIDATE" : "REFERENCE_NOT_REACHED";
            // Budget/failure observations remain visible even when they cannot supply a candidate.
            String unavailable = nativeUnavailability(runObservation.get("stages"));
            if (!unavailable.isEmpty()) status = unavailable;
            statuses.merge(status, 1L, Long::sum);
            rows.add(fields("sourceIndex", observation.sourceIndex(), "caseId", label.caseId(), "family", label.family(), "kind", label.kind(), "principalId", label.principalId(),
                "profile", run.profile(), "runHash", run.contentHash(), "status", status, "referenceReached", reached,
                "candidateCertificate", candidate.map(AblatableRulePreparationRunner.Candidate::certificateHash).orElse(""),
                "preparationDepth", candidate.map(AblatableRulePreparationRunner.Candidate::preparationDepth).orElse(0),
                "primitiveRewrites", candidate.map(value -> value.primitiveRuleIds().size()).orElse(0),
                "exactPreparationSteps", candidate.map(value -> value.exactPreparationStepIds().size()).orElse(0),
                "astGrowth", candidate.map(value -> value.outputAstNodes() - value.sourceAstNodes()).orElse(0),
                "retainedAssumptions", candidate.map(AblatableRulePreparationRunner.Candidate::retainedAssumptions).orElse(List.of()),
                "introducedAssumptions", List.of(), "work", work, "exactPreparationWork", exactWork));
        }
        var externalRows = new ArrayList<Object>();
        for (var observation : frozen.externalRuns) {
            var label = qualification.get(observation.sourceIndex()); var outcome = observation.outcome();
            externalRows.add(fields("sourceIndex", observation.sourceIndex(), "caseId", label.caseId(), "operation", observation.operation(), "configurationHash", outcome.configurationHash(),
                "outcomeHash", outcome.contentHash(), "status", outcome.status(),
                "referenceReached", outcome.status().equals("COMPLETED") && !label.reference().isEmpty() && sameForm(outcome.output(), label.reference()),
                "primitiveProof", "UNAVAILABLE", "internalWork", "UNAVAILABLE", "selection", "PREDECLARED_OPERATION_OUTPUT_ONLY"));
        }
        var increments = new TreeMap<String, Long>();
        var incrementalWork = new TreeMap<String, Map<String, Long>>();
        long previous = 0;
        Map<String, Long> previousWork = Map.of("logicalContractUnits", 0L, "exactPreparationContractUnits", 0L);
        for (Profile profile : Profile.values()) {
            long current = coverage.get(profile.name()); increments.put(profile.name(), current - previous); previous = current;
            var currentWork = profileWork.get(profile.name());
            incrementalWork.put(profile.name(), Map.of("logicalContractUnits", currentWork.get("logicalContractUnits") - previousWork.get("logicalContractUnits"),
                "exactPreparationContractUnits", currentWork.get("exactPreparationContractUnits") - previousWork.get("exactPreparationContractUnits")));
            previousWork = currentWork;
        }
        boolean nullResult = coverage.get(Profile.SAFE_PREPARATION_PLUS_LOCAL_BRIDGE.name()).equals(coverage.get(Profile.DIRECT_ONLY.name()));
        return canonical(fields("schema", REPORT, "planHash", plan.contentHash(), "candidateFreezeHash", frozen.contentHash(),
            "qualificationHash", plan.qualificationHash, "nativeRows", rows, "externalRows", externalRows, "independentReplay", verifications,
            "coverage", coverage, "incrementalCoverage", increments, "coverageByFamily", byFamily, "preparerPrincipalReuse", reuse,
            "positiveCasesByFamily", positiveCasesByFamily, "profileWork", profileWork, "incrementalWork", incrementalWork,
            "workComparisonPolicy", "MIXED_CONTRACT_UNITS_NEVER_SUMMED_AS_MATCHED_TOTAL_WORK",
            "statuses", statuses, "falsePositives", falsePositives, "invalidReplays", invalidReplays,
            "semanticEvidence", falsePositives > 0 || invalidReplays > 0 ? "FAILED" : "PUBLIC_CONTROL_OBSERVATIONS",
            "result", nullResult ? "NULL_NO_INCREMENTAL_COVERAGE" : "SEMANTIC_COVERAGE_ONLY",
            "comparativeMatchedWorkGate", "BLOCKED_UNAVAILABLE_INTERNAL_WORK", "comparativeGainClaim", "NOT_AUTHORIZED",
            "reproduction", "REPRODUCTION_NOT_EVALUATED", "scope", "PUBLIC_DEVELOPMENT_CONTROLS_NOT_HELD_OUT_OR_FLAGSHIP"));
    }

    public static byte[] sourceBytes(List<Source> sources) { return utf8(canonical(fields("schema", "regelsuche.amplification-sources/v1", "sources", sources))); }
    public static byte[] qualificationBytes(List<Qualification> rows) { return utf8(canonical(fields("schema", "regelsuche.amplification-labels/v1", "rows", rows))); }

    /** Only recorded stage decisions carry availability; source and binding text cannot supply a status. */
    private static String nativeUnavailability(Object evidence) {
        String result = "";
        Iterable<?> children;
        if (evidence instanceof Map<?, ?> fields) {
            for (String key : List.of("status", "matchStatus", "outcome")) {
                Object status = fields.get(key);
                if ("TECHNICAL_FAILURE".equals(status)) return "TECHNICAL_FAILURE";
                if ("BUDGET_INCONCLUSIVE".equals(status) || "INCONCLUSIVE".equals(status)) result = "BUDGET_INCONCLUSIVE";
            }
            children = fields.values();
        } else if (evidence instanceof List<?> values) children = values;
        else return result;
        for (Object child : children) {
            String nested = nativeUnavailability(child);
            if (nested.equals("TECHNICAL_FAILURE")) return nested;
            if (!nested.isEmpty()) result = nested;
        }
        return result;
    }

    private static AblatableRulePreparationRunner runner(String revision) {
        var all = new KnowledgePackRegistry().enabledRules(KnowledgePackSelection.profile(RuleProfile.ALL));
        var principals = PRINCIPALS.stream().map(id -> all.stream().filter(rule -> rule.id().equals(id)).findFirst().orElseThrow())
            .map(PatternRewriteRule.class::cast).toList();
        var preparation = AstRewriteTransformationEngine.allBuiltInRules().stream().filter(rule -> rule.id().equals("ast_cancel_division_factor")).toList();
        return new AblatableRulePreparationRunner(principals, preparation, revision, AblatableRulePreparationRunner.Budget.publicControls());
    }
    private static boolean sameForm(String actual, String reference) {
        try { var canonicalizer = new ExpressionCanonicalizer(); return canonicalizer.stableHash(actual).equals(canonicalizer.stableHash(reference)); }
        catch (IllegalArgumentException unsupported) { return false; }
    }
    private static List<Qualification> readQualification(byte[] raw) {
        var root = read(new String(raw, StandardCharsets.UTF_8));
        if (!"regelsuche.amplification-labels/v1".equals(root.get("schema"))) throw new IllegalArgumentException("qualification schema differs");
        return list(root.get("rows")).stream().map(AblatableRuleAmplificationExperiment::object)
            .map(row -> new Qualification(text(row, "caseId"), text(row, "family"), text(row, "principalId"), text(row, "reference"), text(row, "kind"))).toList();
    }
    private static List<Source> readSources(byte[] raw) {
        var root = read(new String(raw, StandardCharsets.UTF_8));
        if (!"regelsuche.amplification-sources/v1".equals(root.get("schema"))) throw new IllegalArgumentException("source schema differs");
        return list(root.get("sources")).stream().map(AblatableRuleAmplificationExperiment::object)
            .map(row -> new Source(text(row, "expression"), list(row.get("assumptions")).stream().map(String.class::cast).toList())).toList();
    }

    /** Local adapters call exactly this authority; no workflow-owned quality decision. */
    public static void main(String[] args) throws IOException {
        if (args.length != 3 || !List.of("plan", "run").contains(args[0]))
            throw new IllegalArgumentException("usage: plan|run <repositoryRevision> <fresh-output-or-existing-input-directory>");
        Path directory = Path.of(args[2]).toAbsolutePath().normalize();
        if (args[0].equals("plan")) {
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("plan directory must be fresh");
            Files.createDirectories(directory);
            var corpus = AblatableRuleAmplificationCorpus.publicCorpus();
            var labels = qualificationBytes(corpus.qualification());
            var plan = plan(args[1], corpus.sources(), labels);
            writeNew(directory.resolve("sources.json"), sourceBytes(corpus.sources()));
            writeNew(directory.resolve("qualification.json"), labels);
            writeNew(directory.resolve("plan.json"), utf8(plan.canonicalJson()));
        } else {
            var planBytes = readBounded(directory.resolve("plan.json"));
            var planValues = read(new String(planBytes, StandardCharsets.UTF_8));
            var plan = new Plan(args[1], text(planValues, "sourceHash"), text(planValues, "qualificationHash"));
            if (!Arrays.equals(planBytes, utf8(plan.canonicalJson()))) throw new IllegalArgumentException("plan/profile/runtime binding differs");
            var sources = readSources(readBounded(directory.resolve("sources.json")));
            try (var sympy = new SymPyNamedOperationEngine()) {
                var frozen = freeze(plan, sources, (operation, source) -> sympy.execute(operation, source.expression(), source.assumptions()));
                Path freezeFile = directory.resolve("candidate-freeze.json");
                writeNew(freezeFile, utf8(frozen.canonicalJson()));
                // Verify the complete persisted bytes before the first qualification read.
                if (!Arrays.equals(readBounded(freezeFile), utf8(frozen.canonicalJson()))) throw new IllegalStateException("persisted freeze changed");
                String report = qualify(plan, frozen, () -> {
                    try { return readBounded(directory.resolve("qualification.json")); }
                    catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
                });
                writeNew(directory.resolve("qualification-report.json"), utf8(report));
                System.out.println("Retained public amplification observations; comparative matched-work and multi-host claims remain gated.");
            }
        }
    }
    private static byte[] readBounded(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 8_000_000)
            throw new IllegalArgumentException("required bounded regular artifact missing: " + path.getFileName());
        return Files.readAllBytes(path);
    }
    private static void writeNew(Path path, byte[] bytes) throws IOException { Files.write(path, bytes, StandardOpenOption.CREATE_NEW); }
    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String bytesHash(String text) { return hashBytes(utf8(text)); }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") private static List<Object> list(Object value) { return (List<Object>) value; }
    private static String text(Map<String, Object> root, String key) { return (String) root.get(key); }
}
