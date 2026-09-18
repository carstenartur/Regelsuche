package de.regelsuche.benchmark;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.mining.GeneralizedPattern;
import de.regelsuche.mining.PatternGeneralizer;
import de.regelsuche.mining.SuccessfulTransformationPath;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.AstRewriteTransport;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RecognitionProfile;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Held-out evaluation stage introduced only after the TRAIN artifact was frozen. */
public final class ModPowReuseLearningEvaluation {
    public static final String FROZEN_CONTENT_SHA256 =
        "c582cec2e85426ad89fc35af22347a0920d7c8de26aa5a07b74cc14bb58ca6d6";

    private static final ExpressionParser PARSER = new ExpressionParser();
    private static final Pattern JSON_STRING = Pattern.compile(
        "\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");
    private static final List<String> ASSUMPTIONS = List.of(
        "base integer",
        "modulus integer",
        "modulus > 0",
        "all exponents integer",
        "all exponents >= 0"
    );

    public enum Verdict {
        GREEN,
        YELLOW,
        RED
    }

    public record Artifact(
        String leftPattern,
        String rightPattern,
        List<String> parameterRelations,
        String contentSha256
    ) {
    }

    public record TestCase(
        String id,
        String program,
        String baseVariable,
        String modulusVariable,
        List<String> requiredPreservedSubtrees,
        boolean positive
    ) {
    }

    public record CaseResult(
        String id,
        boolean positive,
        long originalTreeCost,
        long originalDagCost,
        long selectedTreeCost,
        long selectedDagCost,
        int generatedCandidates,
        int acceptedProofs,
        boolean preservedRequiredOutputs,
        boolean selectedHasSharedComputation,
        String selectedProgram
    ) {
        public boolean improved() {
            return selectedDagCost < originalDagCost;
        }
    }

    public record Report(
        Artifact learnedArtifact,
        GeneralizedPattern shuffledArtifact,
        List<CaseResult> learnedCases,
        List<CaseResult> shuffledCases,
        boolean learnedArtifactMatchesShuffledControl,
        Verdict verdict
    ) {
    }

    private ModPowReuseLearningEvaluation() {
    }

    public static void main(String[] arguments) throws IOException {
        if (arguments.length != 2) {
            throw new IllegalArgumentException(
                "expected learned artifact path and output directory");
        }
        Artifact artifact = readArtifact(Path.of(arguments[0]));
        Report report = evaluate(artifact);
        Path output = Path.of(arguments[1]);
        Files.createDirectories(output);
        Files.writeString(output.resolve("modpow-reuse-learning-result.json"),
            json(report), StandardCharsets.UTF_8);
        Files.writeString(output.resolve("modpow-reuse-learning-result.md"),
            markdown(report), StandardCharsets.UTF_8);
        if (report.verdict() == Verdict.RED) {
            throw new IllegalStateException(
                "held-out learned transfer failed proof or transfer requirements");
        }
    }

    static Artifact readArtifact(Path path) throws IOException {
        String json = Files.readString(path, StandardCharsets.UTF_8);
        String left = jsonString(json, "leftPattern");
        String right = jsonString(json, "rightPattern");
        String digest = jsonString(json, "contentSha256");
        List<String> relations = jsonStringArray(json, "parameterRelations");
        List<String> assumptions = jsonStringArray(json, "assumptions");
        String canonical = String.join("\n",
            ModPowReuseLearningFormation.SCHEMA,
            left,
            right,
            String.join("|", relations),
            String.join("|", assumptions));
        String recomputed = sha256(canonical);
        if (!digest.equals(recomputed)
                || !digest.equals(FROZEN_CONTENT_SHA256)
                || !assumptions.equals(ASSUMPTIONS)) {
            throw new IllegalStateException(
                "learned artifact hash or assumptions changed after freeze");
        }
        return new Artifact(left, right, relations, digest);
    }

    static Report evaluate(Artifact artifact) {
        GeneralizedPattern shuffled = new PatternGeneralizer()
            .generalize(shuffledTrainingPaths())
            .orElseThrow(() -> new IllegalStateException(
                "shuffled control did not form a pattern"));

        var learnedTransport = transport(
            artifact.leftPattern(), artifact.rightPattern(), "learned");
        var shuffledTransport = transport(
            shuffled.leftPattern(), shuffled.rightPattern(), "shuffled");

        List<CaseResult> learnedCases = heldOutCases().stream()
            .map(item -> evaluateCase(item, learnedTransport))
            .toList();
        List<CaseResult> shuffledCases = heldOutCases().stream()
            .map(item -> evaluateCase(item, shuffledTransport))
            .toList();

        boolean primaryTransfer = learnedCases.stream()
            .filter(CaseResult::positive)
            .allMatch(result -> result.improved()
                && result.acceptedProofs() > 0
                && result.preservedRequiredOutputs()
                && result.selectedHasSharedComputation())
            && learnedCases.stream()
                .filter(result -> !result.positive())
                .allMatch(result -> !result.improved());

        boolean artifactsEquivalent =
            artifact.leftPattern().equals(shuffled.leftPattern())
            && artifact.rightPattern().equals(shuffled.rightPattern())
            && artifact.parameterRelations().equals(
                shuffled.parameterRelations());

        boolean shuffledEquivalentAdvantage = pairedPositiveResults(
            learnedCases, shuffledCases).stream()
            .allMatch(pair -> pair.learned().selectedDagCost()
                == pair.shuffled().selectedDagCost()
                && pair.shuffled().improved());

        Verdict verdict = !primaryTransfer
            ? Verdict.RED
            : artifactsEquivalent || shuffledEquivalentAdvantage
                ? Verdict.YELLOW
                : Verdict.GREEN;

        return new Report(
            artifact,
            shuffled,
            learnedCases,
            shuffledCases,
            artifactsEquivalent,
            verdict);
    }

    private record Pair(CaseResult learned, CaseResult shuffled) {
    }

    private static List<Pair> pairedPositiveResults(
        List<CaseResult> learned,
        List<CaseResult> shuffled
    ) {
        var result = new ArrayList<Pair>();
        for (int index = 0; index < learned.size(); index++) {
            if (learned.get(index).positive()) {
                result.add(new Pair(learned.get(index), shuffled.get(index)));
            }
        }
        return result;
    }

    private static AstRewriteTransport transport(
        String left,
        String right,
        String prefix
    ) {
        PatternExpr source = pattern(PARSER.parseTerm(left));
        PatternExpr target = pattern(PARSER.parseTerm(right));
        PatternRewriteRule direct = new PatternRewriteRule(
            prefix + "-learned",
            source,
            target,
            RecognitionProfile.arithmeticAc());
        PatternRewriteRule swapped = new PatternRewriteRule(
            prefix + "-learned-parameter-swap",
            swapIndependentParameters(source),
            swapIndependentParameters(target),
            RecognitionProfile.arithmeticAc());
        return new AstRewriteTransport(
            List.of(direct, swapped), 256, 128);
    }

    private static CaseResult evaluateCase(
        TestCase item,
        AstRewriteTransport transport
    ) {
        Expr source = PARSER.parseTerm(item.program());
        long originalTree = treeCost(source);
        long originalDag = dagCost(source, new HashSet<>());
        Expr selected = source;
        long selectedTree = originalTree;
        long selectedDag = originalDag;
        int accepted = 0;
        List<AstRewriteTransport.Step> generated = transport.generate(source);
        for (AstRewriteTransport.Step step : generated) {
            Expr target = step.target();
            if (!proofAccepts(source, target, item)) {
                continue;
            }
            accepted++;
            long targetDag = dagCost(target, new HashSet<>());
            long targetTree = treeCost(target);
            if (targetDag < selectedDag
                    || targetDag == selectedDag
                        && targetTree < selectedTree) {
                selected = target;
                selectedDag = targetDag;
                selectedTree = targetTree;
            }
        }

        boolean preserved = item.requiredPreservedSubtrees().stream()
            .map(PARSER::parseTerm)
            .allMatch(required -> containsSubtree(selected, required));
        return new CaseResult(
            item.id(),
            item.positive(),
            originalTree,
            originalDag,
            selectedTree,
            selectedDag,
            generated.size(),
            accepted,
            preserved,
            hasDuplicateModPow(selected),
            selected.toString());
    }

    private static boolean proofAccepts(
        Expr source,
        Expr target,
        TestCase item
    ) {
        int left = ModPowDagRediscoveryStudy.compositionDifferenceCount(
            source, target, ModPowDagRediscoveryStudy.RULE_LEFT);
        int right = ModPowDagRediscoveryStudy.compositionDifferenceCount(
            source, target, ModPowDagRediscoveryStudy.RULE_RIGHT);
        return (left == 1 || right == 1)
            && numericDomainSatisfied(source,
                item.baseVariable(), item.modulusVariable())
            && numericDomainSatisfied(target,
                item.baseVariable(), item.modulusVariable());
    }

    private static boolean numericDomainSatisfied(
        Expr expression,
        String baseVariable,
        String modulusVariable
    ) {
        if (expression instanceof FunctionExpr function) {
            if (isModPow(function)) {
                Expr base = function.arguments().get(0);
                Expr exponent = function.arguments().get(1);
                Expr modulus = function.arguments().get(2);
                if (!integerBase(base, baseVariable, modulusVariable)
                        || !nonnegativeIntegerExponent(exponent)
                        || !(modulus instanceof VariableExpr variable)
                        || !variable.name().equals(modulusVariable)) {
                    return false;
                }
            }
            for (Expr argument : function.arguments()) {
                if (!numericDomainSatisfied(
                        argument, baseVariable, modulusVariable)) {
                    return false;
                }
            }
        } else if (expression instanceof BinaryExpr binary) {
            return numericDomainSatisfied(
                    binary.left(), baseVariable, modulusVariable)
                && numericDomainSatisfied(
                    binary.right(), baseVariable, modulusVariable);
        }
        return true;
    }

    private static boolean integerBase(
        Expr expression,
        String baseVariable,
        String modulusVariable
    ) {
        if (expression instanceof VariableExpr variable) {
            return variable.name().equals(baseVariable);
        }
        if (expression instanceof FunctionExpr function && isModPow(function)) {
            return numericDomainSatisfied(
                function, baseVariable, modulusVariable);
        }
        return false;
    }

    private static boolean nonnegativeIntegerExponent(Expr expression) {
        if (expression instanceof NumberExpr number) {
            return number.value().isInteger()
                && number.value().numerator().signum() >= 0;
        }
        if (expression instanceof BinaryExpr binary
                && binary.operator() == de.regelsuche.ast.BinaryOperator.MUL) {
            return nonnegativeIntegerExponent(binary.left())
                && nonnegativeIntegerExponent(binary.right());
        }
        return false;
    }

    private static long treeCost(Expr expression) {
        long own = expression instanceof FunctionExpr function
                && isModPow(function)
            ? exponentWork(function.arguments().get(1))
            : 0;
        long children = 0;
        if (expression instanceof BinaryExpr binary) {
            children = add(treeCost(binary.left()), treeCost(binary.right()));
        } else if (expression instanceof FunctionExpr function) {
            for (Expr argument : function.arguments()) {
                children = add(children, treeCost(argument));
            }
        }
        return add(own, children);
    }

    private static long dagCost(
        Expr expression,
        Set<Expr> computedModPows
    ) {
        if (expression instanceof FunctionExpr function && isModPow(function)) {
            if (!computedModPows.add(function)) {
                return 0;
            }
            long total = exponentWork(function.arguments().get(1));
            for (Expr argument : function.arguments()) {
                total = add(total, dagCost(argument, computedModPows));
            }
            return total;
        }
        long total = 0;
        if (expression instanceof BinaryExpr binary) {
            total = add(dagCost(binary.left(), computedModPows),
                dagCost(binary.right(), computedModPows));
        } else if (expression instanceof FunctionExpr function) {
            for (Expr argument : function.arguments()) {
                total = add(total, dagCost(argument, computedModPows));
            }
        }
        return total;
    }

    private static long exponentWork(Expr exponent) {
        if (exponent instanceof NumberExpr number
                && number.value().isInteger()
                && number.value().numerator().signum() >= 0) {
            BigInteger value = number.value().numerator();
            return Math.max(1, value.bitLength());
        }
        if (exponent instanceof BinaryExpr binary
                && binary.operator() == de.regelsuche.ast.BinaryOperator.MUL) {
            return add(exponentWork(binary.left()),
                exponentWork(binary.right()));
        }
        throw new IllegalArgumentException(
            "held-out exponent outside frozen numeric cost domain: "
                + exponent);
    }

    private static long add(long left, long right) {
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static boolean hasDuplicateModPow(Expr expression) {
        var seen = new HashSet<Expr>();
        return collectDuplicateModPow(expression, seen);
    }

    private static boolean collectDuplicateModPow(
        Expr expression,
        Set<Expr> seen
    ) {
        boolean duplicate = false;
        if (expression instanceof FunctionExpr function) {
            if (isModPow(function) && !seen.add(function)) {
                duplicate = true;
            }
            for (Expr argument : function.arguments()) {
                duplicate |= collectDuplicateModPow(argument, seen);
            }
        } else if (expression instanceof BinaryExpr binary) {
            duplicate |= collectDuplicateModPow(binary.left(), seen);
            duplicate |= collectDuplicateModPow(binary.right(), seen);
        }
        return duplicate;
    }

    private static boolean containsSubtree(Expr expression, Expr required) {
        if (expression.equals(required)) {
            return true;
        }
        if (expression instanceof FunctionExpr function) {
            return function.arguments().stream()
                .anyMatch(argument -> containsSubtree(argument, required));
        }
        if (expression instanceof BinaryExpr binary) {
            return containsSubtree(binary.left(), required)
                || containsSubtree(binary.right(), required);
        }
        return false;
    }

    private static boolean isModPow(FunctionExpr function) {
        return function.name().equals("modpow")
            && function.arguments().size() == 3;
    }

    private static PatternExpr pattern(Expr expression) {
        if (expression instanceof VariableExpr variable) {
            return PatternExpr.var(variable.name());
        }
        if (expression instanceof NumberExpr number) {
            return PatternExpr.num(number.value());
        }
        if (expression instanceof BinaryExpr binary) {
            return PatternExpr.op(
                binary.operator(),
                pattern(binary.left()),
                pattern(binary.right()));
        }
        if (expression instanceof FunctionExpr function) {
            return new PatternExpr.Function(
                function.name(),
                function.arguments().stream()
                    .map(ModPowReuseLearningEvaluation::pattern)
                    .toList());
        }
        throw new IllegalArgumentException(
            "unsupported learned pattern node " + expression);
    }

    private static PatternExpr swapIndependentParameters(
        PatternExpr expression
    ) {
        if (expression instanceof PatternExpr.Placeholder placeholder) {
            return switch (placeholder.name()) {
                case "A" -> PatternExpr.var("A2");
                case "A2" -> PatternExpr.var("A");
                default -> expression;
            };
        }
        if (expression instanceof PatternExpr.Operation operation) {
            return PatternExpr.op(
                operation.operator(),
                swapIndependentParameters(operation.left()),
                swapIndependentParameters(operation.right()));
        }
        if (expression instanceof PatternExpr.Function function) {
            return new PatternExpr.Function(
                function.name(),
                function.arguments().stream()
                    .map(ModPowReuseLearningEvaluation::swapIndependentParameters)
                    .toList());
        }
        return expression;
    }

    private static List<SuccessfulTransformationPath> shuffledTrainingPaths() {
        return List.of(
            path("SHUFFLED_15", "modpow(a,15,n)",
                "modpow(modpow(a,5,n),3,n)"),
            path("SHUFFLED_28", "modpow(b,28,m)",
                "modpow(modpow(b,7,m),4,m)"),
            path("SHUFFLED_66", "modpow(c,66,k)",
                "modpow(modpow(c,11,k),6,k)")
        );
    }

    private static SuccessfulTransformationPath path(
        String id,
        String source,
        String target
    ) {
        return new SuccessfulTransformationPath(
            id,
            source,
            target,
            List.of(source, target),
            List.of("verified-modpow-composition"),
            new ExpressionScore(100, 0, 0, 0, 0),
            new ExpressionScore(90, 0, 0, 0, 0),
            true,
            "independent-modpow-composition-proof",
            Map.of(),
            ASSUMPTIONS);
    }

    static List<TestCase> heldOutCases() {
        return List.of(
            new TestCase(
                "TEST_RENAMED",
                "program(modpow(z,13*7,t),modpow(z,7,t))",
                "z", "t",
                List.of("modpow(z,7,t)"),
                true),
            new TestCase(
                "TEST_SWAPPED_OUTPUTS",
                "program(modpow(y,8,u),modpow(y,17*8,u))",
                "y", "u",
                List.of("modpow(y,8,u)"),
                true),
            new TestCase(
                "TEST_EXTRA_OUTPUT",
                "program(extra(w),modpow(w,19*9,v),modpow(w,9,v))",
                "w", "v",
                List.of("extra(w)", "modpow(w,9,v)"),
                true),
            new TestCase(
                "TEST_FACTOR_ORDER",
                "program(modpow(r,23,s),modpow(r,23*10,s))",
                "r", "s",
                List.of("modpow(r,23,s)"),
                true),
            new TestCase(
                "TEST_NO_REUSE",
                "program(modpow(z,13*7,t),modpow(z,5,t))",
                "z", "t",
                List.of("modpow(z,5,t)"),
                false)
        );
    }

    private static String jsonString(String json, String key) {
        Matcher matcher = JSON_STRING.matcher(json);
        while (matcher.find()) {
            if (matcher.group(1).equals(key)) {
                return matcher.group(2);
            }
        }
        throw new IllegalArgumentException(
            "missing JSON string field " + key);
    }

    private static List<String> jsonStringArray(
        String json,
        String key
    ) {
        Pattern arrayPattern = Pattern.compile(
            "\"" + Pattern.quote(key)
                + "\"\\s*:\\s*\\[(.*?)\\]",
            Pattern.DOTALL);
        Matcher array = arrayPattern.matcher(json);
        if (!array.find()) {
            throw new IllegalArgumentException(
                "missing JSON array field " + key);
        }
        Matcher strings = Pattern.compile("\"([^\"]*)\"")
            .matcher(array.group(1));
        var result = new ArrayList<String>();
        while (strings.find()) {
            result.add(strings.group(1));
        }
        return List.copyOf(result);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                "SHA-256 unavailable", exception);
        }
    }

    private static String json(Report report) {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"schema\": \"regelsuche.modpow-reuse-learning-result/v1\",\n");
        out.append("  \"verdict\": \"").append(report.verdict()).append("\",\n");
        out.append("  \"learnedContentSha256\": \"")
            .append(report.learnedArtifact().contentSha256()).append("\",\n");
        out.append("  \"shuffledArtifactEqualsLearned\": ")
            .append(report.learnedArtifactMatchesShuffledControl()).append(",\n");
        appendCases(out, "learned", report.learnedCases(), true);
        appendCases(out, "shuffled", report.shuffledCases(), false);
        out.append("}\n");
        return out.toString();
    }

    private static void appendCases(
        StringBuilder out,
        String name,
        List<CaseResult> cases,
        boolean comma
    ) {
        out.append("  \"").append(name).append("\": [\n");
        for (int index = 0; index < cases.size(); index++) {
            CaseResult result = cases.get(index);
            out.append("    {\"id\":\"").append(result.id())
                .append("\",\"positive\":").append(result.positive())
                .append(",\"originalTree\":").append(result.originalTreeCost())
                .append(",\"originalDag\":").append(result.originalDagCost())
                .append(",\"selectedTree\":").append(result.selectedTreeCost())
                .append(",\"selectedDag\":").append(result.selectedDagCost())
                .append(",\"generated\":").append(result.generatedCandidates())
                .append(",\"acceptedProofs\":").append(result.acceptedProofs())
                .append(",\"preserved\":").append(result.preservedRequiredOutputs())
                .append(",\"shared\":").append(result.selectedHasSharedComputation())
                .append("}");
            if (index + 1 < cases.size()) {
                out.append(',');
            }
            out.append('\n');
        }
        out.append("  ]");
        if (comma) {
            out.append(',');
        }
        out.append('\n');
    }

    private static String markdown(Report report) {
        StringBuilder out = new StringBuilder();
        out.append("# Held-out modular-power learned transfer\n\n");
        out.append("Verdict: **").append(report.verdict()).append("**.\n\n");
        out.append("- learned content SHA-256: ")
            .append(report.learnedArtifact().contentSha256()).append('\n');
        out.append("- shuffled artifact equals learned artifact: ")
            .append(report.learnedArtifactMatchesShuffledControl()).append("\n\n");
        out.append("| arm | case | DAG before | DAG after | proof | preserved | shared |\n");
        out.append("|---|---|---:|---:|---:|---|---|\n");
        for (CaseResult item : report.learnedCases()) {
            row(out, "LEARNED", item);
        }
        for (CaseResult item : report.shuffledCases()) {
            row(out, "SHUFFLED", item);
        }
        if (report.verdict() == Verdict.YELLOW) {
            out.append("\nThe frozen learned rule transfers correctly, but the SHUFFLED control produces the same generalized local rewrite. This means the current learner generalized the valid composition theorem, not the surrounding computation-reuse preference. A context/DAG-aware learned macro is required for a GREEN learning claim.\n");
        }
        return out.toString();
    }

    private static void row(
        StringBuilder out,
        String arm,
        CaseResult item
    ) {
        out.append("| ").append(arm)
            .append(" | ").append(item.id())
            .append(" | ").append(item.originalDagCost())
            .append(" | ").append(item.selectedDagCost())
            .append(" | ").append(item.acceptedProofs())
            .append(" | ").append(item.preservedRequiredOutputs())
            .append(" | ").append(item.selectedHasSharedComputation())
            .append(" |\n");
    }
}
