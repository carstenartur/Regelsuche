package de.regelsuche.benchmark;

import de.regelsuche.ast.Equation;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.math.algorithms.linalg.DirectScalarEliminationSolver;
import de.regelsuche.math.algorithms.linalg.ExactLinearSolutionConsequence;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem;
import de.regelsuche.math.algorithms.linalg.ExactRrefReduction;
import de.regelsuche.math.algorithms.linalg.ExactRrefSolver;
import de.regelsuche.math.algorithms.linalg.LinearSystemRepresentationBridge;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.representation.RepresentationBridge;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Frozen matched-work comparison between the exact representation/RREF route
 * and an independent direct scalar substitution/elimination route.
 *
 * <p>The experiment does not compare wall-clock time and does not use the old
 * per-equation product path as a baseline. Both routes receive the same parsed
 * scalar equations, declared variable order, exact arithmetic semantics and one
 * total primitive-work budget. A case is comparable only when both complete,
 * independently verify and emit the identical canonical terminal consequence.</p>
 */
public final class RepresentationMatchedWorkExperiment {
    public static final String SCHEMA =
        "regelsuche.representation-matched-work-report/v1";
    public static final String CONFIGURATION_ID =
        "representation-rref-v1-vs-direct-scalar-elimination-v1";
    public static final String REPRESENTATION_ROUTE =
        "REPRESENTATION_RREF_V1";
    public static final String DIRECT_ROUTE =
        "DIRECT_SCALAR_ELIMINATION_V1";
    public static final int DEFAULT_TOTAL_BUDGET = 200_000;

    private final ExpressionParser parser = new ExpressionParser();
    private final LinearSystemRepresentationBridge representationBridge =
        new LinearSystemRepresentationBridge();
    private final ExactRrefSolver rrefSolver = new ExactRrefSolver();
    private final DirectScalarEliminationSolver directSolver =
        new DirectScalarEliminationSolver();

    public static void main(String[] args) throws IOException {
        Path outputDirectory = args.length == 0
            ? Path.of("build", "reports", "representation-matched-work")
            : Path.of(args[0]);
        RepresentationMatchedWorkExperiment experiment =
            new RepresentationMatchedWorkExperiment();
        Report report = experiment.run(
            defaultCases(),
            DEFAULT_TOTAL_BUDGET);
        report.write(outputDirectory);
        if (!report.allComparableAndEquivalent()) {
            throw new IllegalStateException(
                "matched-work routes did not prove identical consequences");
        }
    }

    public Report run(
        List<ExperimentCase> cases,
        int totalBudget
    ) {
        Objects.requireNonNull(cases, "cases");
        if (cases.isEmpty()) {
            throw new IllegalArgumentException(
                "matched-work experiment requires cases");
        }
        if (totalBudget < 0) {
            throw new IllegalArgumentException(
                "totalBudget must not be negative");
        }
        List<CaseResult> results = cases.stream()
            .map(experimentCase -> runCase(experimentCase, totalBudget))
            .toList();
        return new Report(
            SCHEMA,
            CONFIGURATION_ID,
            totalBudget,
            results);
    }

    private CaseResult runCase(
        ExperimentCase experimentCase,
        int totalBudget
    ) {
        List<Equation> equations = parser.parse(new InputRequest(
            InputType.SYSTEM,
            experimentCase.expression())).equations();
        if (equations.isEmpty()) {
            throw new IllegalArgumentException(
                "experiment case parsed to no equations: "
                    + experimentCase.id());
        }

        RouteOutcome representation = representationRoute(
            equations,
            experimentCase.variables(),
            totalBudget);
        RouteOutcome direct = directRoute(
            equations,
            experimentCase.variables(),
            totalBudget);
        boolean comparable = representation.solvedAndVerified()
            && direct.solvedAndVerified();
        boolean equivalent = comparable
            && representation.consequence().equals(direct.consequence());
        Winner winner = !equivalent
            ? Winner.INCOMPARABLE
            : Integer.compare(
                representation.totalWork(),
                direct.totalWork()) < 0
                ? Winner.REPRESENTATION_RREF
                : Integer.compare(
                    representation.totalWork(),
                    direct.totalWork()) > 0
                    ? Winner.DIRECT_SCALAR
                    : Winner.TIE;
        return new CaseResult(
            experimentCase,
            representation,
            direct,
            comparable,
            equivalent,
            representation.totalWork() - direct.totalWork(),
            winner);
    }

    private RouteOutcome representationRoute(
        List<Equation> equations,
        List<String> declaredVariables,
        int totalBudget
    ) {
        RepresentationBridge.Result<
            ExactLinearSystem,
            LinearSystemRepresentationBridge.Certificate> represented =
                representationBridge.analyze(
                    equations,
                    new RepresentationBridge.Budget(totalBudget));
        int sourceWork = represented.work().consumedWorkUnits();
        if (!represented.represented()) {
            return RouteOutcome.incomplete(
                REPRESENTATION_ROUTE,
                represented.status().name(),
                represented.detailCode(),
                sourceWork,
                0,
                totalBudget);
        }

        ExactLinearSystem system = represented.representation().orElseThrow();
        if (!system.variables().equals(declaredVariables)) {
            return RouteOutcome.incomplete(
                REPRESENTATION_ROUTE,
                "VARIABLE_ORDER_MISMATCH",
                "represented=" + system.variables()
                    + ", declared=" + declaredVariables,
                sourceWork,
                0,
                totalBudget);
        }
        int remaining = Math.max(0, totalBudget - sourceWork);
        ExactRrefSolver.Result reduced = rrefSolver.solve(
            system,
            new RepresentationBridge.Budget(remaining));
        int consequenceWork = reduced.work().consumedWorkUnits();
        if (reduced.status() != ExactRrefSolver.Status.SOLVED) {
            return RouteOutcome.incomplete(
                REPRESENTATION_ROUTE,
                reduced.status().name(),
                reduced.detailCode(),
                sourceWork,
                consequenceWork,
                totalBudget);
        }

        ExactRrefReduction reduction = reduced.reduction().orElseThrow();
        ExactLinearSolutionConsequence consequence =
            ExactLinearSolutionConsequence.fromRref(reduction);
        boolean verified = representationBridge.verify(
            equations,
            represented)
            && rrefSolver.verify(system, reduced);
        return RouteOutcome.solved(
            REPRESENTATION_ROUTE,
            represented.detailCode() + "+" + reduced.detailCode(),
            sourceWork,
            consequenceWork,
            totalBudget,
            consequence,
            verified,
            List.of(
                "representationWork=" + sourceWork,
                "rrefWork=" + consequenceWork));
    }

    private RouteOutcome directRoute(
        List<Equation> equations,
        List<String> variables,
        int totalBudget
    ) {
        DirectScalarEliminationSolver.Source source =
            new DirectScalarEliminationSolver.Source(
                equations,
                variables);
        DirectScalarEliminationSolver.Result result = directSolver.solve(
            source,
            new RepresentationBridge.Budget(totalBudget));
        DirectScalarEliminationSolver.StageWorkProfile profile =
            result.workProfile();
        if (result.status()
                != DirectScalarEliminationSolver.Status.SOLVED) {
            return RouteOutcome.incomplete(
                DIRECT_ROUTE,
                result.status().name(),
                result.detailCode(),
                profile.sourceAnalysisWork(),
                profile.eliminationWork() + profile.evidenceWork(),
                totalBudget);
        }
        boolean verified = directSolver.verify(source, result);
        return RouteOutcome.solved(
            DIRECT_ROUTE,
            result.detailCode(),
            profile.sourceAnalysisWork(),
            profile.eliminationWork() + profile.evidenceWork(),
            totalBudget,
            result.consequence().orElseThrow(),
            verified,
            List.of(
                "sourceAnalysisWork=" + profile.sourceAnalysisWork(),
                "eliminationWork=" + profile.eliminationWork(),
                "evidenceWork=" + profile.evidenceWork()));
    }

    public static List<ExperimentCase> defaultCases() {
        return List.of(
            new ExperimentCase(
                "unique-dense-2x2",
                "2*x + y = 5; x - y = 1",
                List.of("x", "y"),
                "dense unique system"),
            new ExperimentCase(
                "underdetermined-redundant-2x3",
                "x + y + z = 2; 2*x + 2*y + 2*z = 4",
                List.of("x", "y", "z"),
                "redundant row and two free variables"),
            new ExperimentCase(
                "inconsistent-duplicate-left-side",
                "x + y = 1; x + y = 2",
                List.of("x", "y"),
                "same left side with incompatible constants"),
            new ExperimentCase(
                "block-diagonal-3x3",
                "2*x = 4; 3*y = 9; z = 1",
                List.of("x", "y", "z"),
                "three independent scalar blocks"),
            new ExperimentCase(
                "interleaved-two-blocks-4x4",
                "x + z = 3; y + w = 7; x - z = 1; y - w = 1",
                List.of("w", "x", "y", "z"),
                "two independent blocks interleaved by row"),
            new ExperimentCase(
                "rational-diagonal-2x2",
                "x / 2 = 1; y / 3 = 1",
                List.of("x", "y"),
                "exact rational coefficient scaling"),
            new ExperimentCase(
                "cancelled-coordinate-free",
                "x - x = 0",
                List.of("x"),
                "cancelled coefficient must retain free coordinate"),
            new ExperimentCase(
                "cancelled-coordinate-contradiction",
                "x - x = 1",
                List.of("x"),
                "cancelled coefficient must retain contradiction"));
    }

    public enum Winner {
        REPRESENTATION_RREF,
        DIRECT_SCALAR,
        TIE,
        INCOMPARABLE
    }

    public record ExperimentCase(
        String id,
        String expression,
        List<String> variables,
        String purpose
    ) {
        public ExperimentCase {
            id = requiredText(id, "id");
            expression = requiredText(expression, "expression");
            variables = List.copyOf(Objects.requireNonNull(
                variables,
                "variables"));
            purpose = requiredText(purpose, "purpose");
            if (variables.isEmpty()) {
                throw new IllegalArgumentException(
                    "case variables must not be empty");
            }
        }
    }

    public record RouteOutcome(
        String routeId,
        String status,
        String detailCode,
        int configuredBudget,
        int sourceWork,
        int consequenceWork,
        int totalWork,
        boolean verified,
        Optional<ExactLinearSolutionConsequence> consequence,
        List<String> stageDetails
    ) {
        public RouteOutcome {
            routeId = requiredText(routeId, "routeId");
            status = requiredText(status, "status");
            detailCode = requiredText(detailCode, "detailCode");
            if (configuredBudget < 0
                    || sourceWork < 0
                    || consequenceWork < 0
                    || totalWork < 0
                    || totalWork > configuredBudget
                    || totalWork != sourceWork + consequenceWork) {
                throw new IllegalArgumentException(
                    "route work accounting is invalid");
            }
            consequence = Objects.requireNonNull(
                consequence,
                "consequence");
            stageDetails = List.copyOf(Objects.requireNonNull(
                stageDetails,
                "stageDetails"));
            if (consequence.isPresent() != "SOLVED".equals(status)) {
                throw new IllegalArgumentException(
                    "only solved outcomes retain a consequence");
            }
        }

        private static RouteOutcome solved(
            String routeId,
            String detailCode,
            int sourceWork,
            int consequenceWork,
            int configuredBudget,
            ExactLinearSolutionConsequence consequence,
            boolean verified,
            List<String> stageDetails
        ) {
            return new RouteOutcome(
                routeId,
                "SOLVED",
                detailCode,
                configuredBudget,
                sourceWork,
                consequenceWork,
                sourceWork + consequenceWork,
                verified,
                Optional.of(consequence),
                stageDetails);
        }

        private static RouteOutcome incomplete(
            String routeId,
            String status,
            String detailCode,
            int sourceWork,
            int consequenceWork,
            int configuredBudget
        ) {
            return new RouteOutcome(
                routeId,
                status,
                detailCode,
                configuredBudget,
                sourceWork,
                consequenceWork,
                sourceWork + consequenceWork,
                false,
                Optional.empty(),
                List.of());
        }

        public boolean solvedAndVerified() {
            return "SOLVED".equals(status) && verified;
        }
    }

    public record CaseResult(
        ExperimentCase experimentCase,
        RouteOutcome representationRoute,
        RouteOutcome directRoute,
        boolean comparable,
        boolean equivalent,
        int representationMinusDirectWork,
        Winner winner
    ) {
        public CaseResult {
            experimentCase = Objects.requireNonNull(
                experimentCase,
                "experimentCase");
            representationRoute = Objects.requireNonNull(
                representationRoute,
                "representationRoute");
            directRoute = Objects.requireNonNull(
                directRoute,
                "directRoute");
            winner = Objects.requireNonNull(winner, "winner");
            if (equivalent && !comparable) {
                throw new IllegalArgumentException(
                    "equivalent case must be comparable");
            }
            if (representationMinusDirectWork
                    != representationRoute.totalWork()
                        - directRoute.totalWork()) {
                throw new IllegalArgumentException(
                    "case work delta is inconsistent");
            }
        }
    }

    public record Report(
        String schema,
        String configurationId,
        int totalBudgetPerRoute,
        List<CaseResult> cases
    ) {
        public Report {
            if (!SCHEMA.equals(schema)
                    || !CONFIGURATION_ID.equals(configurationId)
                    || totalBudgetPerRoute < 0) {
                throw new IllegalArgumentException(
                    "report identity or budget is invalid");
            }
            cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
            if (cases.isEmpty()) {
                throw new IllegalArgumentException(
                    "report must retain cases");
            }
        }

        public boolean allComparableAndEquivalent() {
            return cases.stream().allMatch(caseResult ->
                caseResult.comparable() && caseResult.equivalent());
        }

        public int representationWins() {
            return (int) cases.stream().filter(caseResult ->
                caseResult.winner() == Winner.REPRESENTATION_RREF).count();
        }

        public int directWins() {
            return (int) cases.stream().filter(caseResult ->
                caseResult.winner() == Winner.DIRECT_SCALAR).count();
        }

        public int ties() {
            return (int) cases.stream().filter(caseResult ->
                caseResult.winner() == Winner.TIE).count();
        }

        public void write(Path outputDirectory) throws IOException {
            Objects.requireNonNull(outputDirectory, "outputDirectory");
            Files.createDirectories(outputDirectory);
            Files.writeString(
                outputDirectory.resolve("matched-work-report.json"),
                toJson(),
                StandardCharsets.UTF_8);
            Files.writeString(
                outputDirectory.resolve("matched-work-report.md"),
                toMarkdown(),
                StandardCharsets.UTF_8);
        }

        public String toJson() {
            StringBuilder json = new StringBuilder();
            json.append("{\n")
                .append("  \"schema\": \"")
                .append(escape(schema))
                .append("\",\n")
                .append("  \"configurationId\": \"")
                .append(escape(configurationId))
                .append("\",\n")
                .append("  \"totalBudgetPerRoute\": ")
                .append(totalBudgetPerRoute)
                .append(",\n")
                .append("  \"allComparableAndEquivalent\": ")
                .append(allComparableAndEquivalent())
                .append(",\n")
                .append("  \"representationWins\": ")
                .append(representationWins())
                .append(",\n")
                .append("  \"directWins\": ")
                .append(directWins())
                .append(",\n")
                .append("  \"ties\": ")
                .append(ties())
                .append(",\n")
                .append("  \"cases\": [\n");
            for (int index = 0; index < cases.size(); index++) {
                if (index > 0) {
                    json.append(",\n");
                }
                appendCaseJson(json, cases.get(index));
            }
            return json.append("\n  ]\n}\n").toString();
        }

        public String toMarkdown() {
            StringBuilder markdown = new StringBuilder();
            markdown.append("# Exact representation matched-work report\n\n")
                .append("Configuration: `")
                .append(configurationId)
                .append("`  \n")
                .append("Total primitive-work budget per route: `")
                .append(totalBudgetPerRoute)
                .append("`  \n")
                .append("All cases comparable and equivalent: **")
                .append(allComparableAndEquivalent())
                .append("**\n\n")
                .append("| Case | Representation source | Representation RREF | Representation total | Direct source | Direct consequence | Direct total | Delta R-D | Winner |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---|\n");
            for (CaseResult result : cases) {
                markdown.append("| ")
                    .append(result.experimentCase().id())
                    .append(" | ")
                    .append(result.representationRoute().sourceWork())
                    .append(" | ")
                    .append(result.representationRoute().consequenceWork())
                    .append(" | ")
                    .append(result.representationRoute().totalWork())
                    .append(" | ")
                    .append(result.directRoute().sourceWork())
                    .append(" | ")
                    .append(result.directRoute().consequenceWork())
                    .append(" | ")
                    .append(result.directRoute().totalWork())
                    .append(" | ")
                    .append(result.representationMinusDirectWork())
                    .append(" | ")
                    .append(result.winner())
                    .append(" |\n");
            }
            markdown.append("\n## Claim boundary\n\n")
                .append("The table reports deterministic primitive-work counts under one frozen budget and identical verified terminal obligations. It does not establish wall-clock superiority, asymptotic superiority, global search superiority, or a result outside this frozen case set.\n");
            return markdown.toString();
        }

        private static void appendCaseJson(
            StringBuilder json,
            CaseResult result
        ) {
            json.append("    {\n")
                .append("      \"id\": \"")
                .append(escape(result.experimentCase().id()))
                .append("\",\n")
                .append("      \"expression\": \"")
                .append(escape(result.experimentCase().expression()))
                .append("\",\n")
                .append("      \"variables\": ")
                .append(stringArray(result.experimentCase().variables()))
                .append(",\n")
                .append("      \"purpose\": \"")
                .append(escape(result.experimentCase().purpose()))
                .append("\",\n")
                .append("      \"comparable\": ")
                .append(result.comparable())
                .append(",\n")
                .append("      \"equivalent\": ")
                .append(result.equivalent())
                .append(",\n")
                .append("      \"winner\": \"")
                .append(result.winner())
                .append("\",\n")
                .append("      \"representationMinusDirectWork\": ")
                .append(result.representationMinusDirectWork())
                .append(",\n")
                .append("      \"representationRoute\": ");
            appendRouteJson(json, result.representationRoute(), 6);
            json.append(",\n      \"directRoute\": ");
            appendRouteJson(json, result.directRoute(), 6);
            json.append("\n    }");
        }

        private static void appendRouteJson(
            StringBuilder json,
            RouteOutcome route,
            int indent
        ) {
            String padding = " ".repeat(indent);
            json.append("{\n")
                .append(padding).append("  \"routeId\": \"")
                .append(escape(route.routeId())).append("\",\n")
                .append(padding).append("  \"status\": \"")
                .append(escape(route.status())).append("\",\n")
                .append(padding).append("  \"detailCode\": \"")
                .append(escape(route.detailCode())).append("\",\n")
                .append(padding).append("  \"verified\": ")
                .append(route.verified()).append(",\n")
                .append(padding).append("  \"configuredBudget\": ")
                .append(route.configuredBudget()).append(",\n")
                .append(padding).append("  \"sourceWork\": ")
                .append(route.sourceWork()).append(",\n")
                .append(padding).append("  \"consequenceWork\": ")
                .append(route.consequenceWork()).append(",\n")
                .append(padding).append("  \"totalWork\": ")
                .append(route.totalWork()).append(",\n")
                .append(padding).append("  \"stageDetails\": ")
                .append(stringArray(route.stageDetails())).append(",\n")
                .append(padding).append("  \"consequence\": ")
                .append(route.consequence()
                    .map(ExactLinearSolutionConsequence::canonicalLines)
                    .map(Report::stringArray)
                    .orElse("null"))
                .append("\n")
                .append(padding).append("}");
        }

        private static String stringArray(List<String> values) {
            return values.stream()
                .map(value -> "\"" + escape(value) + "\"")
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
        }

        private static String escape(String value) {
            return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        }
    }

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
