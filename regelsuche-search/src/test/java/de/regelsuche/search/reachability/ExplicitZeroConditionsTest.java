package de.regelsuche.search.reachability;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.assumption.AssumptionSignature;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExplicitZeroConditionsTest {
    @Test void recognizesBothOrientationsAndStructurallyIdenticalCompoundTerms() {
        assertTrue(conflict("0.0=(x + 1)", "x+1 != 0"));
        assertTrue(conflict("(x + 1)=0", "0 != x+1"));
        assertTrue(conflict("0 != 0"));
    }

    @Test void preservesConditionalInformationAndOpaquePredicates() {
        assertFalse(conflict("a >= 0", "a != 0"));
        assertFalse(conflict("x != 0", "y = 0"));
        assertFalse(conflict("opaque@predicate = 0", "x != 0"));
    }

    private static boolean conflict(String... terms) {
        return ExplicitZeroConditions.conflict(AssumptionSignature.ofExpressions(List.of(terms)));
    }
}
