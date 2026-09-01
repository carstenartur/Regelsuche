package de.regelsuche.benchmark.polynomial;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Complete target-blind measurements for one typed candidate result. */
public record PolynomialTheoryUtilityCandidateMeasurements(
    String measurementId,
    PolynomialTheoryUtilityCandidateResult result,
    String formationAssumptionSetId,
    List<String> normalizedAssumptions,
    int sourceAstNodeCount,
    List<PolynomialTheoryUtilityTransitionTrace> transitionTraces,
    List<PolynomialTheoryUtilityFactorizationAttempt> factorizationAttempts,
    List<PolynomialTheoryUtilityCacheEvent> cacheEvents
) {
    public static final String SCHEMA =
        "regelsuche.polynomial-theory-utility-candidate-measurements/v1";
    private static final Pattern SHA_256 =
        Pattern.compile("sha256:[0-9a-f]{64}");
    private static final ThreadLocal<ExpressionCanonicalizer> CANONICALIZER =
        ThreadLocal.withInitial(ExpressionCanonicalizer::new);
    private static final Map<
        String,
        PolynomialTheoryUtilityCaseCorpus.FormationCase
    > FORMATION_CASES = loadFormationCases();

    public PolynomialTheoryUtilityCandidateMeasurements {
        measurementId = requireHash(measurementId, "measurementId");
        result = Objects.requireNonNull(result, "result");
        var formationCase = formationCase(result.input().caseId());
        formationAssumptionSetId = requireText(
            formationAssumptionSetId,
            "formationAssumptionSetId"
        );
        transitionTraces = immutable(transitionTraces, "transitionTraces");
        normalizedAssumptions = requireNormalizedAssumptions(
            normalizedAssumptions,
            transitionTraces
        );
        factorizationAttempts = immutable(
            factorizationAttempts,
            "factorizationAttempts"
        );
        cacheEvents = immutable(cacheEvents, "cacheEvents");

        if (!result.sourceRootExpression().equals(
                formationCase.sourceExpression())
                || !formationAssumptionSetId.equals(
                    formationCase.assumptionSetId())
                || sourceAstNodeCount < 1
                || sourceAstNodeCount != nodeCount(
                    result.sourceRootExpression()
                )) {
            throw new IllegalArgumentException(
                "candidate measurements differ from frozen formation"
            );
        }

        requireVisibleAssumptions(
            formationAssumptionSetId,
            normalizedAssumptions
        );
        var profile = PolynomialTheoryUtilityExecutionInputs.profile(
            result.input().profileId()
        );
        PolynomialTheoryUtilityCandidateMeasurementValidator.validate(
            result,
            profile,
            sourceAstNodeCount,
            transitionTraces,
            factorizationAttempts,
            cacheEvents
        );

        if (!measurementId.equals(identity(
                result,
                formationAssumptionSetId,
                normalizedAssumptions,
                sourceAstNodeCount,
                transitionTraces,
                factorizationAttempts,
                cacheEvents))) {
            throw new IllegalArgumentException(
                "candidate measurement identity differs from its fields"
            );
        }
    }

    public static PolynomialTheoryUtilityCandidateMeasurements create(
        PolynomialTheoryUtilityCandidateResult result,
        List<PolynomialTheoryUtilityTransitionTrace> transitionTraces,
        List<PolynomialTheoryUtilityFactorizationAttempt> factorizationAttempts,
        List<PolynomialTheoryUtilityCacheEvent> cacheEvents
    ) {
        var retainedResult = Objects.requireNonNull(result, "result");
        var formationCase = formationCase(retainedResult.input().caseId());
        List<PolynomialTheoryUtilityTransitionTrace> traces = immutable(
            transitionTraces,
            "transitionTraces"
        );
        List<String> normalized = traceAssumptions(traces);
        List<PolynomialTheoryUtilityFactorizationAttempt> attempts = immutable(
            factorizationAttempts,
            "factorizationAttempts"
        );
        List<PolynomialTheoryUtilityCacheEvent> events = immutable(
            cacheEvents,
            "cacheEvents"
        );
        int sourceNodes = nodeCount(retainedResult.sourceRootExpression());
        return new PolynomialTheoryUtilityCandidateMeasurements(
            identity(
                retainedResult,
                formationCase.assumptionSetId(),
                normalized,
                sourceNodes,
                traces,
                attempts,
                events
            ),
            retainedResult,
            formationCase.assumptionSetId(),
            normalized,
            sourceNodes,
            traces,
            attempts,
            events
        );
    }

    public String schema() {
        return SCHEMA;
    }

    public int generatedTransitionCount() {
        return result.transitions().size();
    }

    public List<Integer> pathDepths() {
        return transitionTraces.stream()
            .map(PolynomialTheoryUtilityTransitionTrace::pathDepth)
            .toList();
    }

    public int totalPathDepth() {
        return sum(pathDepths());
    }

    public List<Integer> primitiveExpansionLengths() {
        return transitionTraces.stream()
            .map(PolynomialTheoryUtilityTransitionTrace::primitiveExpansionLength)
            .toList();
    }

    public int totalPrimitiveExpansionLength() {
        return sum(primitiveExpansionLengths());
    }

    public List<Integer> transformedAstNodeCounts() {
        return transitionTraces.stream()
            .map(PolynomialTheoryUtilityTransitionTrace::transformedAstNodeCount)
            .toList();
    }

    public List<Integer> astNodeGrowths() {
        return transitionTraces.stream()
            .map(PolynomialTheoryUtilityTransitionTrace::astNodeGrowth)
            .toList();
    }

    public int factorizationRequestCount() {
        return factorizationAttempts.size();
    }

    public int factorizationCandidateCount() {
        int total = 0;
        for (var attempt : factorizationAttempts) {
            total = Math.addExact(total, attempt.candidateCount());
        }
        return total;
    }

    public int cacheHitCount() {
        return countEvents(
            value -> value.kind()
                == PolynomialTheoryUtilityCacheEvent.Kind.LOOKUP_HIT
        );
    }

    public int cacheMissCount() {
        return countEvents(
            value -> value.kind()
                == PolynomialTheoryUtilityCacheEvent.Kind.LOOKUP_MISS
        );
    }

    public int cacheInsertionCount() {
        return countEvents(
            value -> value.kind()
                == PolynomialTheoryUtilityCacheEvent.Kind.INSERTION
        );
    }

    public int cacheEvictionCount() {
        return countEvents(
            value -> value.kind()
                == PolynomialTheoryUtilityCacheEvent.Kind.EVICTION
        );
    }

    public int cacheReplayCount() {
        return countEvents(
            value -> value.kind()
                == PolynomialTheoryUtilityCacheEvent.Kind.REPLAY
        );
    }

    public List<String> primitiveRuleIds() {
        return transitionTraces.stream()
            .flatMap(value -> value.primitiveSteps().stream())
            .map(PolynomialTheoryUtilityTransitionTrace.PrimitiveStep::ruleId)
            .toList();
    }

    public void validateAgainst(
        PolynomialTheoryUtilityCandidateResult expected
    ) {
        if (!result.equals(Objects.requireNonNull(expected, "expected"))) {
            throw new IllegalArgumentException(
                "candidate measurements refer to another result"
            );
        }
    }

    private int countEvents(
        Predicate<PolynomialTheoryUtilityCacheEvent> predicate
    ) {
        return Math.toIntExact(cacheEvents.stream().filter(predicate).count());
    }

    private static String identity(
        PolynomialTheoryUtilityCandidateResult result,
        String formationAssumptionSetId,
        List<String> normalizedAssumptions,
        int sourceAstNodeCount,
        List<PolynomialTheoryUtilityTransitionTrace> traces,
        List<PolynomialTheoryUtilityFactorizationAttempt> attempts,
        List<PolynomialTheoryUtilityCacheEvent> events
    ) {
        StringBuilder material = new StringBuilder();
        append(material, SCHEMA);
        append(material, PolynomialTheoryUtilityPreregistration.STUDY_ID);
        append(
            material,
            Objects.requireNonNull(result, "result").resultId()
        );
        append(
            material,
            requireText(
                formationAssumptionSetId,
                "formationAssumptionSetId"
            )
        );
        append(material, Integer.toString(sourceAstNodeCount));
        append(material, Integer.toString(normalizedAssumptions.size()));
        normalizedAssumptions.forEach(value -> append(
            material,
            requireText(value, "normalizedAssumption")
        ));
        appendIds(material, traces.stream()
            .map(PolynomialTheoryUtilityTransitionTrace::traceId)
            .toList());
        appendIds(material, attempts.stream()
            .map(PolynomialTheoryUtilityFactorizationAttempt::attemptId)
            .toList());
        appendIds(material, events.stream()
            .map(PolynomialTheoryUtilityCacheEvent::eventId)
            .toList());
        return PolynomialTheoryUtilityExecutionIdentity.sha256(
            material.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void appendIds(StringBuilder target, List<String> values) {
        append(target, Integer.toString(values.size()));
        values.forEach(value -> append(target, requireHash(value, "identity")));
    }

    private static int sum(List<Integer> values) {
        int total = 0;
        for (Integer value : values) {
            total = Math.addExact(total, Objects.requireNonNull(value, "value"));
        }
        return total;
    }

    private static int nodeCount(String expression) {
        return CANONICALIZER.get().astNodeCount(expression);
    }

    private static List<String> traceAssumptions(
        List<PolynomialTheoryUtilityTransitionTrace> traces
    ) {
        List<String> values = new ArrayList<>();
        traces.forEach(value -> values.addAll(
            Objects.requireNonNull(value, "trace").normalizedAssumptions()
        ));
        return AssumptionSignature.ofExpressions(values)
            .normalizedAssumptions();
    }

    private static List<String> requireNormalizedAssumptions(
        List<String> values,
        List<PolynomialTheoryUtilityTransitionTrace> traces
    ) {
        List<String> retained = List.copyOf(
            Objects.requireNonNull(values, "normalizedAssumptions")
        );
        if (!retained.equals(traceAssumptions(traces))) {
            throw new IllegalArgumentException(
                "candidate assumptions differ from transition traces"
            );
        }
        return retained;
    }

    private static void requireVisibleAssumptions(
        String assumptionSetId,
        List<String> assumptions
    ) {
        boolean valid = switch (assumptionSetId) {
            case "NONE", "N_POSITIVE_INTEGER" -> assumptions.isEmpty();
            case "X_NONZERO" -> assumptions.isEmpty()
                || assumptions.equals(List.of("x != 0"));
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                "trace assumptions exceed the frozen visible assumption set"
            );
        }
    }

    private static <T> List<T> immutable(List<T> values, String name) {
        return List.copyOf(Objects.requireNonNull(values, name));
    }

    private static Map<
        String,
        PolynomialTheoryUtilityCaseCorpus.FormationCase
    > loadFormationCases() {
        Map<String, PolynomialTheoryUtilityCaseCorpus.FormationCase> values =
            new LinkedHashMap<>();
        for (var value : PolynomialTheoryUtilityCaseCorpus.load().cases()) {
            if (values.putIfAbsent(value.caseId(), value) != null) {
                throw new IllegalStateException(
                    "formation corpus repeats a case identity"
                );
            }
        }
        return Collections.unmodifiableMap(values);
    }

    private static PolynomialTheoryUtilityCaseCorpus.FormationCase
            formationCase(String caseId) {
        var value = FORMATION_CASES.get(requireText(caseId, "caseId"));
        if (value == null) {
            throw new IllegalArgumentException(
                "unknown frozen polynomial utility case: " + caseId
            );
        }
        return value;
    }

    private static String requireHash(String value, String name) {
        String text = requireText(value, name);
        if (!SHA_256.matcher(text).matches()) {
            throw new IllegalArgumentException(name + " is not SHA-256");
        }
        return text;
    }

    private static String requireText(String value, String name) {
        String text = Objects.requireNonNull(value, name);
        if (text.isBlank()) {
            throw new IllegalArgumentException(
                name + " must not be blank"
            );
        }
        return text;
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }
}
