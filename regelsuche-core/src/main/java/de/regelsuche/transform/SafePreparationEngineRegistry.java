package de.regelsuche.transform;

import de.regelsuche.knowledge.RuleInventoryFingerprint;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Content-addressed registry for the existing exact preparation engine chain.
 *
 * <p>The individual preparation engines remain responsible for their own
 * certificates and concrete principal replay. This registry freezes their
 * order, native principal IDs and implementation identities behind one
 * deterministic execution boundary.</p>
 */
public final class SafePreparationEngineRegistry {
    public static final String REGISTRY_ID =
        "regelsuche.safe-exact-preparation-registry/v1";
    public static final String ANY_PRINCIPAL = "*";

    private static final List<Stage> STAGES = List.of(
        new Stage(
            "direct-ast-rewrite",
            StageKind.DIRECT,
            "",
            ANY_PRINCIPAL,
            AstRewriteTransformationEngine.class.getName()),
        new Stage(
            "exact-polynomial-quotient",
            StageKind.EXACT_PREPARATION,
            RulePreparationPlanner.PLANNER_ID,
            RulePreparationPlanner.PRINCIPAL_RULE_ID,
            RulePreparationTransformationEngine.class.getName()),
        new Stage(
            "ac-factor-exposure",
            StageKind.EXACT_PREPARATION,
            AcNormalizationPreparationSolver.SOLVER_ID,
            AcNormalizationPreparationSolver.PRINCIPAL_RULE_ID,
            AcNormalizationPreparationTransformationEngine.class.getName()),
        new Stage(
            "common-monomial-factor",
            StageKind.EXACT_PREPARATION,
            MonomialCommonFactorPreparationSolver.SOLVER_ID,
            MonomialCommonFactorPreparationSolver.PRINCIPAL_RULE_ID,
            MonomialCommonFactorPreparationTransformationEngine.class.getName()),
        new Stage(
            "perfect-square-exposure",
            StageKind.EXACT_PREPARATION,
            PerfectSquareStructurePreparationSolver.SOLVER_ID,
            PerfectSquareStructurePreparationSolver.PRINCIPAL_RULE_ID,
            PerfectSquareStructurePreparationTransformationEngine.class.getName()),
        new Stage(
            "common-denominator",
            StageKind.EXACT_PREPARATION,
            RationalCommonDenominatorPreparationSolver.SOLVER_ID,
            RationalCommonDenominatorPreparationSolver.PRINCIPAL_RULE_ID,
            RationalCommonDenominatorPreparationTransformationEngine.class.getName()));

    private SafePreparationEngineRegistry() {
    }

    public static List<Stage> stages() {
        return STAGES;
    }

    /**
     * Creates the complete exact preparation chain for one frozen visible rule
     * inventory. Rule order is retained and included in the registry identity.
     */
    public static Registration production(
        List<? extends RewriteRule> suppliedRules
    ) {
        List<RewriteRule> rules = validateRules(suppliedRules);
        String inventoryFingerprint =
            RuleInventoryFingerprint.contentHash(rules);
        String registryFingerprint = fingerprint(rules);
        TransformationEngine engine =
            new RationalCommonDenominatorPreparationTransformationEngine(rules);
        return new Registration(
            REGISTRY_ID,
            STAGES,
            inventoryFingerprint,
            registryFingerprint,
            engine);
    }

    private static List<RewriteRule> validateRules(
        List<? extends RewriteRule> supplied
    ) {
        Objects.requireNonNull(supplied, "rules");
        if (supplied.isEmpty()) {
            throw new IllegalArgumentException(
                "the exact preparation registry requires visible rules");
        }
        Map<String, RewriteRule> byId = new LinkedHashMap<>();
        for (RewriteRule rule : supplied) {
            RewriteRule checked = Objects.requireNonNull(rule, "rule");
            if (!checked.isEquivalencePreservingByConstruction()) {
                throw new IllegalArgumentException(
                    "exact preparation registry accepts only equivalence-preserving rules: "
                        + checked.id());
            }
            if (byId.put(checked.id(), checked) != null) {
                throw new IllegalArgumentException(
                    "duplicate visible rule ID: " + checked.id());
            }
        }
        return List.copyOf(byId.values());
    }

    private static String fingerprint(List<RewriteRule> rules) {
        StringBuilder descriptor = new StringBuilder();
        append(descriptor, REGISTRY_ID);
        append(descriptor, Integer.toString(rules.size()));
        for (RewriteRule rule : rules) {
            append(descriptor, RuleInventoryFingerprint.ruleContentHash(rule));
        }
        append(descriptor, Integer.toString(STAGES.size()));
        for (Stage stage : STAGES) {
            append(descriptor, stage.stageId());
            append(descriptor, stage.kind().name());
            append(descriptor, stage.solverId());
            append(descriptor, stage.principalRuleId());
            append(descriptor, stage.engineClassName());
        }
        return sha256(descriptor.toString());
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public enum StageKind {
        DIRECT,
        EXACT_PREPARATION
    }

    public record Stage(
        String stageId,
        StageKind kind,
        String solverId,
        String principalRuleId,
        String engineClassName
    ) {
        public Stage {
            if (stageId == null || stageId.isBlank()
                    || kind == null
                    || solverId == null
                    || principalRuleId == null
                    || principalRuleId.isBlank()
                    || engineClassName == null
                    || engineClassName.isBlank()) {
                throw new IllegalArgumentException(
                    "preparation stage identity is invalid");
            }
            if (kind == StageKind.EXACT_PREPARATION
                    && solverId.isBlank()) {
                throw new IllegalArgumentException(
                    "exact preparation stages require a solver ID");
            }
            if (kind == StageKind.DIRECT
                    && !ANY_PRINCIPAL.equals(principalRuleId)) {
                throw new IllegalArgumentException(
                    "direct stage must support every principal");
            }
        }

        public boolean supports(String ruleId) {
            return ANY_PRINCIPAL.equals(principalRuleId)
                || principalRuleId.equals(ruleId);
        }
    }

    public static final class Registration {
        private final String registryId;
        private final List<Stage> stages;
        private final String ruleInventoryFingerprint;
        private final String registryFingerprint;
        private final TransformationEngine engine;

        private Registration(
            String registryId,
            List<Stage> stages,
            String ruleInventoryFingerprint,
            String registryFingerprint,
            TransformationEngine engine
        ) {
            this.registryId = Objects.requireNonNull(
                registryId, "registryId");
            this.stages = List.copyOf(stages);
            this.ruleInventoryFingerprint = requireHash(
                ruleInventoryFingerprint, "ruleInventoryFingerprint");
            this.registryFingerprint = requireHash(
                registryFingerprint, "registryFingerprint");
            this.engine = Objects.requireNonNull(engine, "engine");
        }

        public String registryId() {
            return registryId;
        }

        public List<Stage> stages() {
            return stages;
        }

        public String ruleInventoryFingerprint() {
            return ruleInventoryFingerprint;
        }

        public String registryFingerprint() {
            return registryFingerprint;
        }

        /** True only when a registered exact specialist owns this principal. */
        public boolean supportsPrincipal(String ruleId) {
            if (ruleId == null || ruleId.isBlank()) {
                return false;
            }
            return stages.stream()
                .filter(stage -> stage.kind() == StageKind.EXACT_PREPARATION)
                .anyMatch(stage -> stage.supports(ruleId));
        }

        public Execution transform(String sourceExpression) {
            if (sourceExpression == null || sourceExpression.isBlank()) {
                throw new IllegalArgumentException(
                    "sourceExpression must not be blank");
            }
            List<Transformation> transformations = List.copyOf(
                Objects.requireNonNull(
                    engine.transform(sourceExpression),
                    "exact preparation engine result"));
            return new Execution(
                registryId,
                registryFingerprint,
                sourceExpression.trim(),
                transformations);
        }
    }

    public record Execution(
        String registryId,
        String registryFingerprint,
        String sourceExpression,
        List<Transformation> transformations
    ) {
        public Execution {
            if (!REGISTRY_ID.equals(registryId)) {
                throw new IllegalArgumentException(
                    "unsupported exact preparation registry");
            }
            registryFingerprint = requireHash(
                registryFingerprint, "registryFingerprint");
            if (sourceExpression == null || sourceExpression.isBlank()) {
                throw new IllegalArgumentException(
                    "sourceExpression must not be blank");
            }
            transformations = List.copyOf(
                Objects.requireNonNull(
                    transformations, "transformations"));
        }

        /**
         * Composite moves emitted by a verified exact preparation stage rather
         * than by the ordinary direct rewrite pass.
         */
        public List<Transformation> preparedTransformations() {
            List<Transformation> result = new ArrayList<>();
            for (Transformation transformation : transformations) {
                if (transformation.primitiveStepCount() > 1
                        && transformation.applicationKey()
                            .startsWith("prepared")) {
                    result.add(transformation);
                }
            }
            return List.copyOf(result);
        }
    }

    private static String requireHash(String value, String field) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                field + " must be a SHA-256 identity");
        }
        return value;
    }
}
