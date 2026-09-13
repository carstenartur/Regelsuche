package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PluginDistributionCanonicalJsonTest {
    @Test
    void canonicalEvidenceSortsNestedMapAndRecordFieldsIndependentlyOfInsertionOrder() {
        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("z", new Fields(1, 2));
        reversed.put("a", 1);
        assertEquals("{\"a\":1,\"z\":{\"a\":2,\"b\":1}}\n", PluginDistributionJson.canonical(reversed));
    }

    record Fields(int b, int a) { }
}
