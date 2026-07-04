package de.regelsuche.validation;

import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;

/** Infrastructure-free discovery oracle adapter with explicit tri-state status. */
public final class SymPyDiscoveryOracleAdapter {
    private static final String CAS_UNAVAILABLE = "python/sympy runtime unavailable";

    private final ExpressionParser parser = new ExpressionParser();

    public OracleResult equivalence(String leftExpression, String rightExpression) {
        if (!canParse(leftExpression) || !canParse(rightExpression)) {
            return OracleResult.unavailable("oracle input could not be parsed");
        }
        return OracleResult.unavailable(CAS_UNAVAILABLE);
    }

    public OracleResult factorCandidate(String expression, String candidateExpression) {
        if (!canParse(expression) || !canParse(candidateExpression)) {
            return OracleResult.unavailable("factor candidate input could not be parsed");
        }
        return OracleResult.unavailable(CAS_UNAVAILABLE);
    }

    public OracleResult groebnerEquivalence(List<String> generators, String polynomialExpression) {
        if (generators == null || generators.isEmpty()) {
            return OracleResult.unavailable("no generators provided");
        }
        if (!canParse(polynomialExpression)) {
            return OracleResult.unavailable("polynomial input could not be parsed");
        }
        for (String generator : generators) {
            if (!canParse(generator)) {
                return OracleResult.unavailable("generator input could not be parsed");
            }
        }
        return OracleResult.unavailable(CAS_UNAVAILABLE);
    }

    private boolean canParse(String expression) {
        try {
            ExpressionFormatter.format(parser.parseTerm(expression));
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public enum Status {
        AGREE,
        DISAGREE,
        UNAVAILABLE
    }

    public record OracleResult(Status status, String evidence) {
        public OracleResult {
            status = status == null ? Status.UNAVAILABLE : status;
            evidence = evidence == null ? "" : evidence;
        }

        public static OracleResult agree(String evidence) {
            return new OracleResult(Status.AGREE, evidence);
        }

        public static OracleResult disagree(String evidence) {
            return new OracleResult(Status.DISAGREE, evidence);
        }

        public static OracleResult unavailable(String evidence) {
            return new OracleResult(Status.UNAVAILABLE, evidence);
        }
    }
}
