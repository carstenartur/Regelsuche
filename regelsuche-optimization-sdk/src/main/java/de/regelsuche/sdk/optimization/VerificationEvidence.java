package de.regelsuche.sdk.optimization;

import java.util.*;

/** A receipt binds inputs to a proof; reverify executes the checker and does not trust this receipt. */
public record VerificationEvidence(String schemaRevision, String semanticsRevision, String checkerRevision,
        String generatorRevision, String sourceHash, String candidateHash, String traceHash, String assumptionsHash,
        SafetyProfile safetyProfile, CheckedPolicy checkedPolicy, RuntimeObligations obligations,
        List<String> proofMethods, long verificationWork) {
    public VerificationEvidence { proofMethods = List.copyOf(proofMethods); }
}
