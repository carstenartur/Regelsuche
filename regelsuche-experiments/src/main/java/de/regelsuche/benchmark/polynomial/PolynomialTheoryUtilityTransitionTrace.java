package de.regelsuche.benchmark.polynomial;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Ordered primitive lineage and structural measurements for one transition.
 *
 * <p>The trace is an evidence companion to an already validated transition. It
 * retains path-edge boundaries instead of treating a primitive expansion as an
 * unordered rule set.</p>
 */
public record PolynomialTheoryUtilityTransitionTrace(
    String traceId,
    PolynomialTheoryUtilityTransitionOutcome transition,
    int pathDepth,
    List<PrimitiveStep> primitiveSteps,
    List<String> normalizedAssumptions,
    int sourceAstNodeCount,
    int transformedAstNodeCount
) {
    public static final String SCHEMA =
        "regelsuche.polynomial-theory-utility-transition-trace/v1";
    private static final Pattern SHA_256 =
        Pattern.compile("sha256:[0-9a-f]{64}");
    private static final ThreadLocal<ExpressionCanonicalizer> CANONICALIZER =
        ThreadLocal.withInitial(ExpressionCanonicalizer::new);

    public PolynomialTheoryUtilityTransitionTrace {
        traceId = requireHash(traceId, "traceId");
        transition = Objects.requireNonNull(transition, "transition");
        if (pathDepth < 1) {
            throw new IllegalArgumentException(
                "pathDepth must be positive"
            );
        }
        primitiveSteps = List.copyOf(
            Objects.requireNonNull(primitiveSteps, "primitiveSteps")
        );
        normalizedAssumptions = requireNormalizedAssumptions(
            normalizedAssumptions
        );
        if (sourceAstNodeCount < 1
                || transformedAstNodeCount < 1
                || sourceAstNodeCount != nodeCount(
                    transition.sourceRootExpression()
                )
                || transformedAstNodeCount != nodeCount(
                    transition.transformedRootExpression()
                )) {
            throw new IllegalArgumentException(
                "transition AST measurements differ from expressions"
            );
        }
        requirePrimitiveSteps(
            transition,
            pathDepth,
            primitiveSteps
        );
        if (!traceId.equals(identity(
                transition,
                pathDepth,
                primitiveSteps,
                normalizedAssumptions,
                sourceAstNodeCount,
                transformedAstNodeCount))) {
            throw new IllegalArgumentException(
                "transition trace identity differs from its fields"
            );
        }
    }

    public static PolynomialTheoryUtilityTransitionTrace create(
        PolynomialTheoryUtilityTransitionOutcome transition,
        int pathDepth,
        List<PrimitiveStep> primitiveSteps,
        List<String> assumptions
    ) {
        var retainedTransition = Objects.requireNonNull(
            transition,
            "transition"
        );
        List<PrimitiveStep> steps = List.copyOf(
            Objects.requireNonNull(primitiveSteps, "primitiveSteps")
        );
        List<String> normalized = canonicalAssumptions(assumptions);
        int sourceNodes = nodeCount(
            retainedTransition.sourceRootExpression()
        );
        int transformedNodes = nodeCount(
            retainedTransition.transformedRootExpression()
        );
        return new PolynomialTheoryUtilityTransitionTrace(
            identity(
                retainedTransition,
                pathDepth,
                steps,
                normalized,
                sourceNodes,
                transformedNodes
            ),
            retainedTransition,
            pathDepth,
            steps,
            normalized,
            sourceNodes,
            transformedNodes
        );
    }

    public String schema() {
        return SCHEMA;
    }

    public int primitiveExpansionLength() {
        return primitiveSteps.size();
    }

    public int astNodeGrowth() {
        return transformedAstNodeCount - sourceAstNodeCount;
    }

    public void validateAgainst(
        int expectedIndex,
        PolynomialTheoryUtilityTransitionOutcome expectedTransition,
        int expectedSourceAstNodeCount
    ) {
        if (transition.transitionIndex() != expectedIndex
                || !transition.equals(
                    Objects.requireNonNull(
                        expectedTransition,
                        "expectedTransition"
                    )
                )
                || sourceAstNodeCount != expectedSourceAstNodeCount) {
            throw new IllegalArgumentException(
                "transition trace refers to another result transition"
            );
        }
    }

    private static void requirePrimitiveSteps(
        PolynomialTheoryUtilityTransitionOutcome transition,
        int pathDepth,
        List<PrimitiveStep> steps
    ) {
        if (steps.isEmpty()
                || steps.size() > transition.work().primitiveWork()) {
            throw new IllegalArgumentException(
                "primitive lineage differs from retained work"
            );
        }
        int expectedEdge = 0;
        Set<String> identities = new HashSet<>();
        for (int index = 0; index < steps.size(); index++) {
            var step = Objects.requireNonNull(
                steps.get(index),
                "primitiveStep"
            );
            step.validateAgainst(index, transition);
            if (step.pathEdgeIndex() < expectedEdge
                    || step.pathEdgeIndex() > expectedEdge + 1) {
                throw new IllegalArgumentException(
                    "primitive path edge order has a gap"
                );
            }
            if (step.pathEdgeIndex() == expectedEdge + 1) {
                expectedEdge++;
            }
            if (!identities.add(step.stepId())) {
                throw new IllegalArgumentException(
                    "transition trace repeats a primitive step"
                );
            }
        }
        if (steps.getFirst().pathEdgeIndex() != 0
                || expectedEdge + 1 != pathDepth) {
            throw new IllegalArgumentException(
                "pathDepth differs from primitive edge lineage"
            );
        }
    }

    private static String identity(
        PolynomialTheoryUtilityTransitionOutcome transition,
        int pathDepth,
        List<PrimitiveStep> primitiveSteps,
        List<String> normalizedAssumptions,
        int sourceAstNodeCount,
        int transformedAstNodeCount
    ) {
        StringBuilder material = new StringBuilder();
        append(material, SCHEMA);
        append(
            material,
            Objects.requireNonNull(
                transition,
                "transition"
            ).transitionId()
        );
        append(material, Integer.toString(pathDepth));
        append(material, Integer.toString(sourceAstNodeCount));
        append(material, Integer.toString(transformedAstNodeCount));
        append(material, Integer.toString(primitiveSteps.size()));
        primitiveSteps.forEach(value -> append(
            material,
            Objects.requireNonNull(value, "primitiveStep").stepId()
        ));
        append(
            material,
            Integer.toString(normalizedAssumptions.size())
        );
        normalizedAssumptions.forEach(value -> append(
            material,
            requireText(value, "normalizedAssumption")
        ));
        return hash(material.toString());
    }

    private static List<String> canonicalAssumptions(
        List<String> values
    ) {
        return AssumptionSignature.ofExpressions(
            Objects.requireNonNull(values, "assumptions")
        ).normalizedAssumptions();
    }

    private static List<String> requireNormalizedAssumptions(
        List<String> values
    ) {
        List<String> retained = List.copyOf(
            Objects.requireNonNull(values, "normalizedAssumptions")
        );
        if (!retained.equals(canonicalAssumptions(retained))) {
            throw new IllegalArgumentException(
                "assumptions are not normalized and sorted"
            );
        }
        return retained;
    }

    private static int nodeCount(String expression) {
        return CANONICALIZER.get().astNodeCount(expression);
    }

    private static String hash(String material) {
        return PolynomialTheoryUtilityExecutionIdentity.sha256(
            material.getBytes(StandardCharsets.UTF_8)
        );
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

    public record PrimitiveStep(
        String stepId,
        int primitiveIndex,
        int pathEdgeIndex,
        String transitionId,
        String ruleId,
        String evidenceHash
    ) {
        public PrimitiveStep {
            stepId = requireHash(stepId, "stepId");
            if (primitiveIndex < 0 || pathEdgeIndex < 0) {
                throw new IllegalArgumentException(
                    "primitive indices must be non-negative"
                );
            }
            transitionId = requireHash(
                transitionId,
                "transitionId"
            );
            ruleId = requireText(ruleId, "ruleId");
            evidenceHash = requireHash(
                evidenceHash,
                "evidenceHash"
            );
            if (!stepId.equals(identity(
                    primitiveIndex,
                    pathEdgeIndex,
                    transitionId,
                    ruleId,
                    evidenceHash))) {
                throw new IllegalArgumentException(
                    "primitive step identity differs from its fields"
                );
            }
        }

        public static PrimitiveStep create(
            PolynomialTheoryUtilityTransitionOutcome transition,
            int primitiveIndex,
            int pathEdgeIndex,
            String ruleId,
            String evidenceHash
        ) {
            String transitionId = Objects.requireNonNull(
                transition,
                "transition"
            ).transitionId();
            return new PrimitiveStep(
                identity(
                    primitiveIndex,
                    pathEdgeIndex,
                    transitionId,
                    ruleId,
                    evidenceHash
                ),
                primitiveIndex,
                pathEdgeIndex,
                transitionId,
                ruleId,
                evidenceHash
            );
        }

        private void validateAgainst(
            int expectedIndex,
            PolynomialTheoryUtilityTransitionOutcome transition
        ) {
            if (primitiveIndex != expectedIndex
                    || !transitionId.equals(
                        transition.transitionId()
                    )) {
                throw new IllegalArgumentException(
                    "primitive step refers to another transition"
                );
            }
        }

        private static String identity(
            int primitiveIndex,
            int pathEdgeIndex,
            String transitionId,
            String ruleId,
            String evidenceHash
        ) {
            StringBuilder material = new StringBuilder();
            append(
                material,
                "regelsuche.polynomial-theory-utility-"
                    + "primitive-step/v1"
            );
            append(material, Integer.toString(primitiveIndex));
            append(material, Integer.toString(pathEdgeIndex));
            append(
                material,
                requireHash(transitionId, "transitionId")
            );
            append(material, requireText(ruleId, "ruleId"));
            append(
                material,
                requireHash(evidenceHash, "evidenceHash")
            );
            return hash(material.toString());
        }
    }
}
