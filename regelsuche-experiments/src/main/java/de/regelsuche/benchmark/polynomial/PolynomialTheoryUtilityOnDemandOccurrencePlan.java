package de.regelsuche.benchmark.polynomial;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves the frozen occurrence layout and distributes one input row's work
 * authorities before the native on-demand profile executes.
 *
 * <p>The plan uses only the visible formation case, the frozen execution input
 * and the fixed profile/checkpoint contracts. It does not inspect mathematical
 * outcomes or open the sealed qualification resource.</p>
 */
public final class PolynomialTheoryUtilityOnDemandOccurrencePlan {
    public static final String SCHEMA =
        "regelsuche.polynomial-theory-utility-on-demand-occurrence-plan/v1";
    public static final String PROFILE_ID =
        "ON_DEMAND_VERIFIED_FACTORIZATION";
    public static final String ADAPTER_ID =
        "regelsuche.polynomial-theory-utility."
            + "on-demand-verified-factorization/v1";

    private PolynomialTheoryUtilityOnDemandOccurrencePlan() {
    }

    public static Plan create(
        PolynomialTheoryUtilityExecutionInput input,
        PolynomialTheoryUtilityCaseCorpus.FormationCase formationCase
    ) {
        var frozenInput = Objects.requireNonNull(input, "input");
        var studyCase = Objects.requireNonNull(
            formationCase,
            "formationCase"
        );
        requireProfile(frozenInput);
        requireCaseAndCheckpoint(frozenInput, studyCase);

        List<List<Integer>> paths = paths(studyCase.occurrenceLayout());
        if (paths.size() != studyCase.reuseCount()
                || paths.stream().anyMatch(path ->
                    path.size() != studyCase.occurrenceDepth())) {
            throw new IllegalArgumentException(
                "occurrence layout differs from the frozen formation case"
            );
        }

        List<Integer> primitive = splitPositive(
            frozenInput.admittedPrimitiveWork(),
            paths.size(),
            "primitive"
        );
        List<Integer> mechanical = splitPositive(
            frozenInput.totalMechanicalWork(),
            paths.size(),
            "mechanical"
        );
        List<Integer> factorization = splitPositive(
            frozenInput.factorizationWork(),
            paths.size(),
            "factorization"
        );

        List<Occurrence> occurrences = new ArrayList<>(paths.size());
        for (int index = 0; index < paths.size(); index++) {
            occurrences.add(new Occurrence(
                index,
                paths.get(index),
                primitive.get(index),
                mechanical.get(index),
                factorization.get(index)
            ));
        }
        return Plan.create(frozenInput, studyCase, occurrences);
    }

    private static void requireProfile(
        PolynomialTheoryUtilityExecutionInput input
    ) {
        PolynomialTheoryUtilityExecutionProfile profile =
            PolynomialTheoryUtilityExecutionInputs.profile(
                input.profileId()
            );
        if (!PROFILE_ID.equals(input.profileId())
                || !ADAPTER_ID.equals(input.adapterId())
                || !ADAPTER_ID.equals(profile.adapterId())
                || !"DECLARED_UNIVARIATE_ZX_QX".equals(profile.scope())
                || !"ON_DEMAND".equals(profile.factorizationMode())
                || !"regelsuche.factorization."
                    .concat("native-univariate-rational/v1")
                    .equals(profile.engineId())
                || !PolynomialTheoryUtilityExecutionPlan.TRANSFORMATION_ID
                    .equals(profile.transformationId())
                || !"DISABLED".equals(profile.cacheMode())
                || !"NONE".equals(profile.fallbackMode())
                || !"EXPLICIT_INDEX_ASCENDING".equals(
                    profile.candidateSelection()
                )) {
            throw new IllegalArgumentException(
                "execution input is not the frozen native on-demand profile"
            );
        }
    }

    private static void requireCaseAndCheckpoint(
        PolynomialTheoryUtilityExecutionInput input,
        PolynomialTheoryUtilityCaseCorpus.FormationCase formationCase
    ) {
        if (!input.caseId().equals(formationCase.caseId())) {
            throw new IllegalArgumentException(
                "execution input and formation case differ"
            );
        }
        PolynomialTheoryUtilityExecutionCheckpoint checkpoint =
            PolynomialTheoryUtilityExecutionPlan.CHECKPOINTS.stream()
                .filter(value -> value.checkpointId().equals(
                    input.checkpointId()
                ))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                    "execution input checkpoint is not frozen"
                ));
        if (input.admittedPrimitiveWork()
                != PolynomialTheoryUtilityExecutionPlan.scale(
                    formationCase.admittedPrimitiveWork(),
                    checkpoint
                )
                || input.totalMechanicalWork()
                    != PolynomialTheoryUtilityExecutionPlan.scale(
                        formationCase.totalMechanicalWork(),
                        checkpoint
                    )
                || input.factorizationWork()
                    != PolynomialTheoryUtilityExecutionPlan.scale(
                        formationCase.factorizationWork(),
                        checkpoint
                    )) {
            throw new IllegalArgumentException(
                "execution input work differs from its frozen checkpoint"
            );
        }
    }

    private static List<List<Integer>> paths(String layout) {
        return switch (Objects.requireNonNull(layout, "layout")) {
            case "ROOT" -> List.of(List.of());
            case "NESTED_RIGHT" -> List.of(List.of(1));
            case "TWO_IDENTICAL_SIBLINGS" -> List.of(
                List.of(0),
                List.of(1)
            );
            case "FOUR_IDENTICAL_LEAVES" -> List.of(
                List.of(0, 0),
                List.of(0, 1),
                List.of(1, 0),
                List.of(1, 1)
            );
            default -> throw new IllegalArgumentException(
                "unsupported frozen occurrence layout: " + layout
            );
        };
    }

    private static List<Integer> splitPositive(
        int total,
        int parts,
        String role
    ) {
        if (parts < 1 || total < parts) {
            throw new IllegalArgumentException(
                role + " authority cannot cover every frozen occurrence"
            );
        }
        int quotient = total / parts;
        int remainder = total % parts;
        List<Integer> result = new ArrayList<>(parts);
        for (int index = 0; index < parts; index++) {
            result.add(quotient + (index < remainder ? 1 : 0));
        }
        return List.copyOf(result);
    }

    /** One independent occurrence attempt and its disjoint work authority. */
    public record Occurrence(
        int occurrenceIndex,
        List<Integer> path,
        int admittedPrimitiveWork,
        int totalMechanicalWork,
        int factorizationWork
    ) {
        public Occurrence {
            if (occurrenceIndex < 0) {
                throw new IllegalArgumentException(
                    "occurrence index must be non-negative"
                );
            }
            path = List.copyOf(Objects.requireNonNull(path, "path"));
            if (path.stream().anyMatch(value ->
                    value == null || value < 0)) {
                throw new IllegalArgumentException(
                    "occurrence path is invalid"
                );
            }
            if (factorizationWork < 1
                    || admittedPrimitiveWork < factorizationWork
                    || totalMechanicalWork < admittedPrimitiveWork) {
                throw new IllegalArgumentException(
                    "occurrence work authorities are invalid"
                );
            }
        }

        public String pathKey() {
            return path.isEmpty()
                ? "root"
                : path.stream()
                    .map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining("."));
        }

        private void appendIdentityMaterial(StringBuilder target) {
            append(target, Integer.toString(occurrenceIndex));
            append(target, pathKey());
            append(target, Integer.toString(admittedPrimitiveWork));
            append(target, Integer.toString(totalMechanicalWork));
            append(target, Integer.toString(factorizationWork));
        }
    }

    /** Content-addressed target-blind plan for one frozen input row. */
    public record Plan(
        String planId,
        String executionInputId,
        String caseId,
        String sourceExpression,
        String declaredDomain,
        String assumptionSetId,
        String occurrenceLayout,
        int admittedPrimitiveWork,
        int totalMechanicalWork,
        int factorizationWork,
        List<Occurrence> occurrences
    ) {
        public Plan {
            planId = requireHash(planId, "planId");
            executionInputId = requireHash(
                executionInputId,
                "executionInputId"
            );
            caseId = requireText(caseId, "caseId");
            sourceExpression = requireText(
                sourceExpression,
                "sourceExpression"
            );
            declaredDomain = requireText(declaredDomain, "declaredDomain");
            assumptionSetId = requireText(
                assumptionSetId,
                "assumptionSetId"
            );
            occurrenceLayout = requireText(
                occurrenceLayout,
                "occurrenceLayout"
            );
            occurrences = List.copyOf(
                Objects.requireNonNull(occurrences, "occurrences")
            );
            requireOccurrenceSequence(occurrences);
            requireAuthorityTotals(
                admittedPrimitiveWork,
                totalMechanicalWork,
                factorizationWork,
                occurrences
            );
            if (!planId.equals(identity(
                    executionInputId,
                    caseId,
                    sourceExpression,
                    declaredDomain,
                    assumptionSetId,
                    occurrenceLayout,
                    admittedPrimitiveWork,
                    totalMechanicalWork,
                    factorizationWork,
                    occurrences))) {
                throw new IllegalArgumentException(
                    "occurrence-plan identity differs from its fields"
                );
            }
        }

        private static Plan create(
            PolynomialTheoryUtilityExecutionInput input,
            PolynomialTheoryUtilityCaseCorpus.FormationCase formationCase,
            List<Occurrence> occurrences
        ) {
            String identity = identity(
                input.inputId(),
                formationCase.caseId(),
                formationCase.sourceExpression(),
                formationCase.declaredDomain(),
                formationCase.assumptionSetId(),
                formationCase.occurrenceLayout(),
                input.admittedPrimitiveWork(),
                input.totalMechanicalWork(),
                input.factorizationWork(),
                occurrences
            );
            return new Plan(
                identity,
                input.inputId(),
                formationCase.caseId(),
                formationCase.sourceExpression(),
                formationCase.declaredDomain(),
                formationCase.assumptionSetId(),
                formationCase.occurrenceLayout(),
                input.admittedPrimitiveWork(),
                input.totalMechanicalWork(),
                input.factorizationWork(),
                occurrences
            );
        }

        private static void requireOccurrenceSequence(
            List<Occurrence> occurrences
        ) {
            if (occurrences.isEmpty()) {
                throw new IllegalArgumentException(
                    "occurrence plan must retain at least one occurrence"
                );
            }
            Set<List<Integer>> paths = new HashSet<>();
            for (int index = 0; index < occurrences.size(); index++) {
                Occurrence occurrence = occurrences.get(index);
                if (occurrence.occurrenceIndex() != index
                        || !paths.add(occurrence.path())) {
                    throw new IllegalArgumentException(
                        "occurrence plan order or path uniqueness differs"
                    );
                }
            }
        }

        private static void requireAuthorityTotals(
            int primitive,
            int mechanical,
            int factorization,
            List<Occurrence> occurrences
        ) {
            long primitiveSum = occurrences.stream()
                .mapToLong(Occurrence::admittedPrimitiveWork)
                .sum();
            long mechanicalSum = occurrences.stream()
                .mapToLong(Occurrence::totalMechanicalWork)
                .sum();
            long factorizationSum = occurrences.stream()
                .mapToLong(Occurrence::factorizationWork)
                .sum();
            if (primitiveSum != primitive
                    || mechanicalSum != mechanical
                    || factorizationSum != factorization) {
                throw new IllegalArgumentException(
                    "occurrence authorities do not reconstruct the input row"
                );
            }
        }
    }

    private static String identity(
        String executionInputId,
        String caseId,
        String sourceExpression,
        String declaredDomain,
        String assumptionSetId,
        String occurrenceLayout,
        int primitive,
        int mechanical,
        int factorization,
        List<Occurrence> occurrences
    ) {
        StringBuilder material = new StringBuilder();
        append(material, SCHEMA);
        append(material, requireHash(executionInputId, "executionInputId"));
        append(material, requireText(caseId, "caseId"));
        append(material, requireText(sourceExpression, "sourceExpression"));
        append(material, requireText(declaredDomain, "declaredDomain"));
        append(material, requireText(assumptionSetId, "assumptionSetId"));
        append(material, requireText(occurrenceLayout, "occurrenceLayout"));
        append(material, Integer.toString(primitive));
        append(material, Integer.toString(mechanical));
        append(material, Integer.toString(factorization));
        append(material, Integer.toString(occurrences.size()));
        occurrences.forEach(value -> value.appendIdentityMaterial(material));
        return PolynomialTheoryUtilityExecutionIdentity.sha256(
            material.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String requireHash(String value, String name) {
        String text = Objects.requireNonNull(value, name);
        if (!text.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " is not SHA-256");
        }
        return text;
    }

    private static String requireText(String value, String name) {
        String text = Objects.requireNonNull(value, name);
        if (text.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return text;
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }
}
