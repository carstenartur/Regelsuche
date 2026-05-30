package de.regelsuche.transform;

import java.util.List;

public interface HypothesisOperator {
    List<Transformation> generateCandidates(String expression);
}
