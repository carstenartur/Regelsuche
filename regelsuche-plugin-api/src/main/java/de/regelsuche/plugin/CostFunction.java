package de.regelsuche.plugin;

import de.regelsuche.transform.Transformation;

@de.regelsuche.api.StableApi(since = "1")
public interface CostFunction extends PluginExtension {
    int cost(Transformation transformation);
}
