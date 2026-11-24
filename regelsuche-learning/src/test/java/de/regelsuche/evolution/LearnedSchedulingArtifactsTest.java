package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LearnedSchedulingArtifactsTest {
    record Evidence(String source, Set<Integer> costs) { }

    @Test
    void preservesCanonicalBytesForNestedEvidence() {
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("z", null);
        evidence.put("source", "line\n\"quote\"\\path\t");
        evidence.put("numbers", List.of(new BigInteger("9007199254740993"), new BigDecimal("0.1250")));
        evidence.put("items", Set.of(new Evidence("b", Set.of(2, 10)), new Evidence("a", Set.of(10, 2))));

        assertEquals("{\"items\":[{\"costs\":[10,2],\"source\":\"a\"},{\"costs\":[10,2],\"source\":\"b\"}],"
            + "\"numbers\":[9007199254740993,0.1250],\"source\":\"line\\n\\\"quote\\\"\\\\path\\t\",\"z\":null}",
            LearnedSchedulingArtifacts.json(evidence));
    }
}
