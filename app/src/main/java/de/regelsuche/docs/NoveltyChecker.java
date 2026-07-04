package de.regelsuche.docs;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies discovery candidates against already seen input/target pairs, alpha-equivalent
 * variants and explicitly known rules. The primary comparison path is AST-based: expressions
 * are parsed, canonicalized and serialized into structural keys. A lexical fallback exists only
 * for non-parseable expressions so reporting remains robust.
 */
final class NoveltyChecker {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final Set<String> knownRuleIds;
    private final List<Candidate> knownCandidates;

    NoveltyChecker() {
        this(Set.of(), List.of());
    }

    NoveltyChecker(Set<String> knownRuleIds, List<Candidate> knownCandidates) {
        this.knownRuleIds = normalizeSet(knownRuleIds);
        this.knownCandidates = knownCandidates == null ? List.of() : List.copyOf(knownCandidates);
    }

    List<NoveltyResult> classifyAll(List<Candidate> candidates) {
        List<Candidate> seen = new ArrayList<>(knownCandidates);
        List<NoveltyResult> results = new ArrayList<>();
        for (Candidate candidate : candidates == null ? List.<Candidate>of() : candidates) {
            NoveltyResult result = classify(candidate, seen);
            results.add(result);
            if (candidate != null && result.status() != NoveltyStatus.UNKNOWN) {
                seen.add(candidate);
            }
        }
        return List.copyOf(results);
    }

    NoveltyResult classify(Candidate candidate, List<Candidate> previousCandidates) {
        if (candidate == null || candidate.inputExpression().isBlank() || candidate.targetExpression().isBlank()) {
            return new NoveltyResult(NoveltyStatus.UNKNOWN, "", "missing input or target expression");
        }
        if (usesKnownRule(candidate)) {
            return new NoveltyResult(NoveltyStatus.KNOWN_RULE, "", "candidate path already uses a known rule id");
        }

        List<Candidate> previous = previousCandidates == null ? List.of() : previousCandidates;
        for (Candidate previousCandidate : previous) {
            if (previousCandidate == null) {
                continue;
            }
            if (exactPairKey(candidate).equals(exactPairKey(previousCandidate))) {
                return new NoveltyResult(
                    NoveltyStatus.DUPLICATE,
                    previousCandidate.id(),
                    "same AST-normalized input/target pair"
                );
            }
        }
        for (Candidate previousCandidate : previous) {
            if (previousCandidate == null) {
                continue;
            }
            if (alphaPairKey(candidate).equals(alphaPairKey(previousCandidate))) {
                return new NoveltyResult(
                    NoveltyStatus.ALPHA_EQUIVALENT,
                    previousCandidate.id(),
                    "same alpha-equivalent AST input/target pattern"
                );
            }
        }
        for (Candidate previousCandidate : previous) {
            if (previousCandidate == null) {
                continue;
            }
            if (sameFamilyAndOperator(candidate, previousCandidate)) {
                return new NoveltyResult(
                    NoveltyStatus.VARIANT,
                    previousCandidate.id(),
                    "same family/operator with a different structural pattern"
                );
            }
        }
        return new NoveltyResult(NoveltyStatus.NEW, "", "new family/operator pattern");
    }

    private boolean usesKnownRule(Candidate candidate) {
        if (knownRuleIds.isEmpty()) {
            return false;
        }
        for (String ruleId : candidate.rulePath()) {
            if (knownRuleIds.contains(normalizeToken(ruleId))) {
                return true;
            }
        }
        return false;
    }

    private boolean sameFamilyAndOperator(Candidate left, Candidate right) {
        return !left.family().isBlank()
            && left.family().equals(right.family())
            && !left.operatorId().isBlank()
            && left.operatorId().equals(right.operatorId());
    }

    private String exactPairKey(Candidate candidate) {
        return exactExpressionKey(candidate.inputExpression())
            + "->"
            + exactExpressionKey(candidate.targetExpression());
    }

    private String alphaPairKey(Candidate candidate) {
        Map<String, String> variableMap = new LinkedHashMap<>();
        return alphaExpressionKey(candidate.inputExpression(), variableMap)
            + "->"
            + alphaExpressionKey(candidate.targetExpression(), variableMap);
    }

    private String exactExpressionKey(String expression) {
        Expr parsed = parseCanonical(expression);
        if (parsed == null) {
            return "raw:" + normalizeExpression(expression);
        }
        return astKey(parsed, null);
    }

    private String alphaExpressionKey(String expression, Map<String, String> variableMap) {
        Expr parsed = parseCanonical(expression);
        if (parsed == null) {
            return "raw:" + alphaNormalizeLexically(expression, variableMap);
        }
        return astKey(parsed, variableMap);
    }

    private Expr parseCanonical(String expression) {
        try {
            String canonical = canonicalizer.canonicalize(expression);
            return parser.parse(new InputRequest(InputType.TERM, canonical)).terms().getFirst();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String astKey(Expr expression, Map<String, String> variableMap) {
        if (expression instanceof BinaryExpr binaryExpr) {
            return "bin(" + binaryExpr.operator().name()
                + "," + astKey(binaryExpr.left(), variableMap)
                + "," + astKey(binaryExpr.right(), variableMap)
                + ")";
        }
        if (expression instanceof FunctionExpr functionExpr) {
            List<String> argumentKeys = functionExpr.arguments().stream()
                .map(argument -> astKey(argument, variableMap))
                .toList();
            return "fn(" + functionExpr.name().toLowerCase(Locale.ROOT)
                + "," + String.join(",", argumentKeys)
                + ")";
        }
        if (expression instanceof VariableExpr variableExpr) {
            String variable = variableExpr.name();
            if (variableMap != null) {
                variable = variableMap.computeIfAbsent(variable, ignored -> "v" + variableMap.size());
            }
            return "var(" + variable + ")";
        }
        if (expression instanceof NumberExpr numberExpr) {
            return "num(" + formatNumber(numberExpr.value()) + ")";
        }
        return "unknown(" + expression + ")";
    }

    private static String formatNumber(double value) {
        if (Double.isFinite(value) && Math.rint(value) == value) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private static String normalizeExpression(String expression) {
        return expression == null ? "" : expression.replaceAll("\\s+", "");
    }

    private static String alphaNormalizeLexically(String expression, Map<String, String> variableMap) {
        String normalized = normalizeExpression(expression);
        Matcher matcher = IDENTIFIER.matcher(normalized);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String identifier = matcher.group();
            String replacement = isFunctionCall(normalized, matcher.end())
                ? identifier.toLowerCase(Locale.ROOT)
                : variableMap.computeIfAbsent(identifier, ignored -> "v" + variableMap.size());
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static boolean isFunctionCall(String expression, int endIndex) {
        return endIndex < expression.length() && expression.charAt(endIndex) == '(';
    }

    private static Set<String> normalizeSet(Set<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values == null ? Set.<String>of() : values) {
            String token = normalizeToken(value);
            if (!token.isBlank()) {
                normalized.add(token);
            }
        }
        return Set.copyOf(normalized);
    }

    private static String normalizeToken(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    record Candidate(
        String id,
        String family,
        String inputExpression,
        String targetExpression,
        String operatorId,
        List<String> rulePath
    ) {
        Candidate {
            id = id == null ? "" : id;
            family = family == null ? "" : family;
            inputExpression = inputExpression == null ? "" : inputExpression;
            targetExpression = targetExpression == null ? "" : targetExpression;
            operatorId = operatorId == null ? "" : operatorId;
            rulePath = rulePath == null ? List.of() : List.copyOf(rulePath);
        }
    }

    record NoveltyResult(NoveltyStatus status, String matchedCandidateId, String reason) {
        NoveltyResult {
            status = status == null ? NoveltyStatus.UNKNOWN : status;
            matchedCandidateId = matchedCandidateId == null ? "" : matchedCandidateId;
            reason = reason == null ? "" : reason;
        }
    }
}
