package de.regelsuche.plugin;

import de.regelsuche.transform.Transformation;

public interface CostFunction extends PluginExtension {
    int cost(Transformation transformation);
}
