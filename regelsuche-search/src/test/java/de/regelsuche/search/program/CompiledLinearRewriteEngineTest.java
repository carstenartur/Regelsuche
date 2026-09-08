package de.regelsuche.search.program;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.PreparedAstRewriteTransformationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompiledLinearRewriteEngineTest {
    private static RewriteProgram program() {
        var rules = AstRewriteTransformationEngine.defaultRules();
        var zero = rules.stream().filter(rule -> rule.id().equals("ast_add_zero_right")).toList();
        var one = rules.stream().filter(rule -> rule.id().equals("ast_multiply_one_right")).toList();
        return RewritePrograms.sequence("linear", RewritePrograms.source("zero", new PreparedAstRewriteTransformationEngine(zero)),
            RewritePrograms.source("one", new PreparedAstRewriteTransformationEngine(one)));
    }

    @Test
    void compiledPipelinePreservesEveryInterpretedPrimitivePath() {
        for (var program : List.of(program(), ((RewriteProgram.Sequence) program()).steps().getFirst())) {
            var interpreted = new ProgrammedTransformationEngine(program);
            var compiled = new CompiledLinearRewriteEngine(program, 32);
            for (var expression : List.of("(x+0)*1+(y+0)*1", " (x+0)*1 ", "x+0", "x")) {
                var reference = interpreted.transformMeasured(expression);
                var actual = compiled.transformMeasured(expression);
                assertEquals(reference.transformations(), actual.transformations());
                assertEquals(reference.workMetrics().sourceInvocations(), actual.workMetrics().sourceInvocations());
                assertEquals(reference.workMetrics().sourceCandidates(), actual.workMetrics().sourceCandidates());
                assertEquals(reference.workMetrics().composedCandidates(), actual.workMetrics().composedCandidates());
                assertEquals(reference.workMetrics().totalWorkUnits() - reference.workMetrics().programNodeVisits(),
                    actual.workMetrics().totalWorkUnits());
                assertEquals(0, actual.workMetrics().programNodeVisits());
            }
        }
    }

    @Test
    void unsupportedTopologyAndOversizedIntermediateBatchesFailExplicitly() {
        assertThrows(IllegalArgumentException.class, () -> new CompiledLinearRewriteEngine(
            RewritePrograms.choice("choice", program(), program()), 32));
        assertThrows(IllegalArgumentException.class, () -> new CompiledLinearRewriteEngine(program(), 1)
            .transformMeasured("(x+0)*1+(y+0)*1"));
        assertThrows(IllegalArgumentException.class, () -> new CompiledLinearRewriteEngine(program(), 32).transformMeasured(" "));
        assertThrows(IllegalArgumentException.class, () -> new CompiledLinearRewriteEngine(RewritePrograms.source("opaque",
            de.regelsuche.transform.MeasuredTransformationEngines.counting(new AstRewriteTransformationEngine())), 32));
    }
}
