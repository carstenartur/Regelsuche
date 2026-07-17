package de.regelsuche.solver.portfolio;

import de.regelsuche.solver.ir.SolverIr.Binary;
import de.regelsuche.solver.ir.SolverIr.BinaryOperator;
import de.regelsuche.solver.ir.SolverIr.Call;
import de.regelsuche.solver.ir.SolverIr.Expression;
import de.regelsuche.solver.ir.SolverIr.Literal;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.Predicate;
import de.regelsuche.solver.ir.SolverIr.Relation;
import de.regelsuche.solver.ir.SolverIr.Sort;
import de.regelsuche.solver.ir.SolverIr.Symbol;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Lossless SMT-LIB 2 rendering for the bounded arithmetic fragment declared by Z3. */
final class SmtLibRenderer {

    Material render(Obligation obligation) {
        List<String> issues = new ArrayList<>();
        addDivisionDomainIssues(obligation, issues);
        Map<String, String> mapping = new LinkedHashMap<>();
        StringBuilder script = new StringBuilder("(set-logic ALL)\n");
        obligation.declarations().forEach(declaration -> {
            if (declaration.sort() != Sort.REAL) {
                issues.add("UNSUPPORTED_SORT:" + declaration.name()
                    + ':' + declaration.sort().name());
            } else {
                script.append("(declare-const ").append(declaration.name())
                    .append(" Real)\n");
            }
        });
        for (Predicate assumption : obligation.assumptions()) {
            String rendered = relation(
                assumption.relation(), assumption.left(), assumption.right(), issues);
            mapping.put("assumption." + assumption.id(), rendered);
            script.append("(assert ").append(rendered).append(")\n");
        }
        String left = expression(obligation.goal().left(), issues);
        String right = expression(obligation.goal().right(), issues);
        mapping.put("goal.left", left);
        mapping.put("goal.right", right);
        String goal = relation(obligation.goal().relation(),
            obligation.goal().left(), obligation.goal().right(), issues);
        mapping.put("goal", goal);
        script.append("(assert (not ").append(goal).append("))\n");
        return new Material(
            script.toString(), Map.copyOf(mapping),
            issues.stream().distinct().sorted().toList());
    }

    private static void addDivisionDomainIssues(
        Obligation obligation,
        List<String> issues
    ) {
        List<Expression> denominators = new ArrayList<>();
        collectDenominators(obligation.goal().left(), denominators);
        collectDenominators(obligation.goal().right(), denominators);
        for (Predicate assumption : obligation.assumptions()) {
            collectDenominators(assumption.left(), denominators);
            if (assumption.right() != null) {
                collectDenominators(assumption.right(), denominators);
            }
        }
        Set<String> checked = new LinkedHashSet<>();
        for (Expression denominator : denominators) {
            String material = denominator.canonicalMaterial();
            if (!checked.add(material)) {
                continue;
            }
            if (denominator instanceof Literal literal) {
                try {
                    if (new BigDecimal(literal.value()).signum() == 0) {
                        issues.add("DIVISION_BY_ZERO_LITERAL");
                    }
                } catch (NumberFormatException exception) {
                    issues.add("UNSUPPORTED_DIVISOR_LITERAL:" + literal.value());
                }
                continue;
            }
            boolean encoded = obligation.assumptions().stream()
                .anyMatch(assumption -> excludesZero(assumption, denominator));
            if (!encoded) {
                issues.add("DIVISION_DOMAIN_NOT_ENCODED:" + material);
            }
        }
    }

    private static void collectDenominators(
        Expression expression,
        List<Expression> denominators
    ) {
        if (expression instanceof Binary binary) {
            if (binary.operator() == BinaryOperator.DIVIDE) {
                denominators.add(binary.right());
            }
            collectDenominators(binary.left(), denominators);
            collectDenominators(binary.right(), denominators);
        } else if (expression instanceof Call call) {
            call.arguments().forEach(argument ->
                collectDenominators(argument, denominators));
        }
    }

    private static boolean excludesZero(
        Predicate assumption,
        Expression denominator
    ) {
        if (assumption.right() == null) {
            return false;
        }
        boolean denominatorOnLeft = same(assumption.left(), denominator);
        boolean denominatorOnRight = same(assumption.right(), denominator);
        boolean zeroOnLeft = zeroLiteral(assumption.left());
        boolean zeroOnRight = zeroLiteral(assumption.right());
        return switch (assumption.relation()) {
            case NOT_EQUALS -> denominatorOnLeft && zeroOnRight
                || denominatorOnRight && zeroOnLeft;
            case GREATER_THAN, LESS_THAN -> denominatorOnLeft && zeroOnRight
                || denominatorOnRight && zeroOnLeft;
            case EQUALS, LESS_OR_EQUAL, GREATER_OR_EQUAL, IS_INTEGER -> false;
        };
    }

    private static boolean same(Expression first, Expression second) {
        return first.canonicalMaterial().equals(second.canonicalMaterial());
    }

    private static boolean zeroLiteral(Expression expression) {
        if (!(expression instanceof Literal literal)) {
            return false;
        }
        try {
            return new BigDecimal(literal.value()).signum() == 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static String relation(
        Relation relation,
        Expression left,
        Expression right,
        List<String> issues
    ) {
        String leftTerm = expression(left, issues);
        String rightTerm = right == null ? "" : expression(right, issues);
        return switch (relation) {
            case EQUALS -> "(= " + leftTerm + ' ' + rightTerm + ')';
            case NOT_EQUALS -> "(not (= " + leftTerm + ' ' + rightTerm + "))";
            case LESS_THAN -> "(< " + leftTerm + ' ' + rightTerm + ')';
            case LESS_OR_EQUAL -> "(<= " + leftTerm + ' ' + rightTerm + ')';
            case GREATER_THAN -> "(> " + leftTerm + ' ' + rightTerm + ')';
            case GREATER_OR_EQUAL -> "(>= " + leftTerm + ' ' + rightTerm + ')';
            case IS_INTEGER -> "(is_int " + leftTerm + ')';
        };
    }

    private static String expression(Expression expression, List<String> issues) {
        if (expression instanceof Literal literal) {
            return literal.value();
        }
        if (expression instanceof Symbol symbol) {
            return symbol.name();
        }
        if (expression instanceof Call call) {
            issues.add("UNSUPPORTED_CALL:" + call.function());
            return "unsupported_call";
        }
        Binary binary = (Binary) expression;
        String left = expression(binary.left(), issues);
        String right = expression(binary.right(), issues);
        return switch (binary.operator()) {
            case ADD -> "(+ " + left + ' ' + right + ')';
            case SUBTRACT -> "(- " + left + ' ' + right + ')';
            case MULTIPLY -> "(* " + left + ' ' + right + ')';
            case DIVIDE -> "(/ " + left + ' ' + right + ')';
            case POWER -> power(binary, left, right, issues);
        };
    }

    private static String power(
        Binary binary,
        String left,
        String right,
        List<String> issues
    ) {
        if (!(binary.right() instanceof Literal literal)) {
            issues.add("UNSUPPORTED_POWER_EXPONENT:NON_LITERAL");
            return "(^ " + left + ' ' + right + ')';
        }
        try {
            BigDecimal exponent = new BigDecimal(literal.value());
            if (exponent.scale() > 0 || exponent.signum() < 0) {
                issues.add("UNSUPPORTED_POWER_EXPONENT:" + literal.value());
            }
        } catch (NumberFormatException exception) {
            issues.add("UNSUPPORTED_POWER_EXPONENT:" + literal.value());
        }
        return "(^ " + left + ' ' + right + ')';
    }

    record Material(
        String scriptPrefix,
        Map<String, String> termMapping,
        List<String> issues
    ) {
    }
}
