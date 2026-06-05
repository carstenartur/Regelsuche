package de.regelsuche.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.math.algorithms.equivalence.PolynomialNormalFormEquivalenceService;
import de.regelsuche.math.algorithms.registry.DefaultMathematicalAlgorithmRegistry;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.util.AtomicJsonFile;
import de.regelsuche.validation.SymPyDiscoveryOracleAdapter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Runs Discovery Campaign 6 (Open-Ended Identity Mining).
 *
 * <p>Unlike campaigns 1-5, which validate curated input/target pairs, this campaign lets Regelsuche
 * <em>generate</em> identity candidates on its own. It seeds known identity families, applies a fixed
 * catalogue of substitutions (for example {@code x+1}, {@code sin(x)}, {@code a+b}, {@code x^2}),
 * checks equivalence deterministically (and corroborates with a SymPy oracle when available), ranks the
 * candidates by brevity, surprise, path length, reusability and difference from the source expression,
 * and reports the Top-20 as possible new macros.
 */
public final class DiscoveryCampaignSixRunner {
    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private static final String PLACEHOLDER = "U";
    private static final String ATOM = "u";
    private static final int TOP_LIMIT = 20;
    private static final double BREVITY_NODE_CAP = 14.0;
    private static final double PROMOTABLE_THRESHOLD = 0.45;

    private final ExpressionParser parser = new ExpressionParser();
    private final PolynomialNormalFormEquivalenceService equivalence =
        new PolynomialNormalFormEquivalenceService(new DefaultMathematicalAlgorithmRegistry());
    private final SymPyDiscoveryOracleAdapter oracle = new SymPyDiscoveryOracleAdapter();

    public static void main(String[] args) {
        Path repoRoot = args.length == 0
            ? Path.of(".").toAbsolutePath().normalize()
            : Path.of(args[0]).toAbsolutePath().normalize();
        new DiscoveryCampaignSixRunner()
            .writeReport(repoRoot.resolve("app/build/reports/discovery-campaign-6"));
    }

    public CampaignReport run() {
        List<Candidate> scored = new ArrayList<>();
        for (Seed seed : seeds()) {
            for (Substitution substitution : substitutions()) {
                scored.add(mine(seed, substitution));
            }
        }
        scored.sort(Comparator
            .comparingDouble(Candidate::interestingness).reversed()
            .thenComparing(Candidate::id));

        List<Candidate> ranked = new ArrayList<>();
        int rank = 1;
        for (Candidate candidate : scored) {
            if (rank > TOP_LIMIT) {
                break;
            }
            ranked.add(candidate.withRank(rank));
            rank++;
        }
        long promotable = ranked.stream().filter(Candidate::promotable).count();
        return new CampaignReport("discovery-campaign-6", ranked, scored.size(), (int) promotable);
    }

    public CampaignReport writeReport(Path outputDirectory) {
        return writeReport(outputDirectory, run());
    }

    CampaignReport writeReport(Path outputDirectory, CampaignReport report) {
        try {
            Files.createDirectories(outputDirectory);
            AtomicJsonFile.writeUtf8(
                outputDirectory.resolve("discovery-campaign-6.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)
            );
            Files.writeString(
                outputDirectory.resolve("identity-mining-report.md"),
                renderMarkdown(report),
                StandardCharsets.UTF_8
            );
            return report;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private Candidate mine(Seed seed, Substitution substitution) {
        String id = seed.id() + "--" + substitution.id();
        String sourceExpression = instantiate(seed.sourceTemplate(), substitution.expression());
        String simplifiedExpression = instantiate(seed.simplifiedTemplate(), substitution.expression());

        // Deterministic, offline equivalence: the substituted subterm is treated as a single fresh
        // atom so the family identity reduces to a pure polynomial normal-form comparison.
        String atomicSource = seed.sourceTemplate().replace(PLACEHOLDER, ATOM);
        String atomicSimplified = seed.simplifiedTemplate().replace(PLACEHOLDER, ATOM);
        boolean equivalent = equivalence.arePolynomiallyEquivalent(atomicSource, atomicSimplified);
        String deterministicEvidence = equivalence.lastResult().detail();

        SymPyDiscoveryOracleAdapter.OracleResult oracleResult =
            oracle.equivalence(sourceExpression, simplifiedExpression);

        int sourceNodes = nodeCount(sourceExpression);
        int simplifiedNodes = nodeCount(simplifiedExpression);
        int baseNodes = nodeCount(instantiate(seed.sourceTemplate(), "x"));
        Set<String> variables = variablesOf(sourceExpression);

        List<String> path = buildPath(seed, substitution, sourceExpression, simplifiedExpression);

        double brevity = clamp(1.0 - (simplifiedNodes / BREVITY_NODE_CAP));
        double surprise = sourceNodes == 0
            ? 0.0
            : clamp((double) (sourceNodes - simplifiedNodes) / sourceNodes);
        double pathLengthScore = clamp((path.size() - 1) / 3.0);
        double reusability = clamp(
            0.5 * clamp((variables.size() - 1) / 2.0) + 0.5 * substitution.generality());
        double differenceFromSource = baseNodes == 0
            ? 0.0
            : clamp((double) Math.max(0, sourceNodes - baseNodes) / baseNodes);

        Scores scores = new Scores(brevity, surprise, pathLengthScore, reusability, differenceFromSource);
        double interestingness = clamp(
            0.20 * brevity
                + 0.30 * surprise
                + 0.15 * pathLengthScore
                + 0.20 * reusability
                + 0.15 * differenceFromSource);

        boolean knownSeed = substitution.identity();
        boolean promotable = equivalent
            && !knownSeed
            && interestingness >= PROMOTABLE_THRESHOLD
            && oracleResult.status() != SymPyDiscoveryOracleAdapter.Status.DISAGREE;

        String whyInteresting = explain(seed, substitution, sourceNodes, simplifiedNodes,
            variables.size(), path.size(), knownSeed, scores);

        return new Candidate(
            0,
            id,
            seed.family(),
            seed.knownRuleId(),
            substitution.expression(),
            sourceExpression,
            simplifiedExpression,
            path,
            equivalent,
            deterministicEvidence,
            oracleResult.status().name(),
            oracleResult.evidence(),
            scores,
            interestingness,
            promotable,
            whyInteresting
        );
    }

    private List<String> buildPath(Seed seed, Substitution substitution, String source, String simplified) {
        List<String> path = new ArrayList<>();
        path.add("seed:" + seed.family() + " (" + seed.sourceTemplate() + ")");
        path.add("substitute " + PLACEHOLDER + " := " + substitution.expression());
        if (!substitution.atomic()) {
            path.add("expand to " + source);
        }
        path.add("compress to " + simplified);
        return List.copyOf(path);
    }

    private String explain(Seed seed, Substitution substitution, int sourceNodes, int simplifiedNodes,
                           int variableCount, int pathSteps, boolean knownSeed, Scores scores) {
        if (knownSeed) {
            return "Rediscovers the known " + seed.family() + " identity on its base variable; "
                + "kept as a seed reference, not promotable.";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Substituting ").append(substitution.expression())
            .append(" into the ").append(seed.family())
            .append(" family compresses ").append(sourceNodes)
            .append(" nodes down to ").append(simplifiedNodes).append(" (surprise ")
            .append(format(scores.surprise())).append(").");
        if (variableCount > 1) {
            builder.append(" Reusable across ").append(variableCount).append(" free variables.");
        }
        if (!substitution.atomic()) {
            builder.append(" Hidden structure revealed after a ").append(pathSteps).append("-step derivation.");
        }
        return builder.toString();
    }

    private String instantiate(String template, String substitution) {
        String raw = template.replace(PLACEHOLDER, "(" + substitution + ")");
        try {
            return ExpressionFormatter.format(parser.parseTerm(raw));
        } catch (IllegalArgumentException ex) {
            return raw;
        }
    }

    private int nodeCount(String expression) {
        try {
            return nodeCount(parser.parseTerm(expression));
        } catch (IllegalArgumentException ex) {
            return expression.length();
        }
    }

    private int nodeCount(Expr expression) {
        if (expression instanceof BinaryExpr binaryExpr) {
            return 1 + nodeCount(binaryExpr.left()) + nodeCount(binaryExpr.right());
        }
        if (expression instanceof FunctionExpr functionExpr) {
            int total = 1;
            for (Expr argument : functionExpr.arguments()) {
                total += nodeCount(argument);
            }
            return total;
        }
        return 1;
    }

    private Set<String> variablesOf(String expression) {
        Set<String> variables = new LinkedHashSet<>();
        try {
            collectVariables(parser.parseTerm(expression), variables);
        } catch (IllegalArgumentException ignored) {
            // Leave the accumulated set as-is for unparseable expressions.
        }
        return variables;
    }

    private void collectVariables(Expr expression, Set<String> variables) {
        if (expression instanceof VariableExpr variableExpr) {
            variables.add(variableExpr.name());
        } else if (expression instanceof BinaryExpr binaryExpr) {
            collectVariables(binaryExpr.left(), variables);
            collectVariables(binaryExpr.right(), variables);
        } else if (expression instanceof FunctionExpr functionExpr) {
            for (Expr argument : functionExpr.arguments()) {
                collectVariables(argument, variables);
            }
        }
    }

    private double clamp(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        return Math.min(value, 1.0);
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private String renderMarkdown(CampaignReport report) {
        StringBuilder out = new StringBuilder();
        out.append("# Discovery Campaign 6: Open-Ended Identity Mining\n\n");
        out.append("Regelsuche generated ").append(report.generatedCount())
            .append(" identity candidates from seed families and substitutions, then ranked them. ")
            .append("The Top ").append(report.topCandidates().size())
            .append(" are listed below (").append(report.promotableCount())
            .append(" promotable as new macros).\n\n");
        out.append("Ranking factors: brevity, surprise, path length, reusability, difference from source.\n\n");
        out.append("| Rank | Candidate | Family | Source | Simplified | Path | Oracle | Equivalent | Score | Promotable | Why interesting |\n");
        out.append("| ---: | --- | --- | --- | --- | --- | --- | --- | ---: | --- | --- |\n");
        for (Candidate candidate : report.topCandidates()) {
            out.append("| ").append(candidate.rank())
                .append(" | ").append(escape(candidate.id()))
                .append(" | ").append(escape(candidate.family()))
                .append(" | ").append(escape(candidate.sourceExpression()))
                .append(" | ").append(escape(candidate.simplifiedExpression()))
                .append(" | ").append(escape(String.join(" -> ", candidate.path())))
                .append(" | ").append(escape(candidate.oracleStatus().toLowerCase(Locale.ROOT)))
                .append(" | ").append(candidate.equivalent() ? "yes" : "no")
                .append(" | ").append(format(candidate.interestingness()))
                .append(" | ").append(candidate.promotable() ? "yes" : "no")
                .append(" | ").append(escape(candidate.whyInteresting()))
                .append(" |\n");
        }
        out.append("\n## Oracle / proof evidence\n\n");
        for (Candidate candidate : report.topCandidates()) {
            out.append("- **").append(escape(candidate.id())).append("**: ")
                .append(escape(candidate.deterministicEvidence()));
            if (!candidate.oracleEvidence().isBlank()) {
                out.append(" — oracle: ").append(escape(candidate.oracleEvidence()));
            }
            out.append('\n');
        }
        return out.toString();
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    private List<Seed> seeds() {
        return List.of(
            new Seed("complete-square-plus", "complete-square", "complete_square",
                "U^2 + 2*U + 1", "(U + 1)^2"),
            new Seed("complete-square-minus", "complete-square", "complete_square",
                "U^2 - 2*U + 1", "(U - 1)^2"),
            new Seed("square-of-sum-two", "perfect-square", "perfect_square_trinomial",
                "U^2 + 4*U + 4", "(U + 2)^2"),
            new Seed("difference-of-squares", "difference-of-squares", "difference_of_squares",
                "U^2 - 1", "(U - 1)*(U + 1)"),
            new Seed("binomial-cube-plus", "binomial-cube", "binomial_cube",
                "U^3 + 3*U^2 + 3*U + 1", "(U + 1)^3"),
            new Seed("binomial-cube-minus", "binomial-cube", "binomial_cube",
                "U^3 - 3*U^2 + 3*U - 1", "(U - 1)^3")
        );
    }

    private List<Substitution> substitutions() {
        return List.of(
            new Substitution("x", "x", true, true, 0.10),
            new Substitution("x-plus-1", "x + 1", false, false, 0.30),
            new Substitution("x-squared", "x^2", false, false, 0.45),
            new Substitution("sin-x", "sin(x)", true, false, 0.60),
            new Substitution("cos-x", "cos(x)", true, false, 0.60),
            new Substitution("a-plus-b", "a + b", false, false, 1.00),
            new Substitution("two-x", "2*x", false, false, 0.35)
        );
    }

    /** A seed identity family parameterised by a single placeholder {@value #PLACEHOLDER}. */
    public record Seed(String id, String family, String knownRuleId, String sourceTemplate, String simplifiedTemplate) {
    }

    /**
     * A substitution applied to the placeholder.
     *
     * @param atomic whether the substitution is a single atom (variable or function call) that does not
     *               require an explicit expansion step
     * @param identity whether the substitution leaves the seed on its base variable (a known-rule rediscovery)
     * @param generality reusability weight in [0,1] reflecting how generic the substitution is
     */
    public record Substitution(String id, String expression, boolean atomic, boolean identity, double generality) {
    }

    /** Per-factor ranking scores, each in [0,1]. */
    public record Scores(double brevity, double surprise, double pathLength, double reusability,
                         double differenceFromSource) {
    }

    /** A mined identity candidate with its evidence and ranking. */
    public record Candidate(
        int rank,
        String id,
        String family,
        String knownRuleId,
        String substitution,
        String sourceExpression,
        String simplifiedExpression,
        List<String> path,
        boolean equivalent,
        String deterministicEvidence,
        String oracleStatus,
        String oracleEvidence,
        Scores scores,
        double interestingness,
        boolean promotable,
        String whyInteresting
    ) {
        public Candidate {
            path = path == null ? List.of() : List.copyOf(path);
        }

        Candidate withRank(int newRank) {
            return new Candidate(newRank, id, family, knownRuleId, substitution, sourceExpression,
                simplifiedExpression, path, equivalent, deterministicEvidence, oracleStatus, oracleEvidence,
                scores, interestingness, promotable, whyInteresting);
        }
    }

    /** Top-ranked report for Discovery Campaign 6. */
    public record CampaignReport(String campaignId, List<Candidate> topCandidates, int generatedCount,
                                int promotableCount) {
        public CampaignReport {
            topCandidates = topCandidates == null ? List.of() : List.copyOf(topCandidates);
        }
    }
}
