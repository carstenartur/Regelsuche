package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityExternalAdmissionTest {
    @Test
    void frozenSourceOnlyRowsRetainAllClosedPrefixesAndFirstGenuineEngineAuthority() {
        var rows = PolynomialTheoryUtilityExternalAdmission.inspect();
        assertEquals(120, rows.size());
        assertEquals(120, rows.stream().map(row -> row.input().inputId()).distinct().count());
        var admitted = rows.stream().filter(row -> row.firstRequest().isPresent()).toList();
        assertEquals(19, admitted.size());
        assertEquals(List.of(20L,24L,30L,46L,57L,58L,61L,62L,62L,73L,78L,104L,142L,
                190L,190L,190L,220L,286L,382L),
            admitted.stream().map(row -> row.firstRequest().orElseThrow().maxWorkUnits()).sorted().toList());
        assertTrue(admitted.stream().allMatch(row -> !row.pendingPath().isEmpty() || row.completed().isEmpty()));
        assertEquals(2, rows.stream().filter(row -> row.firstRequest().isEmpty())
            .filter(row -> row.completed().stream().allMatch(value -> value.status().equals("UNSUPPORTED"))).count());
        var perCheckpoint = admitted.stream().collect(Collectors.groupingBy(row -> row.input().checkpointId(), Collectors.counting()));
        assertEquals(10, perCheckpoint.get("CP06_FULL"));
    }
}
