package de.regelsuche.web;

import de.regelsuche.evolution.RepresentationTransferExperiment;

/** One immutable execution per server JVM; repeated reads cannot retrain a policy. */
final class RepresentationStudy {
    private RepresentationStudy() { }
    static String json() { return Holder.JSON; }
    private static final class Holder {
        private static final String JSON = RepresentationTransferExperiment.toJson(
            new RepresentationTransferExperiment().run());
    }
}
