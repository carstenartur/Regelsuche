package de.regelsuche.assumption;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic, content-addressed portfolio of assumption evaluators.
 *
 * <p>A conflict between decisive evaluator results fails closed to
 * {@link AssumptionTruthValue#UNKNOWN}. Unsupported, timed-out and technical
 * outcomes are retained as typed evidence and are never treated as true.</p>
 */
public final class AssumptionEvaluatorPortfolio {
    private static final String REVISION =
        "regelsuche.assumption-evaluator-portfolio/v1";

    private final List<EvaluatorBinding> bindings;
    private final List<AssumptionEvaluator> evaluators;
    private final String contentHash;

    public AssumptionEvaluatorPortfolio(
        List<? extends AssumptionEvaluator> evaluators
    ) {
        Objects.requireNonNull(evaluators, "evaluators");
        if (evaluators.isEmpty()) {
            throw new IllegalArgumentException(
                "at least one assumption evaluator is required");
        }
        this.bindings = evaluators.stream()
            .map(evaluator -> Objects.requireNonNull(
                evaluator, "assumption evaluator"))
            .map(AssumptionEvaluatorPortfolio::bind)
            .sorted(Comparator
                .comparing(EvaluatorBinding::id)
                .thenComparing(EvaluatorBinding::revision))
            .toList();
        rejectDuplicateIds(bindings);
        this.evaluators = bindings.stream()
            .map(EvaluatorBinding::evaluator)
            .toList();
        this.contentHash = sha256(canonicalDescriptor());
    }

    public static AssumptionEvaluatorPortfolio localOnly() {
        return new AssumptionEvaluatorPortfolio(
            List.of(KnownAssumptionEvaluator.INSTANCE));
    }

    public String contentHash() {
        return contentHash;
    }

    public List<AssumptionEvaluator> evaluators() {
        return evaluators;
    }

    public AssumptionEvaluation evaluate(
        Assumption requiredAssumption,
        AssumptionContext context
    ) {
        Objects.requireNonNull(context, "context");
        return evaluate(requiredAssumption, context.snapshot());
    }

    public AssumptionEvaluation evaluate(
        Assumption requiredAssumption,
        List<Assumption> knownAssumptions
    ) {
        Objects.requireNonNull(requiredAssumption, "requiredAssumption");
        Objects.requireNonNull(knownAssumptions, "knownAssumptions");
        List<Assumption> contextSnapshot = knownAssumptions.stream()
            .map(assumption -> Objects.requireNonNull(
                assumption, "known assumption"))
            .toList();
        List<AssumptionEvaluationEvidence> evidence = new ArrayList<>();
        for (EvaluatorBinding binding : bindings) {
            AssumptionEvaluationEvidence item = Objects.requireNonNull(
                binding.evaluator().evaluate(
                    requiredAssumption, contextSnapshot),
                "assumption evaluator evidence");
            validateAttribution(binding, item);
            evidence.add(item);
        }
        return AssumptionEvaluation.from(
            requiredAssumption,
            AssumptionSignature.ofAssumptions(contextSnapshot),
            contentHash,
            evidence
        );
    }

    private String canonicalDescriptor() {
        StringBuilder descriptor = new StringBuilder();
        append(descriptor, REVISION);
        append(descriptor, Integer.toString(bindings.size()));
        for (EvaluatorBinding binding : bindings) {
            append(descriptor, binding.id());
            append(descriptor, binding.revision());
            append(descriptor, binding.implementationClass());
        }
        return descriptor.toString();
    }

    private static EvaluatorBinding bind(AssumptionEvaluator evaluator) {
        return new EvaluatorBinding(
            evaluator,
            requireText(evaluator.id(), "assumption evaluator id"),
            requireText(
                evaluator.revision(), "assumption evaluator revision"),
            evaluator.getClass().getName()
        );
    }

    private static void rejectDuplicateIds(
        List<EvaluatorBinding> bindings
    ) {
        Set<String> ids = new HashSet<>();
        for (EvaluatorBinding binding : bindings) {
            if (!ids.add(binding.id())) {
                throw new IllegalArgumentException(
                    "duplicate assumption evaluator id: " + binding.id());
            }
        }
    }

    private static void validateAttribution(
        EvaluatorBinding binding,
        AssumptionEvaluationEvidence evidence
    ) {
        if (!binding.id().equals(evidence.evaluatorId())) {
            throw new IllegalArgumentException(
                "assumption evaluator returned evidence for another id");
        }
        if (!binding.revision().equals(evidence.evaluatorRevision())) {
            throw new IllegalArgumentException(
                "assumption evaluator returned evidence for another revision");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static void append(StringBuilder descriptor, String value) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(value, "value");
        descriptor.append(value.length()).append(':').append(value);
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record EvaluatorBinding(
        AssumptionEvaluator evaluator,
        String id,
        String revision,
        String implementationClass
    ) {
    }
}
