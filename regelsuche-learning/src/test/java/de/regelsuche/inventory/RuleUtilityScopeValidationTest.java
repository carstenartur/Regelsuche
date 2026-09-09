package de.regelsuche.inventory;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RuleUtilityScopeValidationTest {
    @Test void everyRequiredReferenceFieldRejectsNullAndBlankExplicitly() {
        for (int field = 0; field < 6; field++) for (String invalid : new String[]{null, " "}) {
            var values = new String[]{"inventory", "source", "target", "relation", "{}", "assessment"}; values[field] = invalid;
            var error = assertThrows(IllegalArgumentException.class, () -> new RuleUtilityEvidence.ReferenceScope(
                values[0], values[1], values[2], values[3], values[4], values[5], true, 1));
            assertEquals("reference scope must be explicit", error.getMessage());
        }
    }
}
