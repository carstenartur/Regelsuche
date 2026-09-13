package de.regelsuche.evolution;

import java.nio.file.Files;
import java.nio.file.Path;

/** Forked crash control; never a production or real-study execution command. */
public final class SyntheticProgramFinalReservationCrash {
    private SyntheticProgramFinalReservationCrash() { }
    public static void main(String[] args) throws Exception {
        var plan = EvolutionRewriteProgramFinalTestPlan.fromCanonicalJson(Files.readString(Path.of(args[0])));
        if (!plan.commitment().studyId().startsWith("synthetic_")) {
            throw new IllegalArgumentException("synthetic crash control only");
        }
        new FileEvolutionRewriteProgramFinalTestAttemptStore(Path.of(args[1])).reserve(plan);
        Runtime.getRuntime().halt(0);
    }
}
