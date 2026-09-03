package de.regelsuche.evolution;

import de.regelsuche.evolution.SchematicProofPlan.Hole;
import de.regelsuche.evolution.SchematicProofPlan.HoleSort;
import de.regelsuche.evolution.SchematicProofPlan.Obligation;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.scalar.ExactRationalDomain;
import de.regelsuche.scalar.ExactRationalParseEvidence;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Content-addressed bindings and checker-outcome references for one schematic
 * proof plan.
 *
 * <p>This record does not load the referenced evidence, prove its contents,
 * compile a plan or create an executable rewrite.</p>
 */
public record SchematicProofPlanResolution(
    String schema,
    String planHash,
    List<String> requiredHoleIds,
    List<String> requiredObligationIds,
    List<HoleBinding> bindings,
    List<ObligationOutcome> outcomes,
    ResolutionState state,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.schematic-proof-plan-resolution/v1";
    private static final Pattern OCCURRENCE_PATH = Pattern.compile(
        "root|(?:0|[1-9][0-9]*)(?:\\.(?:0|[1-9][0-9]*))*");
    private static final Pattern DETAIL_CODE = Pattern.compile(
        "[A-Z][A-Z0-9_]{2,127}");
    private static final int MAX_BINDINGS = 1_024;
    private static final int MAX_OUTCOMES = 2_048;
    private static final int MAX_VALUE_BYTES = 1_000_000;
    private static final int MAX_RESOLUTION_BYTES = 4_000_000;

    public SchematicProofPlanResolution {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported plan-resolution schema");
        }
        planHash = SchematicProofPlan.requireSha256(
            planHash,
            "planHash");
        requiredHoleIds = SchematicProofPlan.normalizeIds(
            requiredHoleIds,
            "requiredHoleIds",
            false);
        requiredObligationIds = SchematicProofPlan.normalizeIds(
            requiredObligationIds,
            "requiredObligationIds",
            false);
        bindings = normalizeBindings(bindings);
        outcomes = normalizeOutcomes(outcomes);
        requireSubset(
            bindings.stream().map(HoleBinding::holeId).toList(),
            requiredHoleIds,
            "bindings");
        requireSubset(
            outcomes.stream().map(ObligationOutcome::obligationId).toList(),
            requiredObligationIds,
            "outcomes");
        ResolutionState expected = deriveState(
            requiredHoleIds,
            requiredObligationIds,
            bindings,
            outcomes);
        if (state != expected) {
            throw new IllegalArgumentException(
                "resolution state does not match contents");
        }
        contentHash = SchematicProofPlan.requireSha256(
            contentHash,
            "contentHash");
        String payload = render(
            planHash,
            requiredHoleIds,
            requiredObligationIds,
            bindings,
            outcomes,
            state,
            null);
        requireSize(payload, MAX_RESOLUTION_BYTES);
        if (!SchematicProofPlan.hash(payload).equals(contentHash)) {
            throw new IllegalArgumentException(
                "contentHash does not match resolution");
        }
        requireSize(
            render(
                planHash,
                requiredHoleIds,
                requiredObligationIds,
                bindings,
                outcomes,
                state,
                contentHash),
            MAX_RESOLUTION_BYTES);
    }

    public static SchematicProofPlanResolution create(
        SchematicProofPlan plan,
        List<HoleBinding> bindings,
        List<ObligationOutcome> outcomes
    ) {
        Objects.requireNonNull(plan, "plan");
        List<HoleBinding> normalizedBindings = normalizeBindings(bindings);
        List<ObligationOutcome> normalizedOutcomes =
            normalizeOutcomes(outcomes);
        Map<String, Hole> holes = indexHoles(plan.holes());
        Map<String, Obligation> obligations =
            indexObligations(plan.obligations());

        for (HoleBinding binding : normalizedBindings) {
            Hole hole = holes.get(binding.holeId());
            if (hole == null) {
                throw new IllegalArgumentException(
                    "binding references unknown hole");
            }
            validateBinding(binding, hole);
        }
        for (ObligationOutcome outcome : normalizedOutcomes) {
            Obligation obligation = obligations.get(
                outcome.obligationId());
            if (obligation == null) {
                throw new IllegalArgumentException(
                    "outcome references unknown obligation");
            }
            validateOutcome(outcome, obligation);
        }

        ResolutionState state = deriveState(
            plan.holeIds(),
            plan.obligationIds(),
            normalizedBindings,
            normalizedOutcomes);
        String payload = render(
            plan.contentHash(),
            plan.holeIds(),
            plan.obligationIds(),
            normalizedBindings,
            normalizedOutcomes,
            state,
            null);
        requireSize(payload, plan.limits().maxCanonicalBytes());
        SchematicProofPlanResolution result =
            new SchematicProofPlanResolution(
                SCHEMA,
                plan.contentHash(),
                plan.holeIds(),
                plan.obligationIds(),
                normalizedBindings,
                normalizedOutcomes,
                state,
                SchematicProofPlan.hash(payload));
        requireSize(
            result.toCanonicalJson(),
            plan.limits().maxCanonicalBytes());
        return result;
    }

    /**
     * Replays all plan-relative structural checks. A true result is neither
     * mathematical proof nor execution authorization.
     */
    public boolean isStructurallyCompleteFor(
        SchematicProofPlan plan
    ) {
        Objects.requireNonNull(plan, "plan");
        try {
            SchematicProofPlanResolution replayed = create(
                plan,
                bindings,
                outcomes);
            return equals(replayed)
                && state == ResolutionState.COMPLETE_REFERENCES;
        } catch (IllegalArgumentException rejected) {
            return false;
        }
    }

    public String toCanonicalJson() {
        return render(
            planHash,
            requiredHoleIds,
            requiredObligationIds,
            bindings,
            outcomes,
            state,
            contentHash);
    }

    private static Map<String, Hole> indexHoles(
        List<Hole> holes
    ) {
        Map<String, Hole> result = new HashMap<>();
        holes.forEach(hole -> result.put(hole.id(), hole));
        return result;
    }

    private static Map<String, Obligation> indexObligations(
        List<Obligation> obligations
    ) {
        Map<String, Obligation> result = new HashMap<>();
        obligations.forEach(obligation ->
            result.put(obligation.id(), obligation));
        return result;
    }

    private static String render(
        String planHash,
        List<String> requiredHoleIds,
        List<String> requiredObligationIds,
        List<HoleBinding> bindings,
        List<ObligationOutcome> outcomes,
        ResolutionState state,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("planHash", planHash)
            .stringArray("requiredHoleIds", requiredHoleIds)
            .stringArray(
                "requiredObligationIds",
                requiredObligationIds)
            .array("bindings", array -> bindings.forEach(binding ->
                array.objectValue(object ->
                    writeBinding(object, binding))))
            .array("outcomes", array -> outcomes.forEach(outcome ->
                array.objectValue(object ->
                    writeOutcome(object, outcome))))
            .property("state", state.name());
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    private static void writeBinding(
        JsonWriter json,
        HoleBinding binding
    ) {
        json.property("holeId", binding.holeId())
            .property("sort", binding.sort().name())
            .property("canonicalValue", binding.canonicalValue())
            .property("evidenceHash", binding.evidenceHash());
    }

    private static void writeOutcome(
        JsonWriter json,
        ObligationOutcome outcome
    ) {
        json.property("obligationId", outcome.obligationId())
            .property("status", outcome.status().name())
            .property(
                "checkerCapability",
                outcome.checkerCapability())
            .property(
                "checkerRevisionHash",
                outcome.checkerRevisionHash())
            .property(
                "checkerExecutionHash",
                outcome.checkerExecutionHash())
            .property("detailCode", outcome.detailCode());
    }

    private static List<HoleBinding> normalizeBindings(
        List<HoleBinding> values
    ) {
        Objects.requireNonNull(values, "bindings");
        if (values.size() > MAX_BINDINGS) {
            throw new IllegalArgumentException(
                "binding count exceeds absolute limit");
        }
        List<HoleBinding> result = values.stream()
            .map(value -> Objects.requireNonNull(value, "binding"))
            .sorted(Comparator.comparing(HoleBinding::holeId))
            .toList();
        SchematicProofPlan.requireUnique(
            result.stream().map(HoleBinding::holeId).toList(),
            "binding hole IDs");
        return List.copyOf(result);
    }

    private static List<ObligationOutcome> normalizeOutcomes(
        List<ObligationOutcome> values
    ) {
        Objects.requireNonNull(values, "outcomes");
        if (values.size() > MAX_OUTCOMES) {
            throw new IllegalArgumentException(
                "outcome count exceeds absolute limit");
        }
        List<ObligationOutcome> result = values.stream()
            .map(value ->
                Objects.requireNonNull(value, "outcome"))
            .sorted(Comparator.comparing(
                ObligationOutcome::obligationId))
            .toList();
        SchematicProofPlan.requireUnique(
            result.stream()
                .map(ObligationOutcome::obligationId)
                .toList(),
            "outcome obligation IDs");
        return List.copyOf(result);
    }

    private static void validateBinding(
        HoleBinding binding,
        Hole hole
    ) {
        if (binding.sort() != hole.sort()) {
            throw new IllegalArgumentException(
                "binding sort differs from hole sort");
        }
        if (utf8Length(binding.canonicalValue())
                > hole.budget().maxCanonicalBytes()) {
            throw new IllegalArgumentException(
                "binding exceeds hole byte budget");
        }
        switch (binding.sort()) {
            case EXACT_RATIONAL ->
                validateExactRational(binding, hole);
            case SIGN -> validateSign(binding);
            case OCCURRENCE_PATH -> validateOccurrencePath(
                binding.canonicalValue(),
                hole.budget().maxOccurrenceDepth());
            case OCCURRENCE_PAIR -> validateOccurrencePair(
                binding.canonicalValue(),
                hole.budget().maxOccurrenceDepth());
            case TERM, FORMULA -> {
                // Semantic validation belongs to referenced checker evidence.
            }
        }
    }

    private static void validateExactRational(
        HoleBinding binding,
        Hole hole
    ) {
        if (!ExactRationalDomain.DOMAIN_ID.equals(hole.domainId())) {
            throw new IllegalArgumentException(
                "unsupported exact-rational domain");
        }
        ExactRationalParseEvidence parsed =
            new ExactRationalDomain().parse(binding.canonicalValue());
        if (!parsed.exact()
                || !binding.canonicalValue().equals(
                    parsed.canonicalValue())) {
            throw new IllegalArgumentException(
                "rational binding must be canonical and exact");
        }
        ExactRational value = parsed.value().orElseThrow();
        int bits = Math.max(
            value.numerator().abs().bitLength(),
            value.denominator().bitLength());
        if (bits > hole.budget().maxScalarBits()) {
            throw new IllegalArgumentException(
                "rational binding exceeds scalar bit budget");
        }
    }

    private static void validateSign(
        HoleBinding binding
    ) {
        if (!binding.canonicalValue().equals("-1")
                && !binding.canonicalValue().equals("1")) {
            throw new IllegalArgumentException(
                "sign binding must be -1 or 1");
        }
    }

    private static void validateOccurrencePath(
        String value,
        int maxDepth
    ) {
        if (!OCCURRENCE_PATH.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "occurrence path is not canonical");
        }
        int depth = value.equals("root")
            ? 0
            : value.split("\\.").length;
        if (depth > maxDepth) {
            throw new IllegalArgumentException(
                "occurrence path exceeds depth budget");
        }
    }

    private static void validateOccurrencePair(
        String value,
        int maxDepth
    ) {
        String[] paths = value.split("\\|", -1);
        if (paths.length != 2) {
            throw new IllegalArgumentException(
                "occurrence pair requires two paths");
        }
        validateOccurrencePath(paths[0], maxDepth);
        validateOccurrencePath(paths[1], maxDepth);
        if (compareOccurrencePaths(paths[0], paths[1]) >= 0) {
            throw new IllegalArgumentException(
                "occurrence pair must be distinct and ordered");
        }
        if (occurrencesOverlap(paths[0], paths[1])) {
            throw new IllegalArgumentException(
                "occurrence pair paths must be disjoint");
        }
    }

    private static int compareOccurrencePaths(
        String left,
        String right
    ) {
        if (left.equals(right)) {
            return 0;
        }
        if (left.equals("root")) {
            return -1;
        }
        if (right.equals("root")) {
            return 1;
        }
        String[] leftSegments = left.split("\\.");
        String[] rightSegments = right.split("\\.");
        int commonLength = Math.min(
            leftSegments.length,
            rightSegments.length);
        for (int index = 0; index < commonLength; index++) {
            int segmentOrder = compareCanonicalIndex(
                leftSegments[index],
                rightSegments[index]);
            if (segmentOrder != 0) {
                return segmentOrder;
            }
        }
        return Integer.compare(
            leftSegments.length,
            rightSegments.length);
    }

    private static int compareCanonicalIndex(
        String left,
        String right
    ) {
        int lengthOrder = Integer.compare(left.length(), right.length());
        return lengthOrder != 0
            ? lengthOrder
            : left.compareTo(right);
    }

    private static boolean occurrencesOverlap(
        String left,
        String right
    ) {
        return left.equals("root")
            || right.equals("root")
            || left.startsWith(right + ".")
            || right.startsWith(left + ".");
    }

    private static void validateOutcome(
        ObligationOutcome outcome,
        Obligation obligation
    ) {
        if (!outcome.checkerCapability().equals(
                obligation.checkerCapability())) {
            throw new IllegalArgumentException(
                "checker capability differs from obligation");
        }
        if (!outcome.checkerRevisionHash().equals(
                obligation.checkerRevisionHash())) {
            throw new IllegalArgumentException(
                "checker revision differs from obligation");
        }
    }

    private static ResolutionState deriveState(
        List<String> requiredHoleIds,
        List<String> requiredObligationIds,
        List<HoleBinding> bindings,
        List<ObligationOutcome> outcomes
    ) {
        if (outcomes.stream().anyMatch(outcome ->
                outcome.status() != OutcomeStatus.CONFIRMED)) {
            return ResolutionState.BLOCKED;
        }
        Set<String> bound = Set.copyOf(
            bindings.stream().map(HoleBinding::holeId).toList());
        Set<String> decided = Set.copyOf(
            outcomes.stream()
                .map(ObligationOutcome::obligationId)
                .toList());
        return bound.equals(Set.copyOf(requiredHoleIds))
                && decided.equals(Set.copyOf(requiredObligationIds))
            ? ResolutionState.COMPLETE_REFERENCES
            : ResolutionState.PARTIAL;
    }

    private static void requireSubset(
        List<String> actual,
        List<String> required,
        String name
    ) {
        if (!new HashSet<>(required).containsAll(actual)) {
            throw new IllegalArgumentException(
                name + " contain unknown IDs");
        }
    }

    private static int utf8Length(
        String value
    ) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void requireSize(
        String value,
        int maximumBytes
    ) {
        if (utf8Length(value) > maximumBytes) {
            throw new IllegalArgumentException(
                "canonical resolution exceeds byte limit");
        }
    }

    public enum ResolutionState {
        PARTIAL,
        BLOCKED,
        COMPLETE_REFERENCES
    }

    public enum OutcomeStatus {
        CONFIRMED,
        REFUTED,
        UNKNOWN,
        UNSUPPORTED,
        BUDGET_INCONCLUSIVE,
        ERROR
    }

    public record HoleBinding(
        String holeId,
        HoleSort sort,
        String canonicalValue,
        String evidenceHash
    ) {
        public HoleBinding {
            holeId = SchematicProofPlan.requireId(
                holeId,
                "holeId");
            sort = Objects.requireNonNull(sort, "sort");
            if (canonicalValue == null
                    || canonicalValue.isBlank()
                    || !canonicalValue.equals(
                        canonicalValue.strip())) {
                throw new IllegalArgumentException(
                    "canonicalValue must be nonblank and trimmed");
            }
            if (canonicalValue.chars().anyMatch(
                    Character::isISOControl)) {
                throw new IllegalArgumentException(
                    "canonicalValue contains a control character");
            }
            if (utf8Length(canonicalValue) > MAX_VALUE_BYTES) {
                throw new IllegalArgumentException(
                    "canonicalValue exceeds absolute byte limit");
            }
            evidenceHash = SchematicProofPlan.requireSha256(
                evidenceHash,
                "evidenceHash");
        }
    }

    public record ObligationOutcome(
        String obligationId,
        OutcomeStatus status,
        String checkerCapability,
        String checkerRevisionHash,
        String checkerExecutionHash,
        String detailCode
    ) {
        public ObligationOutcome {
            obligationId = SchematicProofPlan.requireId(
                obligationId,
                "obligationId");
            status = Objects.requireNonNull(status, "status");
            checkerCapability = SchematicProofPlan.requireToken(
                checkerCapability,
                "checkerCapability");
            checkerRevisionHash =
                SchematicProofPlan.requireSha256(
                    checkerRevisionHash,
                    "checkerRevisionHash");
            checkerExecutionHash =
                SchematicProofPlan.requireSha256(
                    checkerExecutionHash,
                    "checkerExecutionHash");
            if (detailCode == null
                    || !DETAIL_CODE.matcher(detailCode).matches()) {
                throw new IllegalArgumentException(
                    "detailCode must be an uppercase stable code");
            }
        }
    }
}
