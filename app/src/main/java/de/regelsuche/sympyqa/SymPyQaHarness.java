package de.regelsuche.sympyqa;

import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.Transformation;
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
    private static final List<String> DEFAULT_TERMS = List.of(
        "(x+1)^2",
        "x^2 + 2*x + 1",
        "x/y + z/y",
        "(x*z)/(y*z)",
        "1/(n*(n+1))",
        "sin(x)^2 + cos(x)^2",
        "x^4 + 4*y^4"
    );

    private final AstRewriteTransformationEngine engine;
    private final SymPyDiscoveryOracleAdapter oracle = new SymPyDiscoveryOracleAdapter();
    private final ExpressionParser parser = new ExpressionParser();

    public SymPyQaHarness() {
        this(AstRewriteTransformationEngine.withKnowledgePacks(KnowledgePackSelection.profile(
            de.regelsuche.knowledge.RuleProfile.ALL)));
    }

    SymPyQaHarness(AstRewriteTransformationEngine engine) {
        this.engine = engine;
    }

    public QaSummary runDefault(Path reportDirectory) {
        return run(DEFAULT_TERMS, reportDirectory);
    }

    public QaSummary run(List<String> expressions, Path reportDirectory) {
        List<String> terms = expressions == null || expressions.isEmpty() ? DEFAULT_TERMS : List.copyOf(expressions);
        List<QaCase> cases = new ArrayList<>();
        for (String expression : terms) {
            cases.add(analyze(expression));
        }
        QaSummary summary = buildSummary(cases);
        writeReports(reportDirectory, summary, cases);
        return summary;
    }

    private QaCase analyze(String expression) {
        List<Transformation> candidates = engine.transform(expression);
        Transformation selected = candidates.isEmpty() ? null : candidates.getFirst();
        String regelsucheResult = selected == null ? normalize(expression) : selected.transformedExpression();
        String sympyResult = simplifyWithSymPy(expression);
        boolean sympyAvailable = sympyResult != null;
        SymPyDiscoveryOracleAdapter.OracleResult equivalence = oracle.equivalence(regelsucheResult, sympyAvailable ? sympyResult : expression);
        return new QaCase(
            expression,
            regelsucheResult,
            sympyAvailable ? sympyResult : "UNAVAILABLE",
            sympyAvailable,
            candidates.size(),
            selected == null ? "" : selected.rule(),
            selected == null ? "" : selected.packId(),
            equivalence.status().name(),
            equivalence.evidence()
        );
    }

    private String simplifyWithSymPy(String expression) {
        String normalized;
        try {
            normalized = ExpressionFormatter.format(parser.parseTerm(expression)).replace("^", "**");
        } catch (IllegalArgumentException ex) {
            return null;
        }
        String script = "import sympy as sp\n"
            + "from sympy.parsing.sympy_parser import parse_expr\n"
            + "str(sp.simplify(parse_expr('" + escape(normalized) + "', evaluate=False)))";
        try (Context context = Context.newBuilder("python").build()) {
            Value value = context.eval("python", script);
            return value.asString().replace("**", "^");
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private QaSummary buildSummary(List<QaCase> cases) {
        long sympyAvailableCases = cases.stream().filter(QaCase::sympyAvailable).count();
        long disagreements = cases.stream()
            .filter(QaCase::sympyAvailable)
            .filter(row -> "DISAGREE".equals(row.oracleStatus()))
            .count();
        long noPath = cases.stream().filter(row -> row.pathLength() == 0).count();
        return new QaSummary(
            cases.size(),
            sympyAvailableCases,
            disagreements,
            noPath,
            cases.size() - noPath
        );
    }

    private void writeReports(Path reportDirectory, QaSummary summary, List<QaCase> cases) {
        try {
            Path dir = reportDirectory == null
                ? Path.of("build", "reports", "sympy-qa")
                : reportDirectory;
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("summary.json"), summaryJson(summary));
            Files.writeString(dir.resolve("disagreements.md"), disagreementsMarkdown(cases));
            Files.writeString(dir.resolve("candidate-rules.md"), candidateRulesMarkdown(cases));
            Files.writeString(dir.resolve("interesting-discoveries.md"), interestingMarkdown(cases));
        } catch (IOException exception) {
            throw new IllegalStateException("failed to write SymPy QA reports", exception);
        }
    }

    private String summaryJson(QaSummary summary) {
        return "{\n"
            + "  \"schema\": \"regelsuche.sympy-qa.summary/v1\",\n"
            + "  \"totalCases\": " + summary.totalCases() + ",\n"
            + "  \"sympyAvailableCases\": " + summary.sympyAvailableCases() + ",\n"
            + "  \"disagreements\": " + summary.disagreements() + ",\n"
            + "  \"regelsucheNoPath\": " + summary.regelsucheNoPath() + ",\n"
            + "  \"regelsuchePathFound\": " + summary.regelsuchePathFound() + "\n"
            + "}\n";
    }

    private String disagreementsMarkdown(List<QaCase> cases) {
        StringBuilder builder = new StringBuilder("# Disagreements\n\n");
        cases.stream()
            .filter(QaCase::sympyAvailable)
            .filter(row -> "DISAGREE".equals(row.oracleStatus()))
            .forEach(row -> builder
                .append("- `").append(row.input()).append("` → Regelsuche `")
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
            counts.merge(row.ruleId() + " [" + row.packId() + "]", 1L, Long::sum);
        }
        StringBuilder builder = new StringBuilder("# Candidate rules\n\n");
        counts.forEach((rule, count) -> builder.append("- ").append(rule).append(": ").append(count).append('\n'));
        if (counts.isEmpty()) {
            builder.append("- none\n");
        }
        return builder.toString();
    }

    private String interestingMarkdown(List<QaCase> cases) {
        StringBuilder builder = new StringBuilder("# Interesting discoveries\n\n");
        cases.stream()
            .filter(row -> row.pathLength() > 0)
            .filter(row -> row.sympyAvailable() && !row.regelsucheResult().equals(row.sympyResult()))
            .forEach(row -> builder
                .append("- input `").append(row.input()).append("` Regelsuche `")
                .append(row.regelsucheResult()).append("` vs SymPy `")
                .append(row.sympyResult()).append("`\n"));
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

    public record QaSummary(
        long totalCases,
        long sympyAvailableCases,
        long disagreements,
        long regelsucheNoPath,
        long regelsuchePathFound
    ) {
    }

    public record QaCase(
        String input,
        String regelsucheResult,
        String sympyResult,
        boolean sympyAvailable,
        int pathLength,
        String ruleId,
        String packId,
        String oracleStatus,
        String oracleEvidence
    ) {
    }
}
