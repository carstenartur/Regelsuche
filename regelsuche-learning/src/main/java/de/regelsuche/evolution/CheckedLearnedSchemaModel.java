package de.regelsuche.evolution;

import static de.regelsuche.evolution.CheckedSchemaSupport.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.mining.TypedPatternGeneralizer;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveContext;
import de.regelsuche.search.moves.MoveProvider;
import de.regelsuche.search.moves.MoveState;
import de.regelsuche.search.moves.MoveVerifier;
import de.regelsuche.search.moves.SearchMove;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.AstRewriteTransport;
import de.regelsuche.transform.ExactTheoryEvidence;
import de.regelsuche.transform.ExecutionWork;
import de.regelsuche.transform.ExprMatcher;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationProvenance;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Immutable exact schemas extracted from actual selected paths of the existing learner.
 * Generalization proposes syntax; distinct symbolic indeterminates establish the identity.
 * Applications use checked substitution and occurrence replacement, never primitive discovery.
 * Persisted provenance is descriptive: load re-proves every theorem and binds it to the
 * caller's expected inventory. A digest, training sample, or utility score grants no authority.
 */
public final class CheckedLearnedSchemaModel {
    public static final String REVISION = "regelsuche.checked-learned-schema-model/v1";
    public static final String CHECKER_REVISION = "regelsuche.scalar-rational-polynomial-schema-checker/v1";
    public static final String DOMAIN = "TOTAL_SCALAR_RATIONAL_POLYNOMIAL;LITERAL_NONZERO_DIVISORS;NONNEGATIVE_LITERAL_POWERS;ZERO_POWER_IS_ONE";
    public static final String ARITHMETIC_PROFILE = ExactPolynomialAnalysis.REVISION
        + ";sourceChars=16384;syntaxTokens=256;exponent=32;degree=128;terms=512;coefficientBits=4096;termProducts=65536";
    private static final String INVENTORY_REVISION = EvolutionGenome.SCHEMA + ";" + AstRewriteTransport.REVISION
        + ";checked-unconditional-scalar-inventory/v1";
    private static final String APPLICATION_REVISION = "regelsuche.checked-schema-application/v1";
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();

    /** Hard limits can be tightened, never expanded by a loaded artifact. */
    public record Bounds(int maximumExpressionNodes, int maximumPatternNodes, int maximumDepth,
            int maximumCoefficientBits, int maximumExponent, int maximumExamples, int maximumPairAttempts,
            int maximumSchemas, int maximumMatchAttempts, int maximumCandidates) {
        public static Bounds defaults() { return new Bounds(512, 128, 64, 256, 32, 128, 256, 32, 256, 32); }
        public Bounds {
            int[] values = {maximumExpressionNodes, maximumPatternNodes, maximumDepth, maximumCoefficientBits,
                maximumExponent, maximumExamples, maximumPairAttempts, maximumSchemas, maximumMatchAttempts, maximumCandidates};
            int[] maxima = {512, 128, 64, 256, 32, 128, 256, 32, 256, 32};
            for (int i = 0; i < values.length; i++) if (values[i] < 1 || values[i] > maxima[i]) {
                throw new IllegalArgumentException("unsupported checked schema bounds");
            }
        }
    }

    /** A theorem capability is privately constructed only after symbolic verification. */
    public static final class Schema {
        private final String id;
        private final PatternExpr source;
        private final PatternExpr target;
        private final String proofHash;
        private final List<String> supportingObservationIds;
        private final int structuralReduction;
        private Schema(PatternExpr source, PatternExpr target, String proof, String inventorySemanticsHash,
                List<String> observations) {
            this.source = source;
            this.target = target;
            proofHash = SchematicProofPlan.hash(proof);
            id = "checked-schema:" + SchematicProofPlan.hash(inventorySemanticsHash + "\n" + proof);
            supportingObservationIds = observations.stream().distinct().sorted().toList();
            structuralReduction = patternNodes(source) - patternNodes(target);
        }
        public String id() { return id; }
        public PatternExpr source() { return source; }
        public PatternExpr target() { return target; }
        public String proofHash() { return proofHash; }
        public List<String> assumptions() { return List.of(); }
        public List<String> supportingObservationIds() { return supportingObservationIds; }
    }

    /** Negative and exhausted attempts are retained as observations, never silently promoted. */
    public record Attempt(List<String> observationIds, String status, String detail) {
        public Attempt {
            observationIds = List.copyOf(observationIds);
            if (status == null || status.isBlank() || detail == null) throw new IllegalArgumentException("invalid schema attempt");
        }
    }

    private final String inventoryHash;
    private final String inventorySemanticsHash;
    private final String originStrategyHash;
    private final Bounds bounds;
    private final List<String> requiredAssumptions;
    private final List<Schema> schemas;
    private final Map<String, Schema> byId;
    private final List<Attempt> attempts;
    private final long formationWork;
    private final long loadWork;
    private final String canonicalJson;
    private final String modelHash;
    private final MoveProvider.Descriptor descriptor;

    private CheckedLearnedSchemaModel(String inventoryHash, String originStrategyHash, Bounds bounds,
            List<String> requiredAssumptions, List<Schema> schemas, List<Attempt> attempts,
            long formationWork, long loadWork) {
        hash(inventoryHash);
        hash(originStrategyHash);
        this.inventoryHash = inventoryHash;
        inventorySemanticsHash = inventorySemanticsHash(inventoryHash);
        this.originStrategyHash = originStrategyHash;
        this.bounds = Objects.requireNonNull(bounds);
        this.requiredAssumptions = normalizedRequirements(requiredAssumptions);
        this.schemas = List.copyOf(schemas);
        var registry = new TreeMap<String, Schema>();
        for (var schema : schemas) if (registry.put(schema.id(), schema) != null) throw new IllegalArgumentException("duplicate checked schema");
        byId = Map.copyOf(registry);
        this.attempts = List.copyOf(attempts);
        if (formationWork < 0 || loadWork < 0) throw new IllegalArgumentException("negative schema work");
        this.formationWork = formationWork;
        this.loadWork = loadWork;
        canonicalJson = render();
        modelHash = SchematicProofPlan.hash(canonicalJson);
        descriptor = new MoveProvider.Descriptor("checked-learned:" + modelHash, "checked-scalar-polynomial",
            SearchMove.SourceKind.LEARNED, SearchMove.ProofStrength.VERIFIED, this.requiredAssumptions,
            new SearchMove.ValueEvidence(0, 0, schemas.stream().flatMap(schema -> schema.supportingObservationIds().stream())
                .distinct().count(), -1, 1, false, ""), inventorySemanticsHash);
    }

    public static CheckedLearnedSchemaModel learn(TraceRewriteStrategyLearner.FrozenStrategy formation) {
        return learn(formation, Bounds.defaults());
    }
    public static CheckedLearnedSchemaModel learn(TraceRewriteStrategyLearner.FrozenStrategy formation, Bounds bounds) {
        Objects.requireNonNull(formation, "formation");
        Objects.requireNonNull(bounds, "bounds");
        var work = new Work();
        var examples = new ArrayList<ObservedPair>();
        var deferred = new ArrayList<ObservedPair>();
        var attempts = new ArrayList<Attempt>();
        var seen = new HashSet<String>();
        for (var observation : formation.observations()) {
            work.add(1);
            if (observation.geneSequence().size() < 2 || observation.minimality().isEmpty()
                    || !observation.minimality().orElseThrow().observedReplayVerified()) {
                attempts.add(new Attempt(List.of(observation.input().id()), "TRACE_ONLY", "NO_ADMITTED_MULTISTEP_SELECTED_PATH"));
                continue;
            }
            var path = observation.search().bestState().path();
            var assumptions = observation.search().bestState().transformations().stream()
                .flatMap(step -> step.assumptions().stream()).distinct().toList();
            if (!assumptions.isEmpty()) {
                attempts.add(new Attempt(List.of(observation.input().id()), "TRACE_ONLY", "CONDITIONAL_SCHEMAS_UNSUPPORTED"));
                continue;
            }
            var asts = new ArrayList<Expr>();
            try {
                for (String expression : path) {
                    if (expression.length() > 16_384) throw new IllegalArgumentException("training source size limit");
                    work.add(1);
                    Expr ast = new ExpressionParser().parseExactTerm(expression).expression();
                    domain(ast, bounds, work);
                    asts.add(ast);
                }
                addPair(examples, seen, observation.input().id(), 0, asts.size() - 1,
                    asts.getFirst(), asts.getLast(), work);
                for (int length = asts.size() - 2; length >= 2; length--) {
                    for (int start = 0; start + length < asts.size(); start++) {
                        addPair(deferred, seen, observation.input().id(), start, start + length,
                            asts.get(start), asts.get(start + length), work);
                    }
                }
            } catch (IllegalArgumentException unsupported) {
                attempts.add(new Attempt(List.of(observation.input().id()), "TRACE_ONLY", unsupported.getMessage()));
            }
        }
        examples.addAll(deferred);
        if (examples.size() > bounds.maximumExamples()) {
            attempts.add(new Attempt(List.of(), "EXTRACTION_LIMIT", "UNEXAMINED_WINDOWS=" + (examples.size() - bounds.maximumExamples())));
            examples.subList(bounds.maximumExamples(), examples.size()).clear();
        }
        String inventoryBinding = inventorySemanticsHash(formation.inventory().contentHash());
        var admitted = new TreeMap<String, Schema>();
        var generalizer = new TypedPatternGeneralizer();
        int paired = 0;
        outer: for (int left = 0; left < examples.size(); left++) {
            for (int right = left + 1; right < examples.size(); right++) {
                var first = examples.get(left);
                var second = examples.get(right);
                work.add(1);
                if (first.inputId().equals(second.inputId()) || !shape(first.example().source()).equals(shape(second.example().source()))) continue;
                if (paired++ >= bounds.maximumPairAttempts() || admitted.size() >= bounds.maximumSchemas()) {
                    attempts.add(new Attempt(List.of(), "FORMATION_LIMIT", "UNEXAMINED_PAIRS_REMAIN"));
                    break outer;
                }
                List<String> origins = List.of(first.windowId(), second.windowId());
                try {
                    work.add(1);
                    var hypothesis = generalizer.generalize(List.of(first.example(), second.example()));
                    if (hypothesis.isEmpty()) {
                        attempts.add(new Attempt(origins, "TRACE_ONLY", "NO_COMMON_SOURCE_BOUND_HYPOTHESIS"));
                        continue;
                    }
                    var candidate = hypothesis.orElseThrow();
                    String proof = prove(candidate.source(), candidate.target(), bounds, work);
                    var schema = new Schema(candidate.source(), candidate.target(), proof, inventoryBinding, origins);
                    var old = admitted.get(schema.id());
                    if (old != null) {
                        var combined = new ArrayList<>(old.supportingObservationIds());
                        combined.addAll(origins);
                        schema = new Schema(candidate.source(), candidate.target(), proof, inventoryBinding, combined);
                    }
                    admitted.put(schema.id(), schema);
                    attempts.add(new Attempt(origins, "PROVED", schema.id()));
                } catch (IllegalArgumentException unsupported) {
                    attempts.add(new Attempt(origins, "TRACE_ONLY", unsupported.getMessage()));
                }
            }
        }
        work.add(admitted.size());
        return new CheckedLearnedSchemaModel(formation.inventory().contentHash(), formation.contentHash(), bounds,
            List.of(), List.copyOf(admitted.values()), attempts, work.units, 0);
    }

    private record ObservedPair(String inputId, String windowId, TypedPatternGeneralizer.Example example) {}
    /** Strip only identical enclosing operations with an unchanged sibling; no algebraic normalization. */
    private static void addPair(List<ObservedPair> output, Set<String> seen, String inputId, int start, int end,
            Expr source, Expr target, Work work) {
        while (source instanceof BinaryExpr left && target instanceof BinaryExpr right && left.operator() == right.operator()) {
            work.add(1);
            if (left.left().equals(right.left())) { source = left.right(); target = right.right(); }
            else if (left.right().equals(right.right())) { source = left.left(); target = right.left(); }
            else break;
        }
        work.add(1);
        if (source.equals(target)) return;
        String key = inputId + ":" + CODEC.encodeExpression(source) + ":" + CODEC.encodeExpression(target);
        if (seen.add(key)) output.add(new ObservedPair(inputId, inputId + ":" + start + "-" + end,
            new TypedPatternGeneralizer.Example(source, target)));
    }

    public List<Schema> schemas() { return schemas; }
    public List<Attempt> attempts() { return attempts; }
    public Bounds bounds() { return bounds; }
    public String inventoryHash() { return inventoryHash; }
    public String inventorySemanticsHash() { return inventorySemanticsHash; }
    public List<String> requiredAssumptions() { return requiredAssumptions; }
    /** Additional formation only; the preexisting learner's work must also be paid by callers. */
    public long formationWork() { return formationWork; }
    /** Fresh semantic recheck and decoding work, never serialized as proof authority. */
    public long loadWork() { return loadWork; }
    public String toCanonicalJson() { return canonicalJson; }
    /** Caller prerequisites gate the model; the admitted mathematical theorems remain unconditional. */
    public CheckedLearnedSchemaModel requiring(List<String> prerequisites) {
        var combined = new ArrayList<>(requiredAssumptions);
        combined.addAll(prerequisites);
        return new CheckedLearnedSchemaModel(inventoryHash, originStrategyHash, bounds, combined, schemas, attempts, formationWork, loadWork);
    }

    public List<MoveProvider> providers() { return providers(bounds.maximumSchemas()); }
    public List<MoveProvider> providers(int maximumSchemasPerOccurrence) { return providers(maximumSchemasPerOccurrence, Map.of()); }
    public List<MoveProvider> providers(int maximumSchemasPerOccurrence, Map<String, Double> utilityBySchemaId,
            Set<String> includedSchemaIds) {
        if (maximumSchemasPerOccurrence < 1 || maximumSchemasPerOccurrence > bounds.maximumSchemas()) {
            throw new IllegalArgumentException("schema selection bound outside model limits");
        }
        Objects.requireNonNull(includedSchemaIds, "includedSchemaIds");
        if (!byId.keySet().containsAll(includedSchemaIds)) throw new IllegalArgumentException("unregistered included schema");
        Objects.requireNonNull(utilityBySchemaId, "utilityBySchemaId");
        for (var entry : utilityBySchemaId.entrySet()) if (!byId.containsKey(entry.getKey())
                || entry.getValue() == null || !Double.isFinite(entry.getValue())) {
            throw new IllegalArgumentException("unknown schema or nonfinite selection utility");
        }
        return includedSchemaIds.isEmpty() ? List.of() : List.of(new IndexedProvider(maximumSchemasPerOccurrence,
            Map.copyOf(utilityBySchemaId), Set.copyOf(includedSchemaIds)));
    }
    public List<MoveProvider> providers(int maximumSchemasPerOccurrence, Map<String, Double> utilityBySchemaId) {
        return providers(maximumSchemasPerOccurrence, utilityBySchemaId, byId.keySet());
    }

    private final class IndexedProvider implements TypedMoveSearch.TypedProvider {
        private final Map<String, List<Schema>> index;
        private final int maximumSchemasPerOccurrence;
        private final boolean allSchemasIncluded;
        IndexedProvider(int maximumSchemasPerOccurrence, Map<String, Double> utility, Set<String> included) {
            this.maximumSchemasPerOccurrence = maximumSchemasPerOccurrence;
            allSchemasIncluded = included.size() == schemas.size();
            var mutable = new TreeMap<String, List<Schema>>();
            var ordered = new ArrayList<>(schemas.stream().filter(schema -> included.contains(schema.id())).toList());
            ordered.sort(Comparator.<Schema>comparingDouble(schema -> utility.getOrDefault(schema.id(), 0.0)).reversed()
                .thenComparing(Comparator.comparingInt((Schema schema) -> schema.structuralReduction).reversed()).thenComparing(Schema::id));
            ordered.forEach(schema -> mutable.computeIfAbsent(shape(schema.source()), ignored -> new ArrayList<>()).add(schema));
            var frozen = new TreeMap<String, List<Schema>>();
            mutable.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
            index = Map.copyOf(frozen);
        }
        @Override public Descriptor descriptor() { return descriptor; }
        @Override public Batch candidates(MoveState state, MoveContext context) {
            var work = new Work();
            work.add(1);
            if (!context.carries(requiredAssumptions, state)) return batch(List.of(), work, true);
            Expr source;
            try {
                if (state.expression().length() > 262_144) throw new IllegalArgumentException("schema source transport size limit");
                source = CODEC.decodeExpression(state.expression());
                work.add(1);
                domain(source, bounds, work);
            } catch (IllegalArgumentException unsupported) { return batch(List.of(), work, false); }
            var pending = new ArrayDeque<Occurrence>();
            pending.push(new Occurrence(source, List.of()));
            var candidates = new ArrayList<Transformation>();
            int matched = 0;
            boolean complete = allSchemasIncluded;
            outer: while (!pending.isEmpty()) {
                var occurrence = pending.pop();
                work.add(1);
                var relevant = index.getOrDefault(shape(occurrence.expression()), List.of());
                var wildcard = index.getOrDefault("P", List.of());
                if (!wildcard.isEmpty()) {
                    var combined = new ArrayList<>(relevant);
                    combined.addAll(wildcard);
                    relevant = combined;
                }
                if (relevant.size() > maximumSchemasPerOccurrence) complete = false;
                for (int i = 0; i < Math.min(relevant.size(), maximumSchemasPerOccurrence); i++) {
                    if (matched++ >= bounds.maximumMatchAttempts() || candidates.size() >= bounds.maximumCandidates()) {
                        complete = false;
                        break outer;
                    }
                    Schema schema = relevant.get(i);
                    var outcome = match(schema, occurrence.expression());
                    work.add((long) outcome.evaluatedSteps() + outcome.patternBranches());
                    if (!outcome.complete()) complete = false;
                    if (!outcome.matched()) continue;
                    var applicationWork = new Work();
                    try {
                        var application = apply(schema, source, state.expression(), occurrence.path(),
                            outcome.matches().getFirst().bindings(), applicationWork);
                        if (application != null) candidates.add(Transformation.exactTheory(ExactTheoryEvidence.fromVerified(application)));
                        else work.add(applicationWork.units);
                    } catch (IllegalArgumentException unsupported) {
                        // Failed/unchanged applications have no execution edge whose receipt could carry this work.
                        work.add(applicationWork.units + 1);
                        complete = false;
                    }
                }
                if (occurrence.expression() instanceof BinaryExpr binary) {
                    pending.push(new Occurrence(binary.right(), child(occurrence.path(), 1)));
                    pending.push(new Occurrence(binary.left(), child(occurrence.path(), 0)));
                }
            }
            return batch(candidates, work, complete);
        }
        private Batch batch(List<Transformation> transformations, Work work, boolean complete) {
            var candidateWork = transformations.stream().map(Transformation::executionWork).reduce(ExecutionWork.ZERO, ExecutionWork::plus);
            var metrics = new TransformationWorkMetrics(1, 0, 1, transformations.size(), 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, candidateWork).withDelegatedMechanicalWork(work.units);
            return new Batch(transformations.stream().map(transformation -> SearchMove.from(transformation,
                descriptor, metrics.totalWorkUnits())).toList(), metrics, complete);
        }
    }

    private record Occurrence(Expr expression, List<Integer> path) {}
    private static List<Integer> child(List<Integer> path, int index) {
        var copy = new ArrayList<>(path);
        copy.add(index);
        return List.copyOf(copy);
    }
    private ExprMatcher.MatchOutcome match(Schema schema, Expr occurrence) {
        return ExprMatcher.pattern(schema.source()).match(occurrence, new ExprMatcher.MatchOptions(
            null, 1, bounds.maximumExpressionNodes() * 4, bounds.maximumExpressionNodes() * 4));
    }

    /** Privately issued capability recognized by the installed SPI; public records/JSON are not accepted. */
    static final class VerifiedApplication {
        private final ExactTheoryEvidence.Binding binding;
        private VerifiedApplication(ExactTheoryEvidence.Binding binding) { this.binding = binding; }
        ExactTheoryEvidence.Binding binding() { return binding; }
    }
    private VerifiedApplication apply(Schema schema, Expr source, String encodedSource,
            List<Integer> path, Map<String, Expr> substitutions, Work work) {
        for (Expr expression : substitutions.values()) domain(expression, bounds, work);
        var position = new TreePosition(path, "checked-typed-occurrence");
        Expr replacement = schema.target().instantiate(substitutions);
        work.add(patternNodes(schema.target()) + path.size() + 1L);
        Expr target = position.replaceAt(source, replacement).rewrittenRoot().orElseThrow();
        domain(target, bounds, work);
        if (source.equals(target)) return null;
        String encodedTarget = CODEC.encodeExpression(target);
        var evidence = JSON.createObjectNode().put("schema", APPLICATION_REVISION).put("checkerRevision", CHECKER_REVISION)
            .put("inventorySemanticsHash", inventorySemanticsHash).put("modelId", descriptor.id())
            .put("schemaId", schema.id()).put("proofHash", schema.proofHash()).put("domain", DOMAIN)
            .put("source", encodedSource).put("target", encodedTarget);
        var positions = evidence.putArray("path");
        path.forEach(positions::add);
        var bindings = evidence.putArray("bindings");
        new TreeMap<>(substitutions).forEach((name, value) -> bindings.addObject()
            .put("name", name).put("expression", CODEC.encodeExpression(value)));
        evidence.put("applicationWork", work.units);
        String canonical = write(evidence);
        return new VerifiedApplication(new ExactTheoryEvidence.Binding(encodedSource, encodedTarget, schema.id(),
            SchematicProofPlan.hash(canonical), schema.proofHash(), modelHash, work.units, canonical));
    }

    /** Checks one supplied occurrence and substitution; never enumerates primitive paths or other sites. */
    public TypedMoveSearch.Verifier verifier() {
        return (source, move, context) -> {
            var work = new Work();
            work.add(1);
            try {
                var available = new HashSet<>(context.initialAssumptions());
                available.addAll(AssumptionSignature.ofExpressions(source.assumptions()).normalizedAssumptions());
                if (!available.containsAll(requiredAssumptions) || !available.containsAll(move.assumptions())) {
                    return rejected(work, "CHECKED_SCHEMA_PREREQUISITES_MISSING");
                }
                if (!(move.provenance() instanceof TransformationProvenance.ExactTheoryStep step)
                        || !move.ruleId().equals(descriptor.id())) return rejected(work, "CHECKED_SCHEMA_PROVENANCE_REQUIRED");
                var bound = step.evidence().binding();
                JsonNode data = read(bound.canonicalEvidenceJson());
                fields(data, "schema", "checkerRevision", "inventorySemanticsHash", "modelId", "schemaId", "proofHash",
                    "domain", "source", "target", "path", "bindings", "applicationWork");
                if (!APPLICATION_REVISION.equals(text(data, "schema")) || !CHECKER_REVISION.equals(text(data, "checkerRevision"))
                        || !inventorySemanticsHash.equals(text(data, "inventorySemanticsHash"))
                        || !descriptor.id().equals(text(data, "modelId")) || !DOMAIN.equals(text(data, "domain"))) {
                    return rejected(work, "CHECKED_SCHEMA_STALE_SEMANTICS");
                }
                Schema schema = byId.get(text(data, "schemaId"));
                if (schema == null) return rejected(work, "CHECKED_SCHEMA_UNREGISTERED");
                domain(source.expression(), bounds, work);
                String encodedSource = CODEC.encodeExpression(source.expression());
                if (!encodedSource.equals(text(data, "source")) || !encodedSource.equals(bound.sourceExpression())) {
                    return rejected(work, "CHECKED_SCHEMA_WRONG_SOURCE");
                }
                array(data.get("path"), bounds.maximumDepth());
                var path = new ArrayList<Integer>();
                for (var part : data.get("path")) {
                    if (!part.isInt() || part.intValue() < 0 || part.intValue() > 1) return rejected(work, "CHECKED_SCHEMA_INVALID_PATH");
                    path.add(part.intValue());
                }
                work.add(path.size() + 1L);
                var occurrence = new TreePosition(path, "checked-verification").subtreeAt(source.expression());
                if (occurrence.isEmpty()) return rejected(work, "CHECKED_SCHEMA_ABSENT_OCCURRENCE");
                var outcome = match(schema, occurrence.orElseThrow());
                work.add((long) outcome.evaluatedSteps() + outcome.patternBranches());
                if (!outcome.complete() || !outcome.matched()) return rejected(work, "CHECKED_SCHEMA_BINDING_MISMATCH");
                var substitutions = new TreeMap<String, Expr>();
                array(data.get("bindings"), 16);
                for (var value : data.get("bindings")) {
                    fields(value, "name", "expression");
                    Expr ast = CODEC.decodeExpression(text(value, "expression"));
                    domain(ast, bounds, work);
                    if (substitutions.put(text(value, "name"), ast) != null) return rejected(work, "CHECKED_SCHEMA_DUPLICATE_BINDING");
                }
                if (!substitutions.equals(outcome.matches().getFirst().bindings())) return rejected(work, "CHECKED_SCHEMA_BINDING_MISMATCH");
                var applicationWork = new Work();
                VerifiedApplication checked;
                try { checked = apply(schema, source.expression(), encodedSource, path, substitutions, applicationWork); }
                finally { work.add(applicationWork.units); }
                if (checked == null) return rejected(work, "CHECKED_SCHEMA_UNCHANGED");
                var expected = SearchMove.from(Transformation.exactTheory(ExactTheoryEvidence.fromVerified(checked)), descriptor, move.generationCost());
                // StateValue assesses capabilities after mathematical admission. MoveSearch overwrites
                // provider-supplied deltas with that assessment before retaining the witness. These
                // search annotations are not part of this source/occurrence/substitution theorem.
                boolean accepted = expected.equals(move.withCapabilityDelta(Set.of()));
                return new MoveVerifier.Verification(accepted, work.units,
                    accepted ? List.of("checked-schema-application:" + checked.binding().evidenceHash()) : List.of(),
                    accepted ? "CHECKED_SCHEMA_OCCURRENCE_VERIFIED" : "CHECKED_SCHEMA_TARGET_OR_EVIDENCE_MISMATCH");
            } catch (IllegalArgumentException unsupported) { return rejected(work, "CHECKED_SCHEMA_UNSUPPORTED_OR_MALFORMED"); }
        };
    }
    private static MoveVerifier.Verification rejected(Work work, String detail) {
        return new MoveVerifier.Verification(false, work.units, List.of(), detail);
    }

    private String render() {
        var root = JSON.createObjectNode().put("schema", REVISION).put("checkerRevision", CHECKER_REVISION)
            .put("domain", DOMAIN).put("arithmeticProfile", ARITHMETIC_PROFILE).put("inventoryHash", inventoryHash)
            .put("inventorySemanticsHash", inventorySemanticsHash).put("originStrategyHash", originStrategyHash);
        root.set("bounds", boundsJson(bounds));
        var required = root.putArray("requiredAssumptions");
        requiredAssumptions.forEach(required::add);
        root.put("formationWork", formationWork);
        var values = root.putArray("schemas");
        for (var schema : schemas) {
            var value = values.addObject().put("id", schema.id());
            value.set("source", pattern(schema.source()));
            value.set("target", pattern(schema.target()));
            value.putArray("assumptions");
            value.put("proofHash", schema.proofHash());
            var origins = value.putArray("supportingObservationIds");
            schema.supportingObservationIds().forEach(origins::add);
        }
        var records = root.putArray("attempts");
        for (var attempt : attempts) {
            var value = records.addObject();
            var origins = value.putArray("observationIds");
            attempt.observationIds().forEach(origins::add);
            value.put("status", attempt.status()).put("detail", attempt.detail());
        }
        return write(root);
    }

    /** Load verifies the symbolic statement before inspecting descriptive proof digests. */
    public static CheckedLearnedSchemaModel load(String json, String expectedInventoryHash) {
        hash(expectedInventoryHash);
        var work = new Work();
        work.add(1);
        JsonNode root = read(json);
        fields(root, "schema", "checkerRevision", "domain", "arithmeticProfile", "inventoryHash", "inventorySemanticsHash",
            "originStrategyHash", "bounds", "requiredAssumptions", "formationWork", "schemas", "attempts");
        if (!REVISION.equals(text(root, "schema")) || !CHECKER_REVISION.equals(text(root, "checkerRevision"))
                || !DOMAIN.equals(text(root, "domain")) || !ARITHMETIC_PROFILE.equals(text(root, "arithmeticProfile"))
                || !expectedInventoryHash.equals(text(root, "inventoryHash"))
                || !inventorySemanticsHash(expectedInventoryHash).equals(text(root, "inventorySemanticsHash"))) {
            throw new IllegalArgumentException("stale checked schema revision, domain or inventory semantics");
        }
        Bounds bounds = readBounds(root.get("bounds"));
        var schemas = new ArrayList<Schema>();
        array(root.get("schemas"), bounds.maximumSchemas());
        for (var value : root.get("schemas")) {
            fields(value, "id", "source", "target", "assumptions", "proofHash", "supportingObservationIds");
            array(value.get("assumptions"), 0);
            PatternExpr source = pattern(value.get("source"), bounds, work);
            PatternExpr target = pattern(value.get("target"), bounds, work);
            String proof = prove(source, target, bounds, work);
            var schema = new Schema(source, target, proof, inventorySemanticsHash(expectedInventoryHash),
                texts(value.get("supportingObservationIds"), 256));
            if (!schema.id().equals(text(value, "id")) || !schema.proofHash().equals(text(value, "proofHash"))) {
                throw new IllegalArgumentException("checked schema statement identity differs after semantic recheck");
            }
            schemas.add(schema);
        }
        array(root.get("attempts"), bounds.maximumPairAttempts() + 66);
        var attempts = new ArrayList<Attempt>();
        for (var value : root.get("attempts")) {
            fields(value, "observationIds", "status", "detail");
            attempts.add(new Attempt(texts(value.get("observationIds"), 2), text(value, "status"), text(value, "detail")));
            work.add(1);
        }
        work.add(schemas.size());
        var loaded = new CheckedLearnedSchemaModel(expectedInventoryHash, text(root, "originStrategyHash"), bounds,
            texts(root.get("requiredAssumptions"), 32), schemas, attempts, number(root, "formationWork"), work.units);
        if (!loaded.toCanonicalJson().equals(json)) throw new IllegalArgumentException("noncanonical checked schema model");
        return loaded;
    }

    private static ObjectNode boundsJson(Bounds bounds) {
        return JSON.createObjectNode().put("maximumExpressionNodes", bounds.maximumExpressionNodes())
            .put("maximumPatternNodes", bounds.maximumPatternNodes()).put("maximumDepth", bounds.maximumDepth())
            .put("maximumCoefficientBits", bounds.maximumCoefficientBits()).put("maximumExponent", bounds.maximumExponent())
            .put("maximumExamples", bounds.maximumExamples()).put("maximumPairAttempts", bounds.maximumPairAttempts())
            .put("maximumSchemas", bounds.maximumSchemas()).put("maximumMatchAttempts", bounds.maximumMatchAttempts())
            .put("maximumCandidates", bounds.maximumCandidates());
    }
    private static Bounds readBounds(JsonNode value) {
        String[] names = {"maximumExpressionNodes", "maximumPatternNodes", "maximumDepth", "maximumCoefficientBits", "maximumExponent",
            "maximumExamples", "maximumPairAttempts", "maximumSchemas", "maximumMatchAttempts", "maximumCandidates"};
        fields(value, names);
        int[] counts = new int[names.length];
        for (int i = 0; i < counts.length; i++) {
            long count = number(value, names[i]);
            if (count > Integer.MAX_VALUE) throw new IllegalArgumentException("checked schema bound exceeds supported range");
            counts[i] = (int) count;
        }
        return new Bounds(counts[0], counts[1], counts[2], counts[3], counts[4], counts[5], counts[6], counts[7], counts[8], counts[9]);
    }
    private static List<String> texts(JsonNode value, int maximum) {
        array(value, maximum);
        var values = new ArrayList<String>();
        for (var item : value) {
            if (!item.isTextual() || item.textValue().isBlank() || item.textValue().length() > 512) {
                throw new IllegalArgumentException("invalid checked schema text list");
            }
            values.add(item.textValue());
        }
        return List.copyOf(values);
    }
    private static List<String> normalizedRequirements(List<String> values) {
        Objects.requireNonNull(values, "requirements");
        if (values.size() > 32 || values.stream().anyMatch(value -> value == null || value.length() > 512)) {
            throw new IllegalArgumentException("checked model prerequisite limit");
        }
        return AssumptionSignature.ofExpressions(values).normalizedAssumptions();
    }
    private static String inventorySemanticsHash(String inventoryHash) {
        return SchematicProofPlan.hash(INVENTORY_REVISION + "\n" + inventoryHash);
    }
    private static String shape(Expr expression) {
        return expression instanceof BinaryExpr binary ? binary.operator().name() : "LEAF";
    }
    private static String shape(PatternExpr pattern) {
        return pattern instanceof PatternExpr.Operation operation ? operation.operator().name()
            : pattern instanceof PatternExpr.Placeholder ? "P" : "LEAF";
    }
    private static int patternNodes(PatternExpr expression) {
        if (expression instanceof PatternExpr.Operation operation) return 1 + patternNodes(operation.left()) + patternNodes(operation.right());
        return 1;
    }
}
