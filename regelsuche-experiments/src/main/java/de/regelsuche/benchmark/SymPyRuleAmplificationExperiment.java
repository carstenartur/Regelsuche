package de.regelsuche.benchmark;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.knowledge.KnowledgePackRegistry;
import de.regelsuche.search.reachability.PatternTargetedLocalBridgeSearch;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteRule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Frozen pilot measuring how many additional inputs one unchanged imported
 * SymPy rule can handle through bounded local preparation.
 */
public final class SymPyRuleAmplificationExperiment {
    public static final String SCHEMA =
        "regelsuche.sympy-rule-amplification/v1";
    public static final String CONFIGURATION_ID =
        "sympy-pythagorean-direct-vs-pattern-bridge-v1";
    public static final String PRINCIPAL_RULE_ID =
        "sympy.trig.pythagorean";
    public static final String PRINCIPAL_PACK_ID =
        "sympy-trigonometry";

    private static final PatternTargetedLocalBridgeSearch.Budget BUDGET =
        new PatternTargetedLocalBridgeSearch.Budget(
            3, 128, 1_024, 8, 128, 128,
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
                "SymPy rule-amplification pilot did not satisfy its frozen contract");
        }
    }

    public Report run(String repositoryRevision) {
        PatternRewriteRule principal = principalRule();
        List<RewriteRule> preparationRules = cancellationRules();
        AstRewriteTransformationEngine direct =
            new AstRewriteTransformationEngine(List.of(principal));
        PatternTargetedLocalBridgeSearch bridgeSearch =
            new PatternTargetedLocalBridgeSearch(
                principal,
                preparationRules,
                repositoryRevision,
                BUDGET);
        List<Row> rows = cases().stream()
            .map(experimentCase -> evaluate(
                experimentCase, direct, bridgeSearch))
            .toList();
        return new Report(
            SCHEMA,
            CONFIGURATION_ID,
            repositoryRevision,
            principal.descriptor().sourceVersion(),
            PRINCIPAL_PACK_ID,
            PRINCIPAL_RULE_ID,
            rows);
    }

    private Row evaluate(
        ExperimentCase experimentCase,
        AstRewriteTransformationEngine direct,
        PatternTargetedLocalBridgeSearch bridgeSearch
    ) {
        boolean directApplicable = direct
            .transform(experimentCase.sourceExpression()).stream()
            .anyMatch(value -> PRINCIPAL_RULE_ID.equals(value.rule()));
        PatternTargetedLocalBridgeSearch.Attempt attempt =
            bridgeSearch.analyze(
                experimentCase.sourceExpression(),
                AssumptionSignature.ofExpressions(List.of()));
        var bridge = attempt.bridge();
        boolean verified = bridge.isEmpty()
            || bridgeSearch.verify(bridge.orElseThrow()).valid();
        return new Row(
            experimentCase.id(),
            experimentCase.sourceExpression(),
            experimentCase.expectedStatus(),
            directApplicable,
            attempt.status(),
            bridge.map(
                PatternTargetedLocalBridgeSearch.Bridge::resultExpression)
                .orElse(""),
            bridge.map(value -> value.preparationSteps().size())
                .orElse(0),
            bridge.map(
                PatternTargetedLocalBridgeSearch.Bridge::primitiveRuleIds)
                .orElse(List.of()),
            bridge.map(value -> value.resultAssumptions()
                .normalizedAssumptions()).orElse(List.of()),
            verified,
            attempt.reachedLimits().stream().sorted().toList(),
            attempt.work().generatedTransitions(),
            attempt.work().discoveredStates());
    }

    private PatternRewriteRule principalRule() {
        return new KnowledgePackRegistry().allPacks().stream()
            .filter(pack -> PRINCIPAL_PACK_ID.equals(pack.packId()))
            .flatMap(pack -> pack.rules().stream())
            .filter(rule -> PRINCIPAL_RULE_ID.equals(rule.id()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "missing imported SymPy Pythagorean rule"));
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
                "direct-canonical",
                "sin(x)^2 + cos(x)^2",
                PatternTargetedLocalBridgeSearch.Status
                    .DIRECT_MATCH_AVAILABLE),
            new ExperimentCase(
                "direct-ac-reordered",
                "cos(x)^2 + sin(x)^2",
                PatternTargetedLocalBridgeSearch.Status
                    .DIRECT_MATCH_AVAILABLE),
            new ExperimentCase(
                "one-hidden-cancellation",
                "((sin(x) * a) / a)^2 + cos(x)^2",
                PatternTargetedLocalBridgeSearch.Status.PREPARED),
            new ExperimentCase(
                "two-hidden-cancellations",
                "((sin(x) * a) / a)^2 + ((cos(x) * b) / b)^2",
                PatternTargetedLocalBridgeSearch.Status.PREPARED),
            new ExperimentCase(
                "different-argument-near-miss",
                "((sin(x) * a) / a)^2 + ((cos(y) * b) / b)^2",
                PatternTargetedLocalBridgeSearch.Status
                    .NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE));
    }

    public record ExperimentCase(
        String id,
        String sourceExpression,
        PatternTargetedLocalBridgeSearch.Status expectedStatus
    ) {
        public ExperimentCase {
            text(id, "case id");
            text(sourceExpression, "source expression");
            Objects.requireNonNull(expectedStatus, "expectedStatus");
        }
    }

    public record Row(
        String caseId,
        String sourceExpression,
        PatternTargetedLocalBridgeSearch.Status expectedStatus,
        boolean directApplicable,
        PatternTargetedLocalBridgeSearch.Status bridgeStatus,
        String resultExpression,
        int preparationDepth,
        List<String> primitiveRuleIds,
        List<String> assumptions,
        boolean independentlyVerified,
        List<String> reachedLimits,
        int generatedTransitions,
        int discoveredStates
    ) {
        public Row {
            text(caseId, "caseId");
            text(sourceExpression, "sourceExpression");
            Objects.requireNonNull(expectedStatus, "expectedStatus");
            Objects.requireNonNull(bridgeStatus, "bridgeStatus");
            resultExpression = resultExpression == null
                ? "" : resultExpression;
            primitiveRuleIds = List.copyOf(primitiveRuleIds);
            assumptions = List.copyOf(assumptions);
            reachedLimits = List.copyOf(reachedLimits);
            if (preparationDepth < 0 || generatedTransitions < 0
                    || discoveredStates < 1) {
                throw new IllegalArgumentException(
                    "row work values must not be negative");
            }
        }

        boolean qualifies() {
            return bridgeStatus == expectedStatus
                && independentlyVerified
                && (bridgeStatus
                    != PatternTargetedLocalBridgeSearch.Status.PREPARED
                    || !directApplicable
                        && "1".equals(resultExpression)
                        && preparationDepth > 0)
                && (bridgeStatus
                    != PatternTargetedLocalBridgeSearch.Status
                        .NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE
                    || resultExpression.isEmpty()
                        && reachedLimits.isEmpty());
        }
    }

    public record Report(
        String schema,
        String configurationId,
        String repositoryRevision,
        String sourceVersion,
        String principalPackId,
        String principalRuleId,
        List<Row> rows
    ) {
        public Report {
            if (!SCHEMA.equals(schema)
                    || !CONFIGURATION_ID.equals(configurationId)
                    || repositoryRevision == null
                    || !repositoryRevision.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException(
                    "report identity is invalid");
            }
            text(sourceVersion, "sourceVersion");
            text(principalPackId, "principalPackId");
            text(principalRuleId, "principalRuleId");
            rows = List.copyOf(rows);
            if (rows.size() != cases().size()) {
                throw new IllegalArgumentException(
                    "report must contain the complete frozen case matrix");
            }
        }

        public boolean qualified() {
            return rows.stream().allMatch(Row::qualifies)
                && directApplications() == 2
                && preparedApplications() == 2
                && conclusiveNearMisses() == 1;
        }

        public long directApplications() {
            return rows.stream().filter(Row::directApplicable).count();
        }

        public long preparedApplications() {
            return rows.stream()
                .filter(row -> row.bridgeStatus()
                    == PatternTargetedLocalBridgeSearch.Status.PREPARED)
                .count();
        }

        public long conclusiveNearMisses() {
            return rows.stream()
                .filter(row -> row.bridgeStatus()
                    == PatternTargetedLocalBridgeSearch.Status
                        .NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE)
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
                .append("\",\n  \"sourceVersion\": \"")
                .append(json(sourceVersion))
                .append("\",\n  \"principalPackId\": \"")
                .append(json(principalPackId))
                .append("\",\n  \"principalRuleId\": \"")
                .append(json(principalRuleId))
                .append("\",\n  \"qualified\": ")
                .append(qualified())
                .append(",\n  \"directApplications\": ")
                .append(directApplications())
                .append(",\n  \"preparedApplications\": ")
                .append(preparedApplications())
                .append(",\n  \"amplificationGain\": ")
                .append(amplificationGain())
                .append(",\n  \"rows\": [\n");
            for (int index = 0; index < rows.size(); index++) {
                Row row = rows.get(index);
                value.append("    {\"caseId\":\"")
                    .append(json(row.caseId()))
                    .append("\",\"sourceExpression\":\"")
                    .append(json(row.sourceExpression()))
                    .append("\",\"directApplicable\":")
                    .append(row.directApplicable())
                    .append(",\"bridgeStatus\":\"")
                    .append(row.bridgeStatus())
                    .append("\",\"resultExpression\":\"")
                    .append(json(row.resultExpression()))
                    .append("\",\"preparationDepth\":")
                    .append(row.preparationDepth())
                    .append(",\"primitiveRuleIds\":")
                    .append(jsonList(row.primitiveRuleIds()))
                    .append(",\"assumptions\":")
                    .append(jsonList(row.assumptions()))
                    .append(",\"independentlyVerified\":")
                    .append(row.independentlyVerified())
                    .append(",\"reachedLimits\":")
                    .append(jsonList(row.reachedLimits()))
                    .append("}")
                    .append(index + 1 == rows.size() ? "\n" : ",\n");
            }
            return value.append("  ]\n}\n").toString();
        }

        public String toMarkdown() {
            StringBuilder value = new StringBuilder(
                "# SymPy rule amplification pilot\n\n")
                .append("Principal rule: `").append(principalRuleId)
                .append("` from `").append(principalPackId)
                .append("` (SymPy ").append(sourceVersion)
                .append(").\n\n")
                .append("| Case | Direct | Bridge status | Prep depth | Result |\n")
                .append("|---|---:|---|---:|---|\n");
            rows.forEach(row -> value.append("| `")
                .append(row.caseId()).append("` | ")
                .append(row.directApplicable() ? "yes" : "no")
                .append(" | `").append(row.bridgeStatus())
                .append("` | ").append(row.preparationDepth())
                .append(" | `").append(row.resultExpression())
                .append("` |\n"));
            return value.append("\nAdditional prepared applications: **")
                .append(amplificationGain())
                .append("**. Qualified: **")
                .append(qualified()).append("**.\n\n")
                .append("This is bounded evidence for one unchanged imported rule, one frozen cancellation inventory and five declared cases. It is not a general SymPy performance or completeness claim.\n")
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
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
