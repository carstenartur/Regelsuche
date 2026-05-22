package de.regelsuche.proof;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Resolved configuration for the persistent proof pipeline.
 *
 * <p>Three knobs control where the proof infrastructure stores its state:</p>
 *
 * <ul>
 *   <li>{@code REGELSUCHE_PROOF_ENABLED} (or JVM property
 *       {@code regelsuche.proof.enabled}) — turns the scheduler / REST endpoints
 *       on or off. Defaults to {@code true}; set to {@code false} for tests or
 *       lean (pun intended) deployments that don't need real prover wiring.</li>
 *   <li>{@code REGELSUCHE_PROOF_ARTIFACT_PATH} (or
 *       {@code regelsuche.proof.artifactPath}) — directory under which the
 *       {@link JsonFileProofArtifactRepository} writes per-job artifact bundles
 *       ({@code proof.lean}, {@code stdout.txt}, ...). Default:
 *       {@code <persistencePath>/proofs}.</li>
 *   <li>{@code REGELSUCHE_PROOF_JOB_STORE} (or
 *       {@code regelsuche.proof.jobStore}) — JSON file that holds the persistent
 *       job queue. Default: {@code <persistencePath>/proof-jobs.json}.</li>
 *   <li>{@code REGELSUCHE_PROOF_CACHE} (or
 *       {@code regelsuche.proof.cache}) — JSON file that holds the persistent
 *       proof cache. Default: {@code <persistencePath>/proof-cache.json}.</li>
 * </ul>
 *
 * <p>The Docker image wires sensible defaults so a plain
 * {@code docker run regelsuche} immediately exposes a usable proof workbench
 * with file-backed state under {@code /opt/regelsuche/data}.</p>
 */
public record ProofConfig(
    boolean enabled,
    Path artifactPath,
    Path jobStorePath,
    Path cachePath
) {

    public static final String ENV_ENABLED = "REGELSUCHE_PROOF_ENABLED";
    public static final String ENV_ARTIFACT_PATH = "REGELSUCHE_PROOF_ARTIFACT_PATH";
    public static final String ENV_JOB_STORE = "REGELSUCHE_PROOF_JOB_STORE";
    public static final String ENV_CACHE = "REGELSUCHE_PROOF_CACHE";

    public static final String PROP_ENABLED = "regelsuche.proof.enabled";
    public static final String PROP_ARTIFACT_PATH = "regelsuche.proof.artifactPath";
    public static final String PROP_JOB_STORE = "regelsuche.proof.jobStore";
    public static final String PROP_CACHE = "regelsuche.proof.cache";

    public ProofConfig {
        Objects.requireNonNull(artifactPath, "artifactPath");
        Objects.requireNonNull(jobStorePath, "jobStorePath");
        Objects.requireNonNull(cachePath, "cachePath");
    }

    /** Disabled configuration with placeholder paths. */
    public static ProofConfig disabled() {
        Path placeholder = Paths.get("./data/regelsuche/proofs");
        return new ProofConfig(false, placeholder,
            placeholder.resolveSibling("proof-jobs.json"),
            placeholder.resolveSibling("proof-cache.json"));
    }

    /**
     * Resolve a {@link ProofConfig} from environment + JVM properties, using
     * {@code persistenceRoot} as the base directory for defaults.
     *
     * <p>Explicit JVM properties win over environment variables, which in turn
     * win over the defaults derived from {@code persistenceRoot}.</p>
     */
    public static ProofConfig fromEnvironment(Map<String, String> env, Path persistenceRoot) {
        Objects.requireNonNull(env, "env");
        Path base = persistenceRoot == null
            ? Paths.get("./data/regelsuche")
            : persistenceRoot;

        boolean enabled = parseBoolean(
            firstNonBlank(System.getProperty(PROP_ENABLED), env.get(ENV_ENABLED)),
            true
        );
        Path artifactPath = resolvePath(
            firstNonBlank(System.getProperty(PROP_ARTIFACT_PATH), env.get(ENV_ARTIFACT_PATH)),
            base.resolve("proofs")
        );
        Path jobStorePath = resolvePath(
            firstNonBlank(System.getProperty(PROP_JOB_STORE), env.get(ENV_JOB_STORE)),
            base.resolve("proof-jobs.json")
        );
        Path cachePath = resolvePath(
            firstNonBlank(System.getProperty(PROP_CACHE), env.get(ENV_CACHE)),
            base.resolve("proof-cache.json")
        );
        return new ProofConfig(enabled, artifactPath, jobStorePath, cachePath);
    }

    /** Convenience overload that reads the JVM's real environment. */
    public static ProofConfig fromEnvironment(Path persistenceRoot) {
        return fromEnvironment(System.getenv(), persistenceRoot);
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> fallback;
        };
    }

    private static Path resolvePath(String value, Path fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Paths.get(value);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
