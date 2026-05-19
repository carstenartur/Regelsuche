package de.regelsuche.search.strategy;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.transform.TransformationEngine;

public record SearchProblem(
    String rootExpression,
    TransformationEngine engine,
    ExpressionScorer scorer,
    ExpressionCanonicalizer canonicalizer,
    SearchHeuristic heuristic
) {
}
