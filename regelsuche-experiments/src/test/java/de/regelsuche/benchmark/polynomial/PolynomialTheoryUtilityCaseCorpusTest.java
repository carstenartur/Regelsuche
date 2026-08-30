package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PolynomialTheoryUtilityCaseCorpusTest {
    private static final Pattern QUALIFICATION_CASE_LINE = Pattern.compile(
        "^    \\{\"caseId\":\"([a-z0-9-]+)\","
            + "\"requiredOutcome\":\"([A-Z_]+)\","
            + "\"reducibilityStatus\":\"([A-Z_]+)\","
            + "\"multiplicityStatus\":\"([A-Z_]+)\","
            + "\"referenceExpression\":\"([^\"\\\\]+)\","
            + "\"expectedClassifierOutcome\":\"([A-Z_]+)\"\\}(,?)$"
    );

    @Test
    void freezesTargetBlindFormationCasesWithMatchedWorkBudgets() {
        PolynomialTheoryUtilityCaseCorpus.FormationArtifact artifact =
            PolynomialTheoryUtilityCaseCorpus.load();

        assertEquals(
            "regelsuche.polynomial-theory-utility-formation-corpus/v1",
            artifact.schema()
        );
        assertEquals("FROZEN_NOT_EXECUTED", artifact.evidenceStatus());
        assertEquals(
            "BEFORE_PROFILE_EXECUTION",
            artifact.caseSelectionTiming()
        );
        assertEquals(
            "IDENTICAL_ACROSS_PROFILES",
            artifact.profileVisibility()
        );
        assertEquals(
            "HASH_ONLY_BEFORE_RESULT_FREEZE",
            artifact.qualificationExposure()
        );
        assertEquals(
            PolynomialTheoryUtilityCaseCorpus.ORDERED_CASE_IDS,
            artifact.cases().stream()
                .map(PolynomialTheoryUtilityCaseCorpus.FormationCase::caseId)
                .toList()
        );
        assertEquals(
            Set.of("Z[x]", "Q[x]", "Z[x,y]", "Q(x)", "Z[x,n]"),
            artifact.cases().stream()
                .map(
                    PolynomialTheoryUtilityCaseCorpus.FormationCase
                        ::declaredDomain
                )
                .collect(java.util.stream.Collectors.toSet())
        );
        assertTrue(artifact.cases().stream().anyMatch(value ->
            value.occurrenceDepth() == 2 && value.reuseCount() == 4));
        assertTrue(artifact.cases().stream().anyMatch(value ->
            value.factorizationWork() == 2));
        assertTrue(artifact.cases().stream().allMatch(value ->
            value.totalMechanicalWork() >= value.admittedPrimitiveWork()
                && value.admittedPrimitiveWork()
                    >= value.factorizationWork()));

        for (String forbidden : List.of(
                "\"requiredOutcome\"",
                "\"reducibilityStatus\"",
                "\"multiplicityStatus\"",
                "\"referenceExpression\"",
                "\"expectedClassifierOutcome\"")) {
            assertFalse(artifact.canonicalJson().contains(forbidden));
        }
    }

    @Test
    void bindsButDoesNotExportTheSealedQualification(
        @TempDir Path directory
    ) throws IOException {
        PolynomialTheoryUtilityCaseCorpus.FormationArtifact artifact =
            PolynomialTheoryUtilityCaseCorpus.write(directory);

        Path formation = directory.resolve(
            PolynomialTheoryUtilityCaseCorpus.FORMATION_FILE_NAME
        );
        Path qualification = directory.resolve(
            PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_FILE_NAME
        );
        assertEquals(
            artifact.canonicalJson(),
            Files.readString(formation, StandardCharsets.UTF_8)
        );
        assertFalse(Files.exists(qualification));
        assertEquals(
            PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_CONTENT_HASH,
            artifact.qualificationBinding().contentHash()
        );
        assertEquals(
            PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_BYTE_LENGTH,
            artifact.qualificationBinding().byteLength()
        );
    }

    @Test
    void sealedQualificationAlignsCasesAndCoversEveryRequiredOutcome() {
        byte[] bytes = readResource(
            PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_RESOURCE
        );
        assertEquals(
            PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_BYTE_LENGTH,
            bytes.length
        );
        assertEquals(
            PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_CONTENT_HASH,
            sha256(bytes)
        );

        String canonical = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(canonical.startsWith("{\n"));
        assertTrue(canonical.endsWith("}\n"));
        assertFalse(canonical.contains("\r"));
        String[] lines = canonical.split("\n", -1);
        assertEquals(32, lines.length);
        assertEquals(
            "  \"schema\": "
                + "\"regelsuche.polynomial-theory-utility-"
                + "qualification-corpus/v1\",",
            lines[1]
        );
        assertEquals(
            "  \"evidenceStatus\": \"SEALED_NOT_OPENED\",",
            lines[3]
        );
        assertEquals(
            "  \"openingCondition\": "
                + "\"AFTER_VERSIONED_RESULT_FREEZE\",",
            lines[4]
        );
        assertEquals(
            "  \"candidateFormationMayRead\": false,",
            lines[5]
        );
        assertEquals("  \"profileSelectionMayRead\": false,", lines[6]);

        List<String> expectedIds =
            PolynomialTheoryUtilityCaseCorpus.ORDERED_CASE_IDS;
        Set<String> outcomes = new HashSet<>();
        for (int index = 0; index < expectedIds.size(); index++) {
            Matcher matcher = QUALIFICATION_CASE_LINE.matcher(
                lines[9 + index]
            );
            assertTrue(matcher.matches(), "qualification line " + index);
            assertEquals(expectedIds.get(index), matcher.group(1));
            outcomes.add(matcher.group(2));
            assertEquals(
                index + 1 < expectedIds.size(),
                !matcher.group(7).isEmpty()
            );
        }
        assertEquals(
            Set.copyOf(
                PolynomialTheoryUtilityPreregistration.REQUIRED_CASE_OUTCOMES
            ),
            outcomes
        );
        assertEquals("  ]", lines[29]);
        assertEquals("}", lines[30]);
        assertEquals("", lines[31]);
    }

    @Test
    void rejectsAddedOrReorderedCasesEvenAfterIdentityReissue() {
        String canonical = PolynomialTheoryUtilityCaseCorpus.load()
            .canonicalJson();
        String[] reordered = canonical.split("\n", -1);
        String first = reordered[14];
        reordered[14] = reordered[15];
        reordered[15] = first;
        IllegalStateException orderFailure = assertThrows(
            IllegalStateException.class,
            () -> PolynomialTheoryUtilityCaseCorpus.parseCanonical(
                String.join("\n", reordered)
            )
        );
        assertTrue(orderFailure.getMessage().contains("ordered case"));

        String extra = canonical.replace(
            "  ]\n}\n",
            canonical.lines()
                .filter(line -> line.contains(
                    "\"caseId\":\"z10-tiny-budget\""))
                .findFirst()
                .orElseThrow()
                + ",\n  ]\n}\n"
        );
        IllegalStateException countFailure = assertThrows(
            IllegalStateException.class,
            () -> PolynomialTheoryUtilityCaseCorpus.parseCanonical(extra)
        );
        assertTrue(countFailure.getMessage().contains("line count"));
    }

    @Test
    void rejectsQualificationLeakageAndCrLfConversion() {
        String canonical = PolynomialTheoryUtilityCaseCorpus.load()
            .canonicalJson();
        String leaked = canonical.replace(
            "  \"cases\": [",
            "  \"requiredOutcome\": \"POSITIVE\",\n  \"cases\": ["
        );
        IllegalStateException leakFailure = assertThrows(
            IllegalStateException.class,
            () -> PolynomialTheoryUtilityCaseCorpus.parseCanonical(leaked)
        );
        assertTrue(leakFailure.getMessage().contains("sealed qualification"));

        IllegalStateException framingFailure = assertThrows(
            IllegalStateException.class,
            () -> PolynomialTheoryUtilityCaseCorpus.parseCanonical(
                canonical.replace("\n", "\r\n")
            )
        );
        assertTrue(framingFailure.getMessage().contains("eol=lf"));
        assertTrue(framingFailure.getMessage().contains("renormalize"));
    }

    @Test
    void exposesImmutableValidatedRecords() {
        PolynomialTheoryUtilityCaseCorpus.FormationArtifact artifact =
            PolynomialTheoryUtilityCaseCorpus.load();

        assertThrows(
            UnsupportedOperationException.class,
            () -> artifact.cases().add(artifact.cases().get(0))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityCaseCorpus.QualificationBinding(
                "other.json",
                1L,
                "sha256:other"
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityCaseCorpus.FormationCase(
                "",
                "x",
                "Z[x]",
                1,
                "SMALL",
                "DENSE",
                1,
                0,
                "ROOT",
                "NONE",
                1,
                1,
                1
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCaseCorpus.main(new String[0])
        );
    }

    private static byte[] readResource(String resource) {
        try (InputStream input =
                PolynomialTheoryUtilityCaseCorpus.class
                    .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException(
                    "missing test resource " + resource
                );
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException(
                "cannot read test resource " + resource,
                exception
            );
        }
    }

    private static String sha256(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
