package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.calculus.CalculusDerivativeRules;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PluginAwareDerivativeDomainTest {
    @ParameterizedTest
    @CsvSource({
        "'q + diff(ln(y), y)',q + 1 / y,y > 0",
        "'diff(log(y), y) + q',1 / (y * ln(10)) + q,y > 0",
        "'sin(diff(ln(y), y))',sin(1 / y),y > 0",
        "'q + diff(y^-2, y)',q + (-2) * y ^ (-3),y != 0"
    })
    void pluginEngineRetainsGuardsFromTheActualMatchedOccurrence(
        String source, String target, String guard
    ) {
        var engine = new PluginAwareAstRewriteTransformationEngine(
            CalculusDerivativeRules.rules(), new AstVisitorRegistry());
        var transformations = engine.transform(source);

        assertEquals(1, transformations.size());
        var transformation = transformations.getFirst();
        assertEquals(target, transformation.transformedExpression());
        assertEquals(List.of(guard), transformation.assumptions());
        assertTrue(transformation.equivalencePreservingByConstruction());
    }
}
