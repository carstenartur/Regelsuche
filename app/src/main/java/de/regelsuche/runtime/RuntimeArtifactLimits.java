package de.regelsuche.runtime;

import java.nio.charset.StandardCharsets;

/** Bounds complete exports so the default Workbench can accept their replay envelope. */
public final class RuntimeArtifactLimits {
    public static final int DEFAULT_REQUEST_BYTES = 1 << 20;
    private static final int REPLAY_ENVELOPE_BYTES = "{\"runtimeArtifact\":}".length();
    public static final int MAX_ARTIFACT_BYTES = DEFAULT_REQUEST_BYTES - REPLAY_ENVELOPE_BYTES;

    private RuntimeArtifactLimits() { }

    public static String requireReplayable(String artifact) {
        return requireReplayable(artifact, DEFAULT_REQUEST_BYTES);
    }

    /** A server may tighten its request policy; it cannot enlarge the shared export contract. */
    public static String requireReplayable(String artifact, int requestLimitBytes) {
        int limit = Math.min(MAX_ARTIFACT_BYTES, Math.max(0, requestLimitBytes - REPLAY_ENVELOPE_BYTES));
        int actual = artifact.getBytes(StandardCharsets.UTF_8).length;
        if (actual > limit) throw new ArtifactTooLargeException(limit, actual);
        return artifact;
    }

    public static final class ArtifactTooLargeException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final int limitBytes;
        private final int actualBytes;

        private ArtifactTooLargeException(int limitBytes, int actualBytes) {
            super("RUNTIME_ARTIFACT_TOO_LARGE: complete artifact exceeds replayable export limit ("
                + actualBytes + " bytes; limit " + limitBytes + ")");
            this.limitBytes = limitBytes;
            this.actualBytes = actualBytes;
        }

        public int limitBytes() { return limitBytes; }
        public int actualBytes() { return actualBytes; }
    }
}
