package de.regelsuche.benchmark;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.knowledge.KnowledgePackRegistry;
import de.regelsuche.search.reachability.PatternTargetedLocalBridgeSearch;
import de.regelsuche.search.reachability.RulePreparationCoordinator;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Frozen multi-family pilot measuring additional applicability of unchanged
 * imported SymPy rules through one safe preparation coordinator.
 */
public final class SymPyRuleAmplificationExperiment {
    public static final String SCHEMA =
        "regelsuche.sympy-rule-amplification/v2";
    public static final String CONFIGURATION_ID =
        "sympy-three-family-safe-preparation-matrix-v2";

    public static final String PYTHAGOREAN_RULE_ID =
        "sympy.trig.pythagorean";
    public static final String DIFFERENCE_OF_SQUARES_RULE_ID =
        "sympy.poly.factor.diff_squares";
    public static final String TELESCOPING_RULE_ID =
        "sympy.rational.partial_fraction.telescoping";

    private static final PatternTargetedLocalBridgeSearch.Budget BUDGET =
        new PatternTargetedLocalBridgeSearch.Budget(
            3, 128, 1_024, 8, 160, 128,
            32, 5_000, 2_500);

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                "expected repository revision and output directory");
        }
        Report report = new SymPyRuleAmplificationExperiment().run(args[0]);
        report.write(Path.of(args[1]));
        if (!report.qualified()) {
            throw new IllegalStateException(
                "SymPy rule-amplification matrix did not satisfy its frozen contract");
        }
    }

    public Report run(String repositoryRevision) {
        List<PatternRewriteRule> principals = principals();
        List<RewriteRule> preparationRules = cancellationRules();
        RulePreparationCoordinator coordinator =
            new RulePreparationCoordinator(
                principals,
                preparationRules,
                repositoryRevision,
                BUDGET);
        List<Row> rows = cases().stream()
            .map(experimentCase -> evaluate(
                experimentCase, coordinator))
            .toList();
        List<PrincipalDescriptor> descriptors = principals.stream()
            .map(rule -> new PrincipalDescriptor(
                rule.id(),
                rule.descriptor().packId(),
                rule.descriptor().originProject(),
                rule.descriptor().sourceVersion(),
                rule.descriptor().riskLevel()))
            .toList();
        return new Report(
            SCHEMA,
            CONFIGURATION_ID,
            repositoryRevision,
            RulePreparationCoordinator.COORDINATOR_ID,
            coordinator.principalInventoryFingerprint(),
            coordinator.preparationInventoryFingerprint(),
            descriptors,
            rows);
    }

    private Row evaluate(
        ExperimentCase experimentCase,
        RulePreparationCoordinator coordinator
    ) {
        RulePreparationCoordinator.Evaluation evaluation =
            coordinator.analyze(
                experimentCase.sourceExpression(),
                AssumptionSignature.ofExpressions(
                    experimentCase.sourceAssumptions()));
        RulePreparationCoordinator.Outcome selected = evaluation
            .outcome(experimentCase.principalRuleId())
            .orElseThrow(() -> new IllegalStateException(
                "missing principal outcome: "
                    + experimentCase.principalRuleId()));
        List<String> unexpectedApplicableRuleIds = evaluation.outcomes()
            .stream()
            .filter(RulePreparationCoordinator.Outcome::positive)
            .map(RulePreparationCoordinator.Outcome::ruleId)
            .filter(ruleId -> !ruleId.equals(
                experimentCase.principalRuleId()))
            .toList();
        Transformation candidate = selected.candidate().orElse(null);
        int preparationDepth = selected.prepared()
            ? candidate.primitiveStepCount() - 1
            : 0;
        return new Row(
            experimentCase.id(),
            experimentCase.principalRuleId(),
            experimentCase.sourceExpression(),
            experimentCase.sourceAssumptions(),
            experimentCase.expectedStatus(),
            selected.status(),
            candidate == null ? "" : candidate.transformedExpression(),
            preparationDepth,
            candidate == null ? List.of() : candidate.primitiveRuleIds(),
            candidate == null ? List.of() : candidate.assumptions(),
            experimentCase.requiredResultAssumptions(),
            unexpectedApplicableRuleIds,
            coordinator.verify(evaluation).valid(),
            selected.replayVerified(),
            selected.reachedLimits().stream().sorted().toList(),
            evaluation.aggregateWork().generatedTransitions(),
            evaluation.aggregateWork().discoveredStates());
    }

    private List<PatternRewriteRule> principals() {
        return List.of(
            principal("sympy-trigonometry", PYTHAGOREAN_RULE_ID),
            principal("sympy-polynomial", DIFFERENCE_OF_SQUARES_RULE_ID),
            principal("sympy-rational", TELESCOPING_RULE_ID));
    }

    private PatternRewriteRule principal(
        String packId,
        String ruleId
    ) {
        return new KnowledgePackRegistry().allPacks().stream()
            .filter(pack -> packId.equals(pack.packId()))
            .flatMap(pack -> pack.rules().stream())
            .filter(rule -> ruleId.equals(rule.id()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "missing imported SymPy rule: " + ruleId));
    }

    private List<RewriteRule> cancellationRules() {
        List<RewriteRule> rules = AstRewriteTransformationEngine
            .allBuiltInRules().stream()
            .filter(rule -> "ast_cancel_division_factor"
                .equals(rule.id()))
            .toList();
        if (rules.size() != 1) {
            throw new IllegalStateException(
                "expected exactly one cancellation preparation rule");
        }
        return rules;
    }

    public static List<ExperimentCase> cases() {
        return List.of(
            new ExperimentCase(
                "pythagorean-direct-canonical",
                PYTHAGOREAN_RULE_ID,
                "sin(x)^2 + cos(x)^2",
                List.of(),
                PatternTargetedLocalBridgeSearch.Status
                    .DIRECT_MATCH_AVAILABLE,
                List.of()),
            new ExperimentCase(
                "pythagorean-direct-ac-reordered",
                PYTHAGOREAN_RULE_ID,
                "cos(x)^2 + sin(x)^2",
                List.of(),
                PatternTargetedLocalBridgeSearch.Status
                    .DIRECT_MATCH_AVAILABLE,
                List.of()),
            new ExperimentCase(
                "pythagorean-one-hidden-cancellation",
                PYTHAGOREAN_RULE_ID,
                "((sin(x) * a) / a)^2 + cos(x)^2",
                List.of(),
                PatternTargetedLocalBridgeSearch.Status.PREPARED,
                List.of("a != 0")),
            new ExperimentCase(
                "pythagorean-two-hidden-cancellations",
                PYTHAGOREAN_RULE_ID,
                "((sin(x) * a) / a)^2 + ((cos(x) * b) / b)^2",
                List.of(),
                PatternTargetedLocalBridgeSearch.Status.PREPARED,
                List.of("a != 0", "b != 0")),
            new ExperimentCase(
                "pythagorean-different-argument-near-miss",
                PYTHAGOREAN_RULE_ID,
                "((sin(x) * a) / a)^2 + ((cos(y) * b) / b)^2",
                List.of(),
                PatternTargetedLocalBridgeSearch.Status
                    .NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE,
                List.of()),
            new ExperimentCase(
                "difference-squares-direct",
                DIFFERENCE_OF_SQUARES_RULE_ID,
                "x^2 - y^2",
                List.of(),
                PatternTargetedLocalBridgeSearch.Status
                    .DIRECT_MATCH_AVAILABLE,
                List.of()),
            new ExperimentCase(
                "difference-squares-two-hidden-cancellations",
                DIFFERENCE_OF_SQUARES_RULE_ID,
                "((x * a) / a)^2 - ((y * b) / b)^2",
                List.of(),
                PatternTargetedLocalBridgeSearch.Status.PREPARED,
                List.of("a != 0", "b != 0")),
            new ExperimentCase(
                "difference-squares-sum-near-miss",
                DIFFERENCE_OF_SQUARES_RULE_ID,
                "x^2 + y^2",
                List.of(),
                PatternTargetedLocalBridgeSearch.Status
                    .NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE,
                List.of()),
            new ExperimentCase(
                "telescoping-direct",
                TELESCOPING_RULE_ID,
                "1 / (n * (n + 1))",
                List.of("n != 0", "n + 1 != 0"),
                PatternTargetedLocalBridgeSearch.Status
                    .DIRECT_MATCH_AVAILABLE,
                List.of("n != 0", "n + 1 != 0")),
            new ExperimentCase(
                "telescoping-two-hidden-cancellations",
                TELESCOPING_RULE_ID,
                "1 / (((n * a) / a) * (((n + 1) * b) / b))",
                List.of("n != 0", "n + 1 != 0"),
                PatternTargetedLocalBridgeSearch.Status.PREPARED,
                List.of(
                    "a != 0", "b != 0",
                    "n != 0", "n + 1 != 0")),
            new ExperimentCase(
                "telescoping-step-two-near-miss",
                TELESCOPING_RULE_ID,
                "1 / (n * (n + 2))",
                List.of("n != 0", "n + 2 != 0"),
                PatternTargetedLocalBridgeSearch.Status
                    .NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE,
                List.of()));
    }

    public record ExperimentCase(
        String id,
        String principalRuleId,
        String sourceExpression,
        List<String> sourceAssumptions,
        PatternTargetedLocalBridgeSearch.Status expectedStatus,
        List<String> requiredResultAssumptions
    ) {
        public ExperimentCase {
            text(id, "case id");
            text(principalRuleId, "principal rule ID");
            text(sourceExpression, "source expression");
            sourceAssumptions = AssumptionSignature.ofExpressions(
                sourceAssumptions).normalizedAssumptions();
            Objects.requireNonNull(expectedStatus, "expectedStatus");
            requiredResultAssumptions = AssumptionSignature.ofExpressions(
                requiredResultAssumptions).normalizedAssumptions();
        }
    }

    public record PrincipalDescriptor(
        String ruleId,
        String packId,
        String originProject,
        String sourceVersion,
        String riskLevel
    ) {
        public PrincipalDescriptor {
            text(ruleId, "ruleId");
            text(packId, "packId");
            text(originProject, "originProject");
            text(sourceVersion, "sourceVersion");
            text(riskLevel, "riskLevel");
        }
    }

    public record Row(
        String caseId,
        String principalRuleId,
        String sourceExpression,
        List<String> sourceAssumptions,
        PatternTargetedLocalBridgeSearch.Status expectedStatus,
        PatternTargetedLocalBridgeSearch.Status coordinatorStatus,
        String resultExpression,
        int preparationDepth,
        List<String> primitiveRuleIds,
        List<String> resultAssumptions,
        List<String> requiredResultAssumptions,
        List<String> unexpectedApplicableRuleIds,
        boolean coordinatorVerified,
        boolean principalReplayVerified,
        List<String> reachedLimits,
        long generatedTransitions,
        long discoveredStates
    ) {
        public Row {
            text(caseId, "caseId");
            text(principalRuleId, "principalRuleId");
            text(sourceExpression, "sourceExpression");
            sourceAssumptions = AssumptionSignature.ofExpressions(
                sourceAssumptions).normalizedAssumptions();
            Objects.requireNonNull(expectedStatus, "expectedStatus");
            Objects.requireNonNull(coordinatorStatus, "coordinatorStatus");
            resultExpression = resultExpression == null
                ? "" : resultExpression;
            primitiveRuleIds = List.copyOf(primitiveRuleIds);
            resultAssumptions = AssumptionSignature.ofExpressions(
                resultAssumptions).normalizedAssumptions();
            requiredResultAssumptions = AssumptionSignature.ofExpressions(
                requiredResultAssumptions).normalizedAssumptions();
            unexpectedApplicableRuleIds = List.copyOf(
                unexpectedApplicableRuleIds);
            reachedLimits = List.copyOf(reachedLimits);
            if (preparationDepth < 0 || generatedTransitions < 0
                    || discoveredStates < 1) {
                throw new IllegalArgumentException(
                    "row work values must not be negative");
            }
        }

        boolean qualifies() {
            boolean positive = coordinatorStatus
                    == PatternTargetedLocalBridgeSearch.Status
                        .DIRECT_MATCH_AVAILABLE
                || coordinatorStatus
                    == PatternTargetedLocalBridgeSearch.Status.PREPARED;
            boolean statusAndReplay = coordinatorStatus == expectedStatus
                && coordinatorVerified
                && unexpectedApplicableRuleIds.isEmpty();
            if (!statusAndReplay) {
                return false;
            }
            if (positive) {
                return principalReplayVerified
                    && !resultExpression.isEmpty()
                    && !primitiveRuleIds.isEmpty()
                    && principalRuleId.equals(
                        primitiveRuleIds.getLast())
                    && resultAssumptions.equals(
                        requiredResultAssumptions)
                    && (coordinatorStatus
                        == PatternTargetedLocalBridgeSearch.Status
                            .DIRECT_MATCH_AVAILABLE
                        ? preparationDepth == 0
                        : preparationDepth > 0);
            }
            return coordinatorStatus
                    == PatternTargetedLocalBridgeSearch.Status
                        .NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE
                && resultExpression.isEmpty()
                && primitiveRuleIds.isEmpty()
                && resultAssumptions.isEmpty()
                && reachedLimits.isEmpty();
        }
    }

    public record Report(
        String schema,
        String configurationId,
        String repositoryRevision,
        String coordinatorId,
        String principalInventoryFingerprint,
        String preparationInventoryFingerprint,
        List<PrincipalDescriptor> principals,
        List<Row> rows
    ) {
        public Report {
            if (!SCHEMA.equals(schema)
                    || !CONFIGURATION_ID.equals(configurationId)
                    || repositoryRevision == null
                    || !repositoryRevision.matches("[0-9a-f]{40}")
                    || !RulePreparationCoordinator.COORDINATOR_ID.equals(
                        coordinatorId)
                    || principalInventoryFingerprint == null
                    || !principalInventoryFingerprint.matches(
                        "sha256:[0-9a-f]{64}")
                    || preparationInventoryFingerprint == null
                    || !preparationInventoryFingerprint.matches(
                        "sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "report identity is invalid");
            }
            principals = List.copyOf(principals);
            rows = List.copyOf(rows);
            if (principals.size() != 3
                    || rows.size() != cases().size()) {
                throw new IllegalArgumentException(
                    "report must contain the complete frozen matrix");
            }
            Set<String> principalIds = new LinkedHashSet<>();
            principals.forEach(value -> principalIds.add(value.ruleId()));
            if (principalIds.size() != principals.size()) {
                throw new IllegalArgumentException(
                    "principal descriptors must be unique");
            }
        }

        public boolean qualified() {
            return rows.stream().allMatch(Row::qualifies)
                && directApplications() == 4
                && preparedApplications() == 4
                && conclusiveNearMisses() == 3
                && amplifiedRuleFamilies() == 3;
        }

        public long directApplications() {
            return rows.stream()
                .filter(row -> row.coordinatorStatus()
                    == PatternTargetedLocalBridgeSearch.Status
                        .DIRECT_MATCH_AVAILABLE)
                .count();
        }

        public long preparedApplications() {
            return rows.stream()
                .filter(row -> row.coordinatorStatus()
                    == PatternTargetedLocalBridgeSearch.Status.PREPARED)
                .count();
        }

        public long conclusiveNearMisses() {
            return rows.stream()
                .filter(row -> row.coordinatorStatus()
                    == PatternTargetedLocalBridgeSearch.Status
                        .NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE)
                .count();
        }

        public long amplifiedRuleFamilies() {
            return rows.stream()
                .filter(row -> row.coordinatorStatus()
                    == PatternTargetedLocalBridgeSearch.Status.PREPARED)
                .map(Row::principalRuleId)
                .distinct()
                .count();
        }

        public long amplificationGain() {
            return preparedApplications();
        }

        public void write(Path outputDirectory) throws IOException {
            Files.createDirectories(outputDirectory);
            Files.writeString(
                outputDirectory.resolve("sympy-rule-amplification.json"),
                toJson(),
                StandardCharsets.UTF_8);
            Files.writeString(
                outputDirectory.resolve("sympy-rule-amplification.md"),
                toMarkdown(),
                StandardCharsets.UTF_8);
        }

        public String toJson() {
            StringBuilder value = new StringBuilder();
            value.append("{\n")
                .append("  \"schema\": \"").append(json(schema))
                .append("\",\n  \"configurationId\": \"")
                .append(json(configurationId))
                .append("\",\n  \"repositoryRevision\": \"")
                .append(repositoryRevision)
                .append("\",\n  \"coordinatorId\": \"")
                .append(json(coordinatorId))
                .append("\",\n  \"principalInventoryFingerprint\": \"")
                .append(principalInventoryFingerprint)
                .append("\",\n  \"preparationInventoryFingerprint\": \"")
                .append(preparationInventoryFingerprint)
                .append("\",\n  \"qualified\": ")
                .append(qualified())
                .append(",\n  \"directApplications\": ")
                .append(directApplications())
                .append(",\n  \"preparedApplications\": ")
                .append(preparedApplications())
                .append(",\n  \"amplificationGain\": ")
                .append(amplificationGain())
                .append(",\n  \"amplifiedRuleFamilies\": ")
                .append(amplifiedRuleFamilies())
                .append(",\n  \"principals\": [\n");
            for (int index = 0; index < principals.size(); index++) {
                PrincipalDescriptor principal = principals.get(index);
                value.append("    {\"ruleId\":\"")
                    .append(json(principal.ruleId()))
                    .append("\",\"packId\":\"")
                    .append(json(principal.packId()))
                    .append("\",\"originProject\":\"")
                    .append(json(principal.originProject()))
                    .append("\",\"sourceVersion\":\"")
                    .append(json(principal.sourceVersion()))
                    .append("\",\"riskLevel\":\"")
                    .append(json(principal.riskLevel()))
                    .append("\"}")
                    .append(index + 1 == principals.size()
                        ? "\n" : ",\n");
            }
            value.append("  ],\n  \"rows\": [\n");
            for (int index = 0; index < rows.size(); index++) {
                Row row = rows.get(index);
                value.append("    {\"caseId\":\"")
                    .append(json(row.caseId()))
                    .append("\",\"principalRuleId\":\"")
                    .append(json(row.principalRuleId()))
                    .append("\",\"sourceExpression\":\"")
                    .append(json(row.sourceExpression()))
                    .append("\",\"sourceAssumptions\":")
                    .append(jsonList(row.sourceAssumptions()))
                    .append(",\"expectedStatus\":\"")
                    .append(row.expectedStatus())
                    .append("\",\"coordinatorStatus\":\"")
                    .append(row.coordinatorStatus())
                    .append("\",\"resultExpression\":\"")
                    .append(json(row.resultExpression()))
                    .append("\",\"preparationDepth\":")
                    .append(row.preparationDepth())
                    .append(",\"primitiveRuleIds\":")
                    .append(jsonList(row.primitiveRuleIds()))
                    .append(",\"resultAssumptions\":")
                    .append(jsonList(row.resultAssumptions()))
                    .append(",\"requiredResultAssumptions\":")
                    .append(jsonList(row.requiredResultAssumptions()))
                    .append(",\"unexpectedApplicableRuleIds\":")
                    .append(jsonList(row.unexpectedApplicableRuleIds()))
                    .append(",\"coordinatorVerified\":")
                    .append(row.coordinatorVerified())
                    .append(",\"principalReplayVerified\":")
                    .append(row.principalReplayVerified())
                    .append(",\"reachedLimits\":")
                    .append(jsonList(row.reachedLimits()))
                    .append(",\"generatedTransitions\":")
                    .append(row.generatedTransitions())
                    .append(",\"discoveredStates\":")
                    .append(row.discoveredStates())
                    .append("}")
                    .append(index + 1 == rows.size()
                        ? "\n" : ",\n");
            }
            return value.append("  ]\n}\n").toString();
        }

        public String toMarkdown() {
            StringBuilder value = new StringBuilder(
                "# SymPy rule amplification matrix\n\n")
                .append("Coordinator: `").append(coordinatorId)
                .append("`. Principals: **").append(principals.size())
                .append("**; prepared additions: **")
                .append(amplificationGain())
                .append("** across **").append(amplifiedRuleFamilies())
                .append("** mathematical families.\n\n")
                .append("| Case | Principal | Status | Prep depth | Result |\n")
                .append("|---|---|---|---:|---|\n");
            rows.forEach(row -> value.append("| `")
                .append(row.caseId()).append("` | `")
                .append(row.principalRuleId()).append("` | `")
                .append(row.coordinatorStatus()).append("` | ")
                .append(row.preparationDepth()).append(" | `")
                .append(row.resultExpression()).append("` |\n"));
            return value.append("\nDirect applications: **")
                .append(directApplications())
                .append("**; prepared applications: **")
                .append(preparedApplications())
                .append("**; conclusive near misses: **")
                .append(conclusiveNearMisses())
                .append("**; qualified: **")
                .append(qualified()).append("**.\n\n")
                .append("Rational rows bind their denominator assumptions as explicit input evidence. This is bounded evidence for three unchanged low-risk imported rules, one frozen cancellation inventory and eleven declared cases. It is not a general SymPy performance, completeness or superiority claim.\n")
                .toString();
        }
    }

    private static String jsonList(List<String> values) {
        List<String> escaped = new ArrayList<>();
        values.forEach(value -> escaped.add("\"" + json(value) + "\""));
        return "[" + String.join(",", escaped) + "]";
    }

    static String json(String value) {
        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private static void text(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                field + " must not be blank");
        }
    }
}
