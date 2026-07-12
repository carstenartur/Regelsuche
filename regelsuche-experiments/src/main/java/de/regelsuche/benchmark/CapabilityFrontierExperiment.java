package de.regelsuche.benchmark;

import de.regelsuche.ast.Expr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.TransformationEngine;
import de.regelsuche.value.ExprValueFactory;
import de.regelsuche.value.ExprValueFactory.ValueKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Reproducible A/B harness for the capability frontier introduced by issue #255.
 *
 * <p>The unguided run uses the historical queue and candidate ordering. The guided
 * run uses the same engine, budgets, scorer and canonicalizer, adding only a typed
 * target. Capability comparisons run the same guided search with one selected
 * capability disabled and enabled. Results are diagnostic telemetry, not proof.</p>
 */
public final class CapabilityFrontierExperiment {
    private final ExpressionScorer scorer = new ExpressionScorer();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final ExpressionParser parser = new ExpressionParser();

    public GuidanceComparison compareGuidance(GuidanceScenario scenario) {
        Objects.requireNonNull(scenario, "scenario");
        RunEvidence unguided = runUnguided(scenario);
        RunEvidence guided = runGuided(
            scenario.id(), scenario.rootExpression(), scenario.targetExpression(),
            scenario.engine(), scenario.heuristic());
        GuidanceVerdict verdict;
        if (guided.reached() && !unguided.reached()) {
            verdict = GuidanceVerdict.RECOVERED_WITHIN_BUDGET;
        } else if (guided.reached() && unguided.reached()
                && guided.exploredStates() < unguided.exploredStates()) {
            verdict = GuidanceVerdict.FEWER_STATES;
        } else if (guided.reached() && unguided.reached()) {
            verdict = GuidanceVerdict.NO_MATERIAL_GAIN;
        } else {
            verdict = GuidanceVerdict.BOTH_FAILED;
        }
        return new GuidanceComparison(scenario.id(), unguided, guided, verdict);
    }

    public CapabilityComparison compareCapability(CapabilityScenario scenario) {
        Objects.requireNonNull(scenario, "scenario");
        RunEvidence without = runGuided(
            scenario.id() + ":without", scenario.rootExpression(), scenario.targetExpression(),
            scenario.withoutCapability(), scenario.heuristic());
        RunEvidence with = runGuided(
            scenario.id() + ":with", scenario.rootExpression(), scenario.targetExpression(),
            scenario.withCapability(), scenario.heuristic());
        CapabilityVerdict verdict;
        if (!without.reached() && with.reached()) {
            verdict = CapabilityVerdict.CAPABILITY_REQUIRED;
        } else if (without.reached() && with.reached()) {
            verdict = CapabilityVerdict.CAPABILITY_NOT_REQUIRED;
        } else if (!without.reached() && !with.reached()) {
            verdict = CapabilityVerdict.CAPABILITY_INSUFFICIENT_OR_BUDGET_LIMITED;
        } else {
            verdict = CapabilityVerdict.CAPABILITY_REGRESSION;
        }
        return new CapabilityComparison(scenario.id(), scenario.capabilityId(), without, with, verdict);
    }

    public FrontierReport run(
        List<GuidanceScenario> guidanceScenarios,
        List<CapabilityScenario> capabilityScenarios
    ) {
        List<GuidanceComparison> guidance = safeList(guidanceScenarios).stream()
            .map(this::compareGuidance)
            .sorted(Comparator.comparing(GuidanceComparison::scenarioId))
            .toList();
        List<CapabilityComparison> capabilities = safeList(capabilityScenarios).stream()
            .map(this::compareCapability)
            .sorted(Comparator.comparing(CapabilityComparison::scenarioId))
            .toList();
        return new FrontierReport("regelsuche.capability-frontier/v1", guidance, capabilities);
    }

    public Path write(Path output, FrontierReport report) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(report, "report");
        try {
            Path parent = output.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(output, report.toJson(), StandardCharsets.UTF_8);
            return output;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private RunEvidence runUnguided(GuidanceScenario scenario) {
        SearchProblem problem = problem(
            scenario.rootExpression(), scenario.engine(), scenario.heuristic());
        List<SearchState> states = new BestFirstSearchStrategy().search(problem);
        ValueKey targetKey = key(scenario.targetExpression());
        int reachedIndex = -1;
        SearchState reached = null;
        for (int index = 0; index < states.size(); index++) {
            SearchState state = states.get(index);
            if (key(state.expression()).equals(targetKey)) {
                reachedIndex = index;
                reached = state;
                break;
            }
        }
        int statesUntilTarget = reachedIndex < 0 ? states.size() : reachedIndex + 1;
        return new RunEvidence(
            scenario.id() + ":unguided",
            reached != null,
            reached == null ? "NOT_REACHED" : "REACHED",
            statesUntilTarget,
            -1,
            reached == null ? List.of() : reached.path(),
            reached == null ? List.of() : reached.appliedRuleIds(),
            reached == null ? Integer.MAX_VALUE : 0,
            0,
            0,
            0
        );
    }

    private RunEvidence runGuided(
        String runId,
        String root,
        String target,
        TransformationEngine engine,
        SearchHeuristic heuristic
    ) {
        BestFirstSearchStrategy.GoalSearchResult result =
            new BestFirstSearchStrategy().searchWithDiagnostics(
                problem(root, engine, heuristic).withTarget(target));
        SearchState reached = result.reachedState();
        return new RunEvidence(
            runId,
            result.reached(),
            result.status().name(),
            result.metrics().exploredStates(),
            result.metrics().generatedTransformations(),
            reached == null ? List.of() : reached.path(),
            reached == null ? List.of() : reached.appliedRuleIds(),
            result.bestDistance(),
            result.metrics().candidateBudgetPrunes(),
            result.metrics().depthPrunes(),
            result.metrics().statesWithoutTransformations()
        );
    }

    private SearchProblem problem(
        String root,
        TransformationEngine engine,
        SearchHeuristic heuristic
    ) {
        return new SearchProblem(root, engine, scorer, canonicalizer, heuristic);
    }

    private ValueKey key(String expression) {
        Expr parsed = parser.parseTerm(expression);
        Expr canonical = canonicalizer.canonicalize(parsed);
        try (ExprValueFactory factory = new ExprValueFactory()) {
            return factory.fromExpr(canonical).key();
        }
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    public enum GuidanceVerdict {
        RECOVERED_WITHIN_BUDGET,
        FEWER_STATES,
        NO_MATERIAL_GAIN,
        BOTH_FAILED
    }

    public enum CapabilityVerdict {
        CAPABILITY_REQUIRED,
        CAPABILITY_NOT_REQUIRED,
        CAPABILITY_INSUFFICIENT_OR_BUDGET_LIMITED,
        CAPABILITY_REGRESSION
    }

    public record GuidanceScenario(
        String id,
        String rootExpression,
        String targetExpression,
        TransformationEngine engine,
        SearchHeuristic heuristic
    ) {
        public GuidanceScenario {
            requireText(id, "id");
            requireText(rootExpression, "rootExpression");
            requireText(targetExpression, "targetExpression");
            Objects.requireNonNull(engine, "engine");
            Objects.requireNonNull(heuristic, "heuristic");
        }
    }

    public record CapabilityScenario(
        String id,
        String capabilityId,
        String rootExpression,
        String targetExpression,
        TransformationEngine withoutCapability,
        TransformationEngine withCapability,
        SearchHeuristic heuristic
    ) {
        public CapabilityScenario {
            requireText(id, "id");
            requireText(capabilityId, "capabilityId");
            requireText(rootExpression, "rootExpression");
            requireText(targetExpression, "targetExpression");
            Objects.requireNonNull(withoutCapability, "withoutCapability");
            Objects.requireNonNull(withCapability, "withCapability");
            Objects.requireNonNull(heuristic, "heuristic");
        }
    }

    public record RunEvidence(
        String runId,
        boolean reached,
        String status,
        int exploredStates,
        int generatedTransformations,
        List<String> path,
        List<String> ruleIds,
        int bestDistance,
        int candidateBudgetPrunes,
        int depthPrunes,
        int statesWithoutTransformations
    ) {
        public RunEvidence {
            path = List.copyOf(path);
            ruleIds = List.copyOf(ruleIds);
        }
    }

    public record GuidanceComparison(
        String scenarioId,
        RunEvidence unguided,
        RunEvidence guided,
        GuidanceVerdict verdict
    ) {
    }

    public record CapabilityComparison(
        String scenarioId,
        String capabilityId,
        RunEvidence withoutCapability,
        RunEvidence withCapability,
        CapabilityVerdict verdict
    ) {
    }

    public record FrontierReport(
        String schema,
        List<GuidanceComparison> guidance,
        List<CapabilityComparison> capabilities
    ) {
        public FrontierReport {
            guidance = List.copyOf(guidance);
            capabilities = List.copyOf(capabilities);
        }

        public String toJson() {
            StringBuilder json = new StringBuilder();
            json.append("{\n  \"schema\": \"").append(escape(schema)).append("\",");
            json.append("\n  \"guidance\": [");
            appendGuidance(json, guidance);
            json.append("\n  ],\n  \"capabilities\": [");
            appendCapabilities(json, capabilities);
            json.append("\n  ]\n}\n");
            return json.toString();
        }

        private static void appendGuidance(StringBuilder json, List<GuidanceComparison> values) {
            for (int i = 0; i < values.size(); i++) {
                GuidanceComparison value = values.get(i);
                if (i > 0) {
                    json.append(',');
                }
                json.append("\n    {\"scenarioId\":\"")
                    .append(escape(value.scenarioId()))
                    .append("\",\"verdict\":\"")
                    .append(value.verdict())
                    .append("\",\"unguided\":");
                appendRun(json, value.unguided());
                json.append(",\"guided\":");
                appendRun(json, value.guided());
                json.append('}');
            }
        }

        private static void appendCapabilities(StringBuilder json, List<CapabilityComparison> values) {
            for (int i = 0; i < values.size(); i++) {
                CapabilityComparison value = values.get(i);
                if (i > 0) {
                    json.append(',');
                }
                json.append("\n    {\"scenarioId\":\"")
                    .append(escape(value.scenarioId()))
                    .append("\",\"capabilityId\":\"")
                    .append(escape(value.capabilityId()))
                    .append("\",\"verdict\":\"")
                    .append(value.verdict())
                    .append("\",\"withoutCapability\":");
                appendRun(json, value.withoutCapability());
                json.append(",\"withCapability\":");
                appendRun(json, value.withCapability());
                json.append('}');
            }
        }

        private static void appendRun(StringBuilder json, RunEvidence run) {
            json.append("{\"runId\":\"").append(escape(run.runId()))
                .append("\",\"reached\":").append(run.reached())
                .append(",\"status\":\"").append(escape(run.status()))
                .append("\",\"exploredStates\":").append(run.exploredStates())
                .append(",\"generatedTransformations\":").append(run.generatedTransformations())
                .append(",\"bestDistance\":").append(run.bestDistance())
                .append(",\"candidateBudgetPrunes\":").append(run.candidateBudgetPrunes())
                .append(",\"depthPrunes\":").append(run.depthPrunes())
                .append(",\"statesWithoutTransformations\":").append(run.statesWithoutTransformations())
                .append(",\"path\":");
            appendStrings(json, run.path());
            json.append(",\"ruleIds\":");
            appendStrings(json, run.ruleIds());
            json.append('}');
        }

        private static void appendStrings(StringBuilder json, List<String> strings) {
            json.append('[');
            for (int i = 0; i < strings.size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append('\"').append(escape(strings.get(i))).append('\"');
            }
            json.append(']');
        }

        private static String escape(String value) {
            return value == null ? "" : value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
