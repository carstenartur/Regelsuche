package de.regelsuche.discovery.representation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TargetFreeRepresentationCandidateFreezeTest {
    private static final String REPOSITORY_REVISION =
        "0123456789abcdef0123456789abcdef01234567";

    @Test
    void executesAndFreezesTheExactTargetBlindMatrix() {
        var artifact = TargetFreeRepresentationCandidateFreeze.run(
            REPOSITORY_REVISION);
        var content = artifact.content();

        assertEquals(
            TargetFreeRepresentationCandidateFreeze.EVIDENCE_STATUS,
            content.evidenceStatus()
        );
        assertEquals(
            TargetFreeRepresentationCandidateFreeze
                .QUALIFICATION_DISCLOSURE,
            content.qualificationDisclosure()
        );
        assertEquals(24, content.entries().size());
        assertEquals(24, content.summary().configuredEntryCount());
        assertEquals(24, content.summary().executedEntryCount());
        assertEquals(
            24L,
            content.entries().stream()
                .map(TargetFreeRepresentationCandidateFreeze
                    .ExecutionEntry::configurationId)
                .distinct()
                .count()
        );
        assertTrue(content.entries().stream().allMatch(entry ->
            entry.candidateCount() == entry.candidates().size()
                && entry.candidateBatchHash().startsWith("sha256:")
                && entry.candidateSetHash().startsWith("sha256:")
                && entry.candidateFreezeReceiptHash().startsWith("sha256:")
                && entry.work().contentHash().startsWith("sha256:")
        ));
        assertEquals(
            Set.of(
                "BOUNDED_ENUMERATION_V1",
                "RANDOM_MONTE_CARLO_V1",
                "SCALAR_BEST_FIRST_V1",
                "STRUCTURAL_DIVERSITY_V1"
            ),
            content.entries().stream()
                .map(TargetFreeRepresentationCandidateFreeze
                    .ExecutionEntry::policyId)
                .collect(java.util.stream.Collectors.toSet())
        );

        var neutralEnumeration = content.entries().stream()
            .filter(entry -> entry.caseId().equals(
                "neutral-element-compression"))
            .filter(entry -> entry.policyId().equals(
                "BOUNDED_ENUMERATION_V1"))
            .findFirst()
            .orElseThrow();
        assertTrue(neutralEnumeration.candidates().stream()
            .anyMatch(candidate -> candidate.expression().equals("x")));

        String canonical = artifact.toCanonicalJson();
        assertFalse(canonical.contains("\"referenceExpressions\""));
        assertFalse(canonical.contains("\"requiredCapabilities\""));
        assertFalse(canonical.contains("\"acceptedCandidateTypes\""));
        assertFalse(canonical.contains("(a + b)^2 + y"));
        assertFalse(canonical.contains("1 / n - 1 / (n + 1)"));
        assertFalse(canonical.contains(
            "capability:finite-sum-telescoping"));
    }

    @Test
    void retainedFreezeIsCanonicalByteStableAndTamperEvident(
        @TempDir Path temporary
    ) throws Exception {
        var first = TargetFreeRepresentationCandidateFreeze.write(
            temporary,
            REPOSITORY_REVISION
        );
        byte[] firstBytes = Files.readAllBytes(temporary.resolve(
            TargetFreeRepresentationCandidateFreeze.FILE_NAME));
        var second = TargetFreeRepresentationCandidateFreeze.write(
            temporary,
            REPOSITORY_REVISION
        );
        byte[] secondBytes = Files.readAllBytes(temporary.resolve(
            TargetFreeRepresentationCandidateFreeze.FILE_NAME));

        assertEquals(first, second);
        assertArrayEquals(firstBytes, secondBytes);
        assertEquals(
            first,
            TargetFreeRepresentationCandidateFreeze.FreezeArtifact
                .fromCanonicalJson(new String(
                    firstBytes,
                    StandardCharsets.UTF_8
                ))
        );
        assertFalse(new String(
            firstBytes,
            StandardCharsets.UTF_8
        ).contains("\r\n"));
        assertThrows(
            IllegalArgumentException.class,
            () -> TargetFreeRepresentationCandidateFreeze.FreezeArtifact
                .fromCanonicalJson(first.toCanonicalJson() + "\n")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> TargetFreeRepresentationCandidateFreeze.FreezeArtifact
                .fromCanonicalJson(first.toCanonicalJson().replaceFirst(
                    "\"status\":\"EXECUTED\"",
                    "\"status\":\"REMAINING\""
                ))
        );
    }
}
