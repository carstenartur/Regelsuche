package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Source;
import de.regelsuche.search.program.RewriteProgram;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.AstRewriteTransformationEngines;
import de.regelsuche.transform.PreparedAstRewriteTransformationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvolutionRewriteProgramCompilerBackendTest {
    @Test
    void defaultCompilerUsesPreparedBackendAndReferenceRemainsSelectable() {
        EvolutionGenome genome = EvolutionGenomeTestFixtures.genome(
            EvolutionGenomeTestFixtures.addZero("add_zero", "A")
        );
        EvolutionRewriteProgramPlan plan = EvolutionRewriteProgramPlan.create(
            genome,
            new Source("source", List.of("add_zero")),
            2,
            2
        );

        var prepared = new EvolutionRewriteProgramCompiler().compile(genome, plan);
        var reference = new EvolutionRewriteProgramCompiler(
            new EvolutionGenomeCompiler(),
            AstRewriteTransformationEngines.Backend.REFERENCE
        ).compile(genome, plan);

        RewriteProgram.Source preparedSource = assertInstanceOf(
            RewriteProgram.Source.class,
            prepared.program()
        );
        RewriteProgram.Source referenceSource = assertInstanceOf(
            RewriteProgram.Source.class,
            reference.program()
        );
        assertInstanceOf(
            PreparedAstRewriteTransformationEngine.class,
            preparedSource.engine()
        );
        assertInstanceOf(
            AstRewriteTransformationEngine.class,
            referenceSource.engine()
        );
        assertEquals(
            reference.engine().transform("x + 0"),
            prepared.engine().transform("x + 0")
        );
    }
}
