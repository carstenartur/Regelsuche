package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class StateValueValidationTest {
    @Test void malformedCapabilityMapsFailAtTheAssessmentBoundary() {
        assertThrows(IllegalArgumentException.class, () -> new StateValue.Assessment(1, 0, 0, 0, null));
        var invalid = new HashMap<String, StateValue.Capability>(); invalid.put("capability", null);
        assertThrows(IllegalArgumentException.class, () -> new StateValue.Assessment(1, 0, 0, 0, invalid));
        invalid.clear(); invalid.put(null, new StateValue.Capability("rule", "a", "root", "a", "b"));
        assertThrows(IllegalArgumentException.class, () -> new StateValue.Assessment(1, 0, 0, 0, invalid));
    }
}
