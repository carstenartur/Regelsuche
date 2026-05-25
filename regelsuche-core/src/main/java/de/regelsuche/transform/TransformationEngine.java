package de.regelsuche.transform;

import java.util.List;

public interface TransformationEngine {
    List<Transformation> transform(String expression);
}
