package de.regelsuche.discovery.representation;

import java.util.List;
import java.util.Optional;

/** Immutable storage boundary for content-addressed discovery run workspaces. */
public interface RepresentationDiscoveryRunRepository {
    /**
     * Retains a workspace under its Run ID. Re-saving identical canonical
     * bytes is idempotent; replacing an existing Run ID is forbidden.
     */
    RepresentationDiscoveryRunWorkspace save(
        RepresentationDiscoveryRunWorkspace workspace
    );

    /** Returns the exact retained workspace for a Run ID, when present. */
    Optional<RepresentationDiscoveryRunWorkspace> find(String runId);

    /** Returns every retained workspace in deterministic Run-ID order. */
    List<RepresentationDiscoveryRunWorkspace> list();
}
