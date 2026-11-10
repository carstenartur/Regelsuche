package de.regelsuche.plugin;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable bytes and metadata selected and verified together by a distribution
 * client. Only a client authority read can create this host snapshot.
 */
public final class InstalledPluginSnapshot {
    private final PluginInstallationEvidence installation;
    private final Optional<PluginArtifactResolver.ResolutionReceipt> resolution;
    private final List<Artifact> artifacts;

    InstalledPluginSnapshot(PluginInstallationEvidence installation,
            PluginArtifactResolver.ResolutionReceipt resolution, List<Artifact> artifacts) {
        this.installation = Objects.requireNonNull(installation, "installation");
        this.resolution = Optional.ofNullable(resolution);
        this.artifacts = List.copyOf(artifacts);
        if (this.artifacts.isEmpty() == this.resolution.isPresent()
                || !installation.artifacts().equals(this.artifacts.stream().map(Artifact::evidence).toList())) {
            throw new IllegalArgumentException("snapshot closure differs from the installation");
        }
    }

    public PluginInstallationEvidence installation() { return installation; }
    public Optional<PluginArtifactResolver.ResolutionReceipt> resolution() { return resolution; }
    public List<Artifact> artifacts() { return artifacts; }

    /** A verified artifact in dependency order; every byte accessor returns a copy. */
    public static final class Artifact {
        private final PluginInstallationEvidence.Artifact evidence;
        private final PluginArtifactIndex.Entry entry;
        private final byte[] bytes;

        Artifact(PluginInstallationEvidence.Artifact evidence, PluginArtifactIndex.Entry entry, byte[] bytes) {
            this.evidence = Objects.requireNonNull(evidence, "evidence");
            this.entry = Objects.requireNonNull(entry, "entry");
            if (!entry.identityHash().equals(evidence.identityHash())) {
                throw new IllegalArgumentException("artifact identity differs from its signed index entry");
            }
            this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
        }

        public PluginInstallationEvidence.Artifact evidence() { return evidence; }
        public PluginArtifactIndex.Entry entry() { return entry; }
        public byte[] bytes() { return bytes.clone(); }
    }
}
