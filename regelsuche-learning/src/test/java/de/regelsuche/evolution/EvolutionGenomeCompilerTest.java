package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.Expr;
import de.regelsuche.evolution.EvolutionGenome.AssumptionTemplate;
import de.regelsuche.evolution.EvolutionGenome.RewriteGene;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.RewriteKind;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvolutionGenomeCompilerTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void compilesAcceptedGenomeIntoExecutableRule() {
        EvolutionGenome genome = EvolutionGenomeTestFixtures.genome(
            EvolutionGenomeTestFixtures.addZero("add_zero", "A"));

        var program = new EvolutionGenomeCompiler().compile(genome);
        var rule = program.rules().getFirst();
        Expr input = parser.parseTerm("x + 0");

        assertTrue(rule.matches(input));
        assertEquals("x", ExpressionFormatter.format(rule.apply(input)));
        assertFalse(rule.isEquivalencePreservingByConstruction(),
            "an evolved candidate must not claim proof by construction");
        assertEquals(genome.contentHash(), program.genomeHash());
    }

    @Test
    void instantiatesAssumptionTemplatesFromConcreteBindings() {
        RewriteGene divide = new RewriteGene(
            "division_reassociation",
            "?A/?B",
            "?A*(1/?B)",
            RewriteKind.NORMALIZE,
            false,
            1,
            2,
            3,
            List.of(new AssumptionTemplate(
                Assumption.Kind.NON_ZERO,
                "?B != 0",
                List.of("?B"))),
            EvolutionGenomeTestFixtures.obligations());
        EvolutionGenome genome = EvolutionGenomeTestFixtures.genome(divide);

        var rule = new EvolutionGenomeCompiler().compile(genome).rules().getFirst();
        var assumptions = rule.assumptions(parser.parseTerm("x / y"));

        assertEquals(1, assumptions.size());
        assertEquals(Assumption.Kind.NON_ZERO, assumptions.getFirst().kind());
        assertEquals("y != 0", assumptions.getFirst().expression());
        assertEquals(List.of("y"), assumptions.getFirst().symbols());
    }

    @Test
    void refusesCompilationWhenPreflightRejectsGenome() {
        EvolutionGenome genome = EvolutionGenomeTestFixtures.genome(
            EvolutionGenomeTestFixtures.gene("unsafe_target", "?A", "?B"));

        assertThrows(IllegalArgumentException.class,
            () -> new EvolutionGenomeCompiler().compile(genome));
    }
}
