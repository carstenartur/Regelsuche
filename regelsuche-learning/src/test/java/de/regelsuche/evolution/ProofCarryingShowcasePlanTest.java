package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProofCarryingShowcasePlanTest {
    @Test
    void committedPlanIsStrictCanonicalAndStillUnexecuted() {
        ProofCarryingShowcasePlan plan =
            ProofCarryingShowcaseTestFixtures.plan();

        assertEquals(
            "sha256:3aaff50d7208c0339479926049ff8aa9729ae878ab5f9972a54c865ed84970d8",
            plan.contentHash());
        assertEquals(
            ProofCarryingShowcasePlan.STATUS,
            plan.status());
        assertEquals(
            ProofCarryingShowcasePlan.PUBLICATION_GRADE_FLAGSHIP,
            plan.publicationGradeFlagship());
        assertEquals(
            plan,
            ProofCarryingShowcasePlan.fromCanonicalJson(
                plan.toCanonicalJson()));
    }

    @Test
    void rejectsClaimInflationUnknownDuplicateAndTrailingData() {
        ProofCarryingShowcasePlan plan =
            ProofCarryingShowcaseTestFixtures.plan();
        String canonical = plan.toCanonicalJson();

        assertThrows(
            IllegalArgumentException.class,
            () -> ProofCarryingShowcasePlan.fromCanonicalJson(
                canonical.replace(
                    ProofCarryingShowcasePlan.CLAIM_POLICY,
                    "EXTERNAL_NOVELTY_CONFIRMED")));
        assertThrows(
            IllegalArgumentException.class,
            () -> ProofCarryingShowcasePlan.fromCanonicalJson(
                canonical.replaceFirst(
                    "\\{",
                    "{\"unexpected\":true,")));
        assertThrows(
            IllegalArgumentException.class,
            () -> ProofCarryingShowcasePlan.fromCanonicalJson(
                canonical.replaceFirst(
                    "\\{",
                    "{\"schema\":\""
                        + ProofCarryingShowcasePlan.SCHEMA
                        + "\",")));
        assertThrows(
            IllegalArgumentException.class,
            () -> ProofCarryingShowcasePlan.fromCanonicalJson(
                canonical + "{}"));
    }
}
