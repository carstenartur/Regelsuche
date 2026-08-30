package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PolynomialTheoryUtilityPreregistrationTest {
    @Test
    void freezesEveryMandatoryProfileBeforeHeldOutResults() {
        PolynomialTheoryUtilityPreregistration.Artifact artifact =
            PolynomialTheoryUtilityPreregistration.load();

        assertEquals(
            "regelsuche.polynomial-theory-utility-preregistration/v1",
            artifact.schema()
        );
        assertEquals(
            "polynomial-theory-held-out-utility-v1",
            artifact.studyId()
        );
        assertEquals("FROZEN_NOT_EXECUTED", artifact.evidenceStatus());
        assertEquals(
            "BEFORE_HELD_OUT_RESULTS",
            artifact.profileSelectionTiming()
        );
        assertEquals(
            List.of(
                "NO_FACTORIZATION",
                "ON_DEMAND_VERIFIED_FACTORIZATION",
                "VERIFIED_DERIVED_MACRO_CACHE",
                "SPECIALIZED_BINARY_QUARTIC_CONTROL",
                "OPTIONAL_EXTERNAL_VERIFIED_FACTORIZATION"
            ),
            artifact.profiles()
        );
    }

    @Test
    void retainsMatchedWorkNullDecisionAndRequiredOutcomeCoverage() {
        PolynomialTheoryUtilityPreregistration.Artifact artifact =
            PolynomialTheoryUtilityPreregistration.load();

        assertEquals(
            List.of(
                "POSITIVE",
                "NEGATIVE",
                "UNSUPPORTED",
                "BUDGET_INCONCLUSIVE",
                "NEAR_MISS"
            ),
            artifact.requiredCaseOutcomes()
        );
        assertEquals(
            List.of(
                "ON_DEMAND_DEFAULT_JUSTIFIED",
                "VERIFIED_CACHE_DEFAULT_JUSTIFIED",
                "HYBRID_POLICY_JUSTIFIED",
                "KEEP_OPT_IN",
                "NULL_RESULT_NO_MATERIAL_UTILITY"
            ),
            artifact.decisionOutcomes()
        );
        assertTrue(artifact.canonicalJson().contains(
            "\"admittedPrimitiveWork\": "
                + "\"MATCH_AT_EVERY_POLICY_CHECKPOINT\""
        ));
        assertTrue(artifact.canonicalJson().contains(
            "\"totalMechanicalWork\": "
                + "\"MATCH_AT_EVERY_POLICY_CHECKPOINT\""
        ));
        assertTrue(artifact.canonicalJson().contains(
            "\"wallClockOnly\": \"INSUFFICIENT\""
        ));
        assertFalse(artifact.canonicalJson().contains(
            "\"selectedDecision\""
        ));
    }

    @Test
    void rejectsExtraOrReorderedArrayValuesEvenAfterAReissuedHash() {
        String canonical = PolynomialTheoryUtilityPreregistration.load()
            .canonicalJson();
        String extraProfile = canonical.replace(
            "    \"OPTIONAL_EXTERNAL_VERIFIED_FACTORIZATION\"\n  ],",
            "    \"OPTIONAL_EXTERNAL_VERIFIED_FACTORIZATION\",\n"
                + "    \"POST_RESULT_PROFILE\"\n  ],"
        );
        assertNotEquals(canonical, extraProfile);
        IllegalStateException extraFailure = assertThrows(
            IllegalStateException.class,
            () -> PolynomialTheoryUtilityPreregistration.validateStructure(
                extraProfile
            )
        );
        assertTrue(extraFailure.getMessage().contains("profiles"));

        String reorderedDecisions = canonical.replace(
            "    \"ON_DEMAND_DEFAULT_JUSTIFIED\",\n"
                + "    \"VERIFIED_CACHE_DEFAULT_JUSTIFIED\",",
            "    \"VERIFIED_CACHE_DEFAULT_JUSTIFIED\",\n"
                + "    \"ON_DEMAND_DEFAULT_JUSTIFIED\","
        );
        assertNotEquals(canonical, reorderedDecisions);
        IllegalStateException orderFailure = assertThrows(
            IllegalStateException.class,
            () -> PolynomialTheoryUtilityPreregistration.validateStructure(
                reorderedDecisions
            )
        );
        assertTrue(orderFailure.getMessage().contains("decisionOutcomes"));
    }

    @Test
    void explainsHowToRepairCrLfConversion() {
        String crlf = PolynomialTheoryUtilityPreregistration.load()
            .canonicalJson()
            .replace("\n", "\r\n");

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> PolynomialTheoryUtilityPreregistration.validateStructure(crlf)
        );
        assertTrue(failure.getMessage().contains("LF"));
        assertTrue(failure.getMessage().contains(".gitattributes"));
        assertTrue(failure.getMessage().contains("renormalize"));
    }

    @Test
    void writesTheContentAddressedContractByteIdentically(
        @TempDir Path directory
    ) throws IOException {
        PolynomialTheoryUtilityPreregistration.Artifact artifact =
            PolynomialTheoryUtilityPreregistration.write(directory);
        Path output = directory.resolve(
            PolynomialTheoryUtilityPreregistration.FILE_NAME
        );

        assertEquals(
            PolynomialTheoryUtilityPreregistration.CONTENT_HASH,
            artifact.contentHash()
        );
        assertEquals(
            PolynomialTheoryUtilityPreregistration.BYTE_LENGTH,
            artifact.byteLength()
        );
        assertEquals(
            artifact.canonicalJson(),
            Files.readString(output, StandardCharsets.UTF_8)
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> artifact.profiles().add("POST_RESULT_PROFILE")
        );
    }
}
