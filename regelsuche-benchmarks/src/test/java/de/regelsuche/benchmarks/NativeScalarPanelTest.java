package de.regelsuche.benchmarks;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.egraph.proof.ScalarCertificateCodec;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class NativeScalarPanelTest {
    @Test void usesExactPublished620InputsAndTargets() throws Exception {
        Path root=Path.of(System.getProperty("regelsuche.repositoryRoot",".."));
        Path historical=root.resolve("regelsuche-experiments/src/main/resources/de/regelsuche/benchmark/historical-rediscovery-corpus.json");
        byte[] original=Files.readAllBytes(historical);
        assertEquals("sha256:53bff974a9ecb101b85e01f202d095fb2a99f5f33127ed7ec0e4ade2685ac422",ScalarCertificateCodec.hash(original));
        var json=new JsonMapper();
        var cases=json.readTree(original).required("cases"); int retained=0;
        try(var input=NativeScalarComparison.class.getResourceAsStream("native-scalar-panel-v1.json")) {
            for(var row:json.readTree(input)){
                if(!row.required("corpus").asText().equals("historical-rediscovery-corpus/v1"))continue;
                JsonNode found=null; for(var entry:cases) if(entry.get("id").equals(row.get("id"))) found=entry;
                assertNotNull(found); assertEquals(found.required("source"),row.required("source"));
                assertEquals(found.required("target"),row.required("target")); retained++;
            }
        }
        assertEquals(2,retained);
    }
}
