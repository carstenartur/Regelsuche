package de.regelsuche.mining;

/** Independent factor used by the composite hypothesis interestingness ranking. */
public interface InterestingnessScoringModule {
    String name();

    double score(InterestingnessScoringContext context);
}
