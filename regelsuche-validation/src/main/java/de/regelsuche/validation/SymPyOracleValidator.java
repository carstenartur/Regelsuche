package de.regelsuche.validation;

import de.regelsuche.equivalence.EquivalenceService;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;

public class SymPyOracleValidator implements OracleValidator {
    private final ExpressionParser parser = new ExpressionParser();
    private final EquivalenceService equivalenceService;

    public SymPyOracleValidator() {
        this(new SymPyEquivalenceService());
    }

    public SymPyOracleValidator(EquivalenceService equivalenceService) {
        this.equivalenceService = equivalenceService == null ? new SymPyEquivalenceService() : equivalenceService;
    }

    @Override
    public OracleValidation validateEquivalence(String leftExpression, String rightExpression) {
        try {
            ExpressionFormatter.format(parser.parseTerm(leftExpression));
            ExpressionFormatter.format(parser.parseTerm(rightExpression));
        } catch (IllegalArgumentException ex) {
            return OracleValidation.unavailable("oracle input could not be parsed");
        }
        boolean equivalent = equivalenceService.areEquivalent(leftExpression, rightExpression);
        String evidence = equivalenceService.evidence(leftExpression, rightExpression);
        return equivalent ? OracleValidation.agrees(evidence) : OracleValidation.disagrees(evidence);
    }
}
