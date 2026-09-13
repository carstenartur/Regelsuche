package de.regelsuche.runtime;

import de.regelsuche.web.StreamingJsonRequestBody;
import de.regelsuche.web.WebSecurityConfig;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeArtifactLimitsTest {
    @Test void exactUtf8BoundaryIncludesTheCompleteHttpEnvelope() throws Exception {
        int limit = RuntimeArtifactLimits.MAX_ARTIFACT_BYTES;
        // Non-ASCII characters distinguish the transport byte boundary from String.length().
        String exact = "{\"source\":\"" + "é".repeat((limit - 14) / 2) + "\"}\n";
        assertEquals(limit, exact.getBytes(StandardCharsets.UTF_8).length);
        assertSame(exact, RuntimeArtifactLimits.requireReplayable(exact));
        byte[] body = ("{\"runtimeArtifact\":" + exact + "}").getBytes(StandardCharsets.UTF_8);
        assertEquals(WebSecurityConfig.none().maxRequestBytes(), body.length);
        assertTrue(new StreamingJsonRequestBody(WebSecurityConfig.none().maxRequestBytes())
            .readObject(new ByteArrayInputStream(body)).containsKey("runtimeArtifact"));
        var error = assertThrows(RuntimeArtifactLimits.ArtifactTooLargeException.class,
            () -> RuntimeArtifactLimits.requireReplayable(exact + " "));
        assertEquals(limit, error.limitBytes());
        assertEquals(limit + 1, error.actualBytes());
    }

    @Test void largerHttpConfigurationDoesNotWidenTheSharedExportContract() {
        assertThrows(RuntimeArtifactLimits.ArtifactTooLargeException.class,
            () -> RuntimeArtifactLimits.requireReplayable("x".repeat(1 << 20), 4 << 20));
        var error = assertThrows(RuntimeArtifactLimits.ArtifactTooLargeException.class,
            () -> RuntimeArtifactLimits.requireReplayable("é".repeat(503), 1024));
        assertEquals(1004, error.limitBytes());
        assertEquals(1006, error.actualBytes());
    }
}
