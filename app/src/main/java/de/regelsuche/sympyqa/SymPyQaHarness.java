package de.regelsuche.sympyqa;

import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.knowledge.RuleProfile;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.PatternPreparationPlan.Budget;
import de.regelsuche.transform.PatternTargetedPreparationTransformationEngine;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import de.regelsuche.validation.SymPyDiscoveryOracleAdapter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/** SymPy-vs-Regelsuche QA harness with optional SymPy runtime and report export. */
public final class SymPyQaHarness {
    private static final String HIDDEN_PYTHAGOREAN =
        "((sin(x) * a) / a)^2 + ((cos(x) * b) / b)^2";
    private static final List<String> DEFAULT_TERMS = List.of(
        "(x+1)^2",
        "x^2 + 2*x + 1",
        "x/y + z/y",
        "(x*z)/(y*z)",
        "1/(n*(n+1))",
        "sin(x)^2 + cos(x)^2",
        HIDDEN_PYTHAGOREAN,
        "sin(x)^2 + cos(y)^2",
        "x^4 + 4*y^4"
    );

    private final TransformationEngine directEngine;
    private final TransformationEngine safePreparationEngine;
    private final SymPyDiscoveryOracleAdapter oracle =
        new SymPyDiscoveryOracleAdapter();
    private final ExpressionParser parser = new ExpressionParser();

    public SymPyQaHarness() {
        this(defaultDirectEngine(), defaultSafePreparationEngine());
    }

    SymPyQaHarness(AstRewriteTransformationEngine engine) {
        this(engine, engine);
    }

    SymPyQaHarness(
        TransformationEngine directEngine,
        TransformationEngine safePreparationEngine
    ) {
        this.directEngine = directEngine;
        this.safePreparationEngine = safePreparationEngine;
    }

    public QaSummary runDefault(Path reportDirectory) {
        return run(DEFAULT_TERMS, reportDirectory);
    }

    public QaSummary run(List<String> expressions, Path reportDirectory) {
        List<String> terms = expressions == null || expressions.isEmpty()
            ? DEFAULT_TERMS
            : List.copyOf(expressions);
        List<QaCase> cases = new ArrayList<>();
        for (String expression : terms) {
            cases.add(analyze(expression));
        }
        QaSummary summary = buildSummary(cases);
        writeReports(reportDirectory, summary, cases);
        return summary;
    }

    private QaCase analyze(String expression) {
        List<Transformation> directCandidates = directEngine.transform(
            expression);
        List<Transformation> safeCandidates = safePreparationEngine.transform(
            expression);
        Transformation selected = directCandidates.isEmpty()
            ? null
            : directCandidates.getFirst();
        String regelsucheResult = selected == null
            ? normalize(expression)
            : selected.transformedExpression();
        String sympyResult = simplifyWithSymPy(expression);
        boolean sympyAvailable = sympyResult != null;
        SymPyDiscoveryOracleAdapter.OracleResult equivalence =
            oracle.equivalence(
                regelsucheResult,
                sympyAvailable ? sympyResult : expression);
        List<AmplifiedCandidate> amplified = safeCandidates.stream()
            .filter(candidate -> candidate.applicationKey().startsWith(
                "pattern-prepared:"))
            .map(candidate -> new AmplifiedCandidate(
                candidate.rule(),
                candidate.transformedExpression(),
                candidate.assumptions(),
                candidate.primitiveRuleIds()))
            .toList();
        return new QaCase(
            expression,
            regelsucheResult,
            sympyAvailable ? sympyResult : "UNAVAILABLE",
            sympyAvailable,
            directCandidates.size(),
            safeCandidates.size(),
            amplified,
            selected == null ? "" : selected.rule(),
            selected == null ? "" : selected.packId(),
            equivalence.status().name(),
            equivalence.evidence());
    }

    private String simplifyWithSymPy(String expression) {
        String normalized;
        try {
            normalized = ExpressionFormatter.format(
                parser.parseTerm(expression)).replace("^", "**");
        } catch (IllegalArgumentException ex) {
            return null;
        }
        String script = "import sympy as sp\n"
            + "from sympy.parsing.sympy_parser import parse_expr\n"
            + "str(sp.simplify(parse_expr('"
            + escape(normalized)
            + "', evaluate=False)))";
        try (Context context = Context.newBuilder("python").build()) {
            Value value = context.eval("python", script);
            return value.asString().replace("**", "^");
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private QaSummary buildSummary(List<QaCase> cases) {
        long sympyAvailableCases = cases.stream()
            .filter(QaCase::sympyAvailable)
            .count();
        long disagreements = cases.stream()
            .filter(QaCase::sympyAvailable)
            .filter(row -> "DISAGREE".equals(row.oracleStatus()))
            .count();
        long noPath = cases.stream()
            .filter(row -> row.pathLength() == 0)
            .count();
        long amplifiedCases = cases.stream()
            .filter(row -> !row.amplifiedCandidates().isEmpty())
            .count();
        long amplifiedCandidates = cases.stream()
            .mapToLong(row -> row.amplifiedCandidates().size())
            .sum();
        return new QaSummary(
            cases.size(),
            sympyAvailableCases,
            disagreements,
            noPath,
            cases.size() - noPath,
            amplifiedCases,
            amplifiedCandidates);
    }

    private void writeReports(
        Path reportDirectory,
        QaSummary summary,
        List<QaCase> cases
    ) {
        try {
            Path dir = reportDirectory == null
                ? Path.of("build", "reports", "sympy-qa")
                : reportDirectory;
            Files.createDirectories(dir);
            Files.writeString(
                dir.resolve("summary.json"),
                summaryJson(summary));
            Files.writeString(
                dir.resolve("disagreements.md"),
                disagreementsMarkdown(cases));
            Files.writeString(
                dir.resolve("candidate-rules.md"),
                candidateRulesMarkdown(cases));
            Files.writeString(
                dir.resolve("interesting-discoveries.md"),
                interestingMarkdown(cases));
            Files.writeString(
                dir.resolve("rule-amplification.md"),
                ruleAmplificationMarkdown(cases));
        } catch (IOException exception) {
            throw new IllegalStateException(
                "failed to write SymPy QA reports",
                exception);
        }
    }

    private String summaryJson(QaSummary summary) {
        return "{\n"
            + "  \"schema\": \"regelsuche.sympy-qa.summary/v2\",\n"
            + "  \"totalCases\": " + summary.totalCases() + ",\n"
            + "  \"sympyAvailableCases\": "
            + summary.sympyAvailableCases() + ",\n"
            + "  \"disagreements\": " + summary.disagreements() + ",\n"
            + "  \"regelsucheNoPath\": "
            + summary.regelsucheNoPath() + ",\n"
            + "  \"regelsuchePathFound\": "
            + summary.regelsuchePathFound() + ",\n"
            + "  \"amplifiedCases\": "
            + summary.amplifiedCases() + ",\n"
            + "  \"amplifiedCandidates\": "
            + summary.amplifiedCandidates() + "\n"
            + "}\n";
    }

    private String disagreementsMarkdown(List<QaCase> cases) {
        StringBuilder builder = new StringBuilder("# Disagreements\n\n");
        cases.stream()
            .filter(QaCase::sympyAvailable)
            .filter(row -> "DISAGREE".equals(row.oracleStatus()))
            .forEach(row -> builder
                .append("- `").append(row.input())
                .append("` → Regelsuche `")
                .append(row.regelsucheResult()).append("`, SymPy `")
                .append(row.sympyResult()).append("` (oracle: ")
                .append(row.oracleEvidence()).append(")\n"));
        if (builder.toString().endsWith("\n\n")) {
            builder.append("- none\n");
        }
        return builder.toString();
    }

    private String candidateRulesMarkdown(List<QaCase> cases) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (QaCase row : cases) {
            if (row.ruleId().isBlank()) {
                continue;
            }
            counts.merge(
                row.ruleId() + " [" + row.packId() + "]",
                1L,
                Long::sum);
        }
        StringBuilder builder = new StringBuilder("# Candidate rules\n\n");
        counts.forEach((rule, count) -> builder
            .append("- ").append(rule).append(": ")
            .append(count).append('\n'));
        if (counts.isEmpty()) {
            builder.append("- none\n");
        }
        return builder.toString();
    }

    private String interestingMarkdown(List<QaCase> cases) {
        StringBuilder builder = new StringBuilder(
            "# Interesting discoveries\n\n");
        cases.stream()
            .filter(row -> row.pathLength() > 0)
            .filter(row -> row.sympyAvailable()
                && !row.regelsucheResult().equals(row.sympyResult()))
            .forEach(row -> builder
                .append("- input `").append(row.input())
                .append("` Regelsuche `")
                .append(row.regelsucheResult()).append("` vs SymPy `")
                .append(row.sympyResult()).append("`\n"));
        if (builder.toString().endsWith("\n\n")) {
            builder.append("- none\n");
        }
        return builder.toString();
    }

    private String ruleAmplificationMarkdown(List<QaCase> cases) {
        StringBuilder builder = new StringBuilder(
            "# Rule amplification\n\n"
                + "Only candidates absent from the direct stage and carrying "
                + "a replayed pattern-preparation path are listed.\n\n");
        for (QaCase row : cases) {
            for (AmplifiedCandidate candidate : row.amplifiedCandidates()) {
                builder.append("- input `").append(row.input())
                    .append("`: `").append(candidate.ruleId())
                    .append("` → `").append(candidate.result())
                    .append("`; assumptions ")
                    .append(candidate.assumptions())
                    .append("; primitive lineage ")
                    .append(candidate.primitiveRuleIds())
                    .append('\n');
            }
        }
        if (builder.toString().endsWith("\n\n")) {
            builder.append("- none\n");
        }
        return builder.toString();
    }

    private String escape(String expression) {
        return expression.replace("\\", "\\\\").replace("'", "\\'");
    }

    private String normalize(String expression) {
        try {
            return ExpressionFormatter.format(parser.parseTerm(expression));
        } catch (IllegalArgumentException ex) {
            return expression;
        }
    }

    private static TransformationEngine defaultDirectEngine() {
        return AstRewriteTransformationEngine.withKnowledgePacks(
            allPacks());
    }

    private static TransformationEngine defaultSafePreparationEngine() {
        return PatternTargetedPreparationTransformationEngine
            .symPyPythagoreanPilot(
                allPacks(),
                new Budget(2, 128, 2_048, 6, 256, 80, 20_000));
    }

    private static KnowledgePackSelection allPacks() {
        return KnowledgePackSelection.profile(RuleProfile.ALL);
    }

    public record QaSummary(
        long totalCases,
        long sympyAvailableCases,
        long disagreements,
        long regelsucheNoPath,
        long regelsuchePathFound,
        long amplifiedCases,
        long amplifiedCandidates
    ) {
    }

    public record AmplifiedCandidate(
        String ruleId,
        String result,
        List<String> assumptions,
        List<String> primitiveRuleIds
    ) {
        public AmplifiedCandidate {
            assumptions = List.copyOf(assumptions);
            primitiveRuleIds = List.copyOf(primitiveRuleIds);
        }
    }

    public record QaCase(
        String input,
        String regelsucheResult,
        String sympyResult,
        boolean sympyAvailable,
        int pathLength,
        int safeCandidateCount,
        List<AmplifiedCandidate> amplifiedCandidates,
        String ruleId,
        String packId,
        String oracleStatus,
        String oracleEvidence
    ) {
        public QaCase {
            amplifiedCandidates = List.copyOf(amplifiedCandidates);
        }
    }
}
