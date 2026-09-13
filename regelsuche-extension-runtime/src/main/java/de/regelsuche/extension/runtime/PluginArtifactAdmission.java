package de.regelsuche.extension.runtime;

import de.regelsuche.api.StableApi;
import java.nio.file.Path;

/** Host-owned admission boundary evaluated before external plugin classloading. */
@FunctionalInterface
@StableApi(since = "2")
public interface PluginArtifactAdmission {
    /**
     * Admits the source artifact and snapshots the exact trusted bytes.
     *
     * @param sourceJar caller-selected source path; the runtime never loads it directly
     * @return immutable snapshot of the exact admitted bytes and their evidence
     */
    AdmittedPluginArtifact admit(Path sourceJar) throws Exception;
}
