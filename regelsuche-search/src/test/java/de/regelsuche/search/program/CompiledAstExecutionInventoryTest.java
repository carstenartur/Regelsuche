package de.regelsuche.search.program;

import de.regelsuche.transform.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompiledAstExecutionInventoryTest {
    @Test void everyOwnedStageMustHaveKnownNestedRules() {
        var exact = source("exact", RecognitionProfile.exact());
        var unsupported = source("ac", RecognitionProfile.arithmeticAc());
        assertTrue(new CompiledAstRewriteProgram("p", List.of(exact), 32).hasBoundedExecutionInventory());
        assertFalse(new CompiledAstRewriteProgram("p", List.of(exact, unsupported), 32).hasBoundedExecutionInventory());
        assertFalse(new CompiledAstRewriteProgram("p", List.of(unsupported, exact), 32).hasBoundedExecutionInventory());
    }
    private static RewriteProgram.Source source(String name, RecognitionProfile profile) {
        var rule = new PatternRewriteRule(name, PatternExpr.var("A"), PatternExpr.var("A"), profile);
        return new RewriteProgram.Source(RewriteProgram.NodeMetadata.named(name),
            new PreparedAstRewriteTransformationEngine(List.of(rule), 32, 32));
    }
}
