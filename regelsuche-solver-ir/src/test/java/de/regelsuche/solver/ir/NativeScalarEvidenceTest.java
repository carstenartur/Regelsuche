package de.regelsuche.solver.ir;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.assumption.Assumption;
import de.regelsuche.egraph.NativeScalarSaturation;
import de.regelsuche.egraph.proof.ScalarCertificateCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeScalarEvidenceTest {
    @TempDir Path directory;
    @Test void independentCleanExecutionsMatchEveryByteAndDetachedReceiverRejectsChangedPremises() throws Exception {
        var source="((b+0)*a)/a"; var target="b"; var revision="a".repeat(40);
        var premises=List.of(Assumption.nonZero("a"));
        var budget=new NativeScalarSaturation.Budget(6,256,4000,NativeScalarSaturation.Schedule.ROUND_ROBIN);
        var first=NativeScalarEvidence.run(source,premises,revision,budget);
        var second=NativeScalarEvidence.run(source,premises,revision,budget);
        assertEquals(first.report(),second.report()); assertEquals(first.certificates().keySet(),second.certificates().keySet());
        for(var key:first.certificates().keySet()) assertArrayEquals(first.certificates().get(key),second.certificates().get(key));
        first.write(directory);
        var reportHash=ScalarCertificateCodec.hash(first.report().getBytes(StandardCharsets.UTF_8));
        NativeScalarEvidence.verify(directory,reportHash,source,premises,revision,budget);
        var record=new JsonMapper().readTree(first.report()).required("extractions").get(0);
        assertEquals(target,record.required("authorized").asText());
        String digest=record.required("derivation").asText();
        Path certificate=directory.resolve(digest.substring(7)+".rsproof");
        NativeScalarEvidence.checkCertificate(certificate,digest,source,target,premises,revision);
        assertThrows(IllegalArgumentException.class,() -> NativeScalarEvidence.checkCertificate(certificate,digest,source,target,List.of(),revision));
        Files.write(certificate,new byte[]{1});
        assertThrows(IllegalArgumentException.class,() -> NativeScalarEvidence.verify(directory,reportHash,source,premises,revision,budget));
    }
}
