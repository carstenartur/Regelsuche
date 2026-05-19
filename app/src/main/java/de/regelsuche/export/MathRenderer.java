package de.regelsuche.export;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;

public interface MathRenderer {
    String renderExpression(String expression);

    String renderStep(TransformationStep step);

    String renderPath(DiscoveredTransformation path);
}
