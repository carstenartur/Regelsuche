package de.regelsuche.evolution;

import de.regelsuche.json.JsonWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Canonical bounded IR for an ordered target-free schematic proof plan.
 *
 * <p>A plan may declare typed holes and open obligations. It is neither an
 * executable rewrite program nor proof evidence and deliberately has no target,
 * historical-reference or held-out-outcome field.</p>
 */
public record SchematicProofPlan(
    String schema,
    String planId,
    InformationBoundary informationBoundary,
    String formationScopeHash,
    List<Step> steps,
    List<Hole> holes,
    List<Obligation> obligations,
    Limits limits,
    String contentHash
) {
    public static final String SCHEMA = "regelsuche.schematic-proof-plan/v1";
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{2,127}");
    private static final Pattern TOKEN = Pattern.compile("[a-z][a-z0-9._/-]{1,127}");
    private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");

    public SchematicProofPlan {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported schematic proof-plan schema");
        }
        planId = requireId(planId, "planId");
        informationBoundary = requireBoundary(informationBoundary);
        formationScopeHash = requireSha256(formationScopeHash, "formationScopeHash");
        limits = Objects.requireNonNull(limits, "limits");
        steps = normalizeSteps(steps, limits);
        holes = normalizeHoles(holes, limits);
        obligations = normalizeObligations(obligations, limits);
        validateReferences(steps, holes, obligations);
        contentHash = requireSha256(contentHash, "contentHash");

        String payload = render(planId, informationBoundary, formationScopeHash,
            steps, holes, obligations, limits, null);
        requireCanonicalSize(payload, limits);
        if (!hash(payload).equals(contentHash)) {
            throw new IllegalArgumentException("contentHash does not match schematic proof plan");
        }
        requireCanonicalSize(render(planId, informationBoundary, formationScopeHash,
            steps, holes, obligations, limits, contentHash), limits);
    }

    public static SchematicProofPlan create(
        String planId,
        InformationBoundary informationBoundary,
        String formationScopeHash,
        List<Step> steps,
        List<Hole> holes,
        List<Obligation> obligations,
        Limits limits
    ) {
        String normalizedId = requireId(planId, "planId");
        InformationBoundary boundary = requireBoundary(informationBoundary);
        String scopeHash = requireSha256(formationScopeHash, "formationScopeHash");
        Limits bounded = Objects.requireNonNull(limits, "limits");
        List<Step> normalizedSteps = normalizeSteps(steps, bounded);
        List<Hole> normalizedHoles = normalizeHoles(holes, bounded);
        List<Obligation> normalizedObligations = normalizeObligations(obligations, bounded);
        validateReferences(normalizedSteps, normalizedHoles, normalizedObligations);
        String payload = render(normalizedId, boundary, scopeHash, normalizedSteps,
            normalizedHoles, normalizedObligations, bounded, null);
        requireCanonicalSize(payload, bounded);
        return new SchematicProofPlan(SCHEMA, normalizedId, boundary, scopeHash,
            normalizedSteps, normalizedHoles, normalizedObligations, bounded, hash(payload));
    }

    public List<String> holeIds() {
        return holes.stream().map(Hole::id).toList();
    }

    public List<String> obligationIds() {
        return obligations.stream().map(Obligation::id).toList();
    }

    public String toCanonicalJson() {
        return render(planId, informationBoundary, formationScopeHash, steps, holes,
            obligations, limits, contentHash);
    }

    private static String render(
        String planId,
        InformationBoundary boundary,
        String scopeHash,
        List<Step> steps,
        List<Hole> holes,
        List<Obligation> obligations,
        Limits limits,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("planId", planId)
            .property("informationBoundary", boundary.name())
            .property("formationScopeHash", scopeHash)
            .object("limits", object -> writeLimits(object, limits))
            .array("steps", array -> steps.forEach(step ->
                array.objectValue(object -> writeStep(object, step))))
            .array("holes", array -> holes.forEach(hole ->
                array.objectValue(object -> writeHole(object, hole))))
            .array("obligations", array -> obligations.forEach(obligation ->
                array.objectValue(object -> writeObligation(object, obligation))));
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    private static void writeLimits(JsonWriter json, Limits limits) {
        json.property("maxSteps", limits.maxSteps())
            .property("maxHoles", limits.maxHoles())
            .property("maxObligations", limits.maxObligations())
            .property("maxCanonicalBytes", limits.maxCanonicalBytes());
    }

    private static void writeStep(JsonWriter json, Step step) {
        json.property("id", step.id())
            .property("action", step.action().name())
            .stringArray("holeIds", step.holeIds())
            .stringArray("obligationIds", step.obligationIds());
    }

    private static void writeHole(JsonWriter json, Hole hole) {
        json.property("id", hole.id())
            .property("kind", hole.kind().name())
            .property("sort", hole.sort().name())
            .property("domainId", hole.domainId())
            .property("grammarRevision", hole.grammarRevision())
            .object("budget", budget -> budget
                .property("maxCandidates", hole.budget().maxCandidates())
                .property("maxCanonicalBytes", hole.budget().maxCanonicalBytes())
                .property("maxScalarBits", hole.budget().maxScalarBits())
                .property("maxOccurrenceDepth", hole.budget().maxOccurrenceDepth()));
    }

    private static void writeObligation(JsonWriter json, Obligation obligation) {
        json.property("id", obligation.id())
            .property("kind", obligation.kind().name())
            .property("issuerStepId", obligation.issuerStepId())
            .stringArray("dependentHoleIds", obligation.dependentHoleIds())
            .stringArray("assumptions", obligation.assumptions())
            .property("checkerCapability", obligation.checkerCapability())
            .property("checkerRevisionHash", obligation.checkerRevisionHash())
            .property("initialStatus", obligation.initialStatus().name());
    }

    private static List<Step> normalizeSteps(List<Step> values, Limits limits) {
        Objects.requireNonNull(values, "steps");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("schematic plan requires at least one step");
        }
        List<Step> result = values.stream()
            .map(value -> Objects.requireNonNull(value, "step"))
            .toList();
        if (result.size() > limits.maxSteps()) {
            throw new IllegalArgumentException("step count exceeds plan limits");
        }
        requireUnique(result.stream().map(Step::id).toList(), "step IDs");
        return List.copyOf(result);
    }

    private static List<Hole> normalizeHoles(List<Hole> values, Limits limits) {
        Objects.requireNonNull(values, "holes");
        List<Hole> result = values.stream()
            .map(value -> Objects.requireNonNull(value, "hole"))
            .sorted(Comparator.comparing(Hole::id))
            .toList();
        if (result.size() > limits.maxHoles()) {
            throw new IllegalArgumentException("hole count exceeds plan limits");
        }
        requireUnique(result.stream().map(Hole::id).toList(), "hole IDs");
        return List.copyOf(result);
    }

    private static List<Obligation> normalizeObligations(
        List<Obligation> values,
        Limits limits
    ) {
        Objects.requireNonNull(values, "obligations");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("schematic plan requires an obligation");
        }
        List<Obligation> result = values.stream()
            .map(value -> Objects.requireNonNull(value, "obligation"))
            .sorted(Comparator.comparing(Obligation::id))
            .toList();
        if (result.size() > limits.maxObligations()) {
            throw new IllegalArgumentException("obligation count exceeds plan limits");
        }
        requireUnique(result.stream().map(Obligation::id).toList(), "obligation IDs");
        return List.copyOf(result);
    }

    private static void validateReferences(
        List<Step> steps,
        List<Hole> holes,
        List<Obligation> obligations
    ) {
        Map<String, Step> stepsById = new HashMap<>();
        steps.forEach(step -> stepsById.put(step.id(), step));
        Set<String> holeIds = Set.copyOf(holes.stream().map(Hole::id).toList());
        Set<String> obligationIds = Set.copyOf(
            obligations.stream().map(Obligation::id).toList());
        Set<String> referencedHoles = new LinkedHashSet<>();
        Set<String> referencedObligations = new LinkedHashSet<>();
        List<Step> emitSteps = steps.stream()
            .filter(step -> step.action() == StepAction.EMIT_CANDIDATE)
            .toList();

        for (Step step : steps) {
            requireKnown(step.holeIds(), holeIds, "plan step hole");
            requireKnown(step.obligationIds(), obligationIds, "plan step obligation");
            referencedHoles.addAll(step.holeIds());
            referencedObligations.addAll(step.obligationIds());
        }
        if (!referencedHoles.equals(holeIds)) {
            throw new IllegalArgumentException("every hole must be referenced by a step");
        }
        if (!referencedObligations.equals(obligationIds)) {
            throw new IllegalArgumentException("every obligation must be referenced by a step");
        }
        if (emitSteps.size() != 1 || !emitSteps.getFirst().equals(steps.getLast())) {
            throw new IllegalArgumentException("v1 requires one final candidate-emission step");
        }
        if (!Set.copyOf(emitSteps.getFirst().obligationIds()).equals(obligationIds)) {
            throw new IllegalArgumentException("emission must depend on every obligation");
        }
        for (Obligation obligation : obligations) {
            Step issuer = stepsById.get(obligation.issuerStepId());
            if (issuer == null || !issuer.obligationIds().contains(obligation.id())) {
                throw new IllegalArgumentException("obligation issuer is not connected to obligation");
            }
            requireKnown(obligation.dependentHoleIds(), holeIds, "obligation hole");
        }
    }

    private static void requireKnown(List<String> actual, Set<String> declared, String name) {
        if (!declared.containsAll(actual)) {
            throw new IllegalArgumentException(name + " references an unknown ID");
        }
    }

    private static InformationBoundary requireBoundary(InformationBoundary value) {
        if (value != InformationBoundary.TARGET_FREE_FORMATION) {
            throw new IllegalArgumentException("v1 requires TARGET_FREE_FORMATION");
        }
        return value;
    }

    private static void requireCanonicalSize(String json, Limits limits) {
        if (json.getBytes(StandardCharsets.UTF_8).length > limits.maxCanonicalBytes()) {
            throw new IllegalArgumentException("canonical plan exceeds maxCanonicalBytes");
        }
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

    static String requireSha256(String value, String name) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
        return value;
    }

    static String requireId(String value, String name) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must match " + ID.pattern());
        }
        return value;
    }

    static String requireToken(String value, String name) {
        if (value == null || !TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must match " + TOKEN.pattern());
        }
        return value;
    }

    static List<String> normalizeIds(List<String> values, String name, boolean allowEmpty) {
        Objects.requireNonNull(values, name);
        List<String> result = values.stream()
            .map(value -> requireId(value, name + " entry"))
            .sorted()
            .toList();
        if (!allowEmpty && result.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        requireUnique(result, name);
        return List.copyOf(result);
    }

    static List<String> normalizeAssumptions(List<String> values) {
        Objects.requireNonNull(values, "assumptions");
        List<String> result = values.stream()
            .map(value -> {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException("assumptions must not be blank");
                }
                return value.trim().replaceAll("\\s+", " ");
            })
            .sorted()
            .toList();
        requireUnique(result, "assumptions");
        return List.copyOf(result);
    }

    static void requireUnique(List<String> values, String name) {
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(name + " must be unique");
        }
    }

    private static void requireNonEmpty(List<?> values, String name) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
    }

    private static void requireRange(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                name + " must be in [" + minimum + "," + maximum + "]");
        }
    }

    public enum InformationBoundary {
        TARGET_FREE_FORMATION
    }

    public enum StepAction {
        FORM_CANDIDATES,
        SELECT_BINDINGS,
        SOLVE_HOLES,
        DISCHARGE_OBLIGATIONS,
        COMPOSE,
        EMIT_CANDIDATE
    }

    public enum HoleSort {
        EXACT_RATIONAL,
        SIGN,
        TERM,
        OCCURRENCE_PATH,
        OCCURRENCE_PAIR,
        FORMULA
    }

    public enum HoleKind {
        COEFFICIENT(HoleSort.EXACT_RATIONAL),
        SIGN(HoleSort.SIGN),
        TERM(HoleSort.TERM),
        OCCURRENCE(HoleSort.OCCURRENCE_PATH),
        DISJOINT_TERM_PAIR(HoleSort.OCCURRENCE_PAIR),
        INVARIANT(HoleSort.TERM),
        MEASURE(HoleSort.TERM),
        WITNESS(HoleSort.TERM),
        FORMULA(HoleSort.FORMULA);

        private final HoleSort requiredSort;

        HoleKind(HoleSort requiredSort) {
            this.requiredSort = requiredSort;
        }

        public HoleSort requiredSort() {
            return requiredSort;
        }
    }

    public enum ObligationKind {
        EQUIVALENT,
        ZERO,
        NON_ZERO,
        DISJOINT_OCCURRENCES,
        COMPLETE_SOURCE_COVER,
        RESIDUAL_SUM_IS_ZERO,
        CAPABILITY_UNLOCKED,
        PRESERVES_INVARIANT,
        STRICTLY_DECREASES,
        PROVES
    }

    public enum InitialObligationStatus {
        OPEN
    }

    public record Step(
        String id,
        StepAction action,
        List<String> holeIds,
        List<String> obligationIds
    ) {
        public Step {
            id = requireId(id, "step id");
            action = Objects.requireNonNull(action, "action");
            holeIds = normalizeIds(holeIds, "holeIds", true);
            obligationIds = normalizeIds(obligationIds, "obligationIds", true);
            switch (action) {
                case FORM_CANDIDATES, SELECT_BINDINGS, SOLVE_HOLES ->
                    requireNonEmpty(holeIds, action + " holeIds");
                case DISCHARGE_OBLIGATIONS, EMIT_CANDIDATE ->
                    requireNonEmpty(obligationIds, action + " obligationIds");
                case COMPOSE -> {
                    requireNonEmpty(holeIds, "COMPOSE holeIds");
                    requireNonEmpty(obligationIds, "COMPOSE obligationIds");
                }
            }
        }
    }

    public record Hole(
        String id,
        HoleKind kind,
        HoleSort sort,
        String domainId,
        String grammarRevision,
        HoleBudget budget
    ) {
        public Hole {
            id = requireId(id, "hole id");
            kind = Objects.requireNonNull(kind, "kind");
            sort = Objects.requireNonNull(sort, "sort");
            if (sort != kind.requiredSort()) {
                throw new IllegalArgumentException(
                    "hole kind " + kind + " requires sort " + kind.requiredSort());
            }
            domainId = requireToken(domainId, "domainId");
            grammarRevision = requireToken(grammarRevision, "grammarRevision");
            budget = Objects.requireNonNull(budget, "budget");
            if ((sort == HoleSort.EXACT_RATIONAL || sort == HoleSort.SIGN)
                    && budget.maxScalarBits() < 1) {
                throw new IllegalArgumentException("scalar holes require maxScalarBits > 0");
            }
        }
    }

    public record HoleBudget(
        int maxCandidates,
        int maxCanonicalBytes,
        int maxScalarBits,
        int maxOccurrenceDepth
    ) {
        public HoleBudget {
            requireRange(maxCandidates, 1, 1_000_000, "maxCandidates");
            requireRange(maxCanonicalBytes, 1, 1_000_000, "maxCanonicalBytes");
            requireRange(maxScalarBits, 0, 1_000_000, "maxScalarBits");
            requireRange(maxOccurrenceDepth, 0, 1_024, "maxOccurrenceDepth");
        }
    }

    public record Obligation(
        String id,
        ObligationKind kind,
        String issuerStepId,
        List<String> dependentHoleIds,
        List<String> assumptions,
        String checkerCapability,
        String checkerRevisionHash,
        InitialObligationStatus initialStatus
    ) {
        public Obligation {
            id = requireId(id, "obligation id");
            kind = Objects.requireNonNull(kind, "kind");
            issuerStepId = requireId(issuerStepId, "issuerStepId");
            dependentHoleIds = normalizeIds(dependentHoleIds, "dependentHoleIds", true);
            assumptions = normalizeAssumptions(assumptions);
            checkerCapability = requireToken(checkerCapability, "checkerCapability");
            checkerRevisionHash = requireSha256(checkerRevisionHash, "checkerRevisionHash");
            if (initialStatus != InitialObligationStatus.OPEN) {
                throw new IllegalArgumentException("v1 obligations must begin OPEN");
            }
        }
    }

    public record Limits(
        int maxSteps,
        int maxHoles,
        int maxObligations,
        int maxCanonicalBytes
    ) {
        public Limits {
            requireRange(maxSteps, 1, 1_024, "maxSteps");
            requireRange(maxHoles, 0, 1_024, "maxHoles");
            requireRange(maxObligations, 1, 2_048, "maxObligations");
            requireRange(maxCanonicalBytes, 1, 4_000_000, "maxCanonicalBytes");
        }
    }
}
