package de.regelsuche.literature;

import static de.regelsuche.literature.CandidateLiteratureAssessments.Status.NO_PRIOR_ART_FOUND_WITHIN_SCOPE;
import static de.regelsuche.literature.CandidateLiteratureAssessments.Status.PRIOR_ART_FOUND;
import static de.regelsuche.literature.CandidateLiteratureAssessments.Status.UNRESOLVED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.literature.LiteratureReviewRepository.Subject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CandidateLiteratureAssessmentsTest {
    private static final Subject SUBJECT = new Subject(hash("a"), hash("b"), hash("c"));
    private static final String SOURCE = """
        {
          "id": "faltung-2020", "authors": ["Ada Example"], "title": "Faltung über ℚ",
          "year": 2020, "identifier": "Example series 12", "version": "2", "locator": "Theorem 3",
          "url": "https://example.org/math/faltung"
        }
        """;
    @TempDir Path work;

    @Test
    void retainsOneImmutableAssessmentForEquivalentJson() throws IOException {
        var first = repository().retain(SUBJECT, assessment(PRIOR_ART_FOUND, match("EQUIVALENT", "")));
        var repeated = repository().retain(SUBJECT, (" \n" + new String(
            assessment(PRIOR_ART_FOUND, match("EQUIVALENT", "")), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        assertEquals(first, repeated);
        assertEquals(List.of(first), repository().list(SUBJECT));
        assertEquals("Faltung über ℚ", first.matches().getFirst().source().title());
        assertEquals("Example index", first.scope().getFirst().source());
        assertEquals(List.of("Faltung ℚ"), first.queries());

        var later = repository().retain(SUBJECT, assessment(UNRESOLVED, "[]", "2026-11-09"));
        assertEquals(List.of(later, first), repository().list(SUBJECT));
    }

    @Test
    void bindsAssessmentToAllPartsOfRetainedEvidence() throws IOException {
        repository().retain(SUBJECT, assessment(UNRESOLVED, "[]"));
        for (var other : List.of(
            new Subject(hash("d"), SUBJECT.candidateId(), SUBJECT.artifactHash()),
            new Subject(SUBJECT.runId(), hash("d"), SUBJECT.artifactHash()),
            new Subject(SUBJECT.runId(), SUBJECT.candidateId(), hash("d"))
        )) {
            assertEquals(List.of(), repository().list(other));
            assertThrows(IOException.class, () -> repository().retain(other, assessment(UNRESOLVED, "[]")));
        }
    }

    @Test
    void requiresDecisivePriorArtAndRejectsContradictoryScopedAbsence() throws IOException {
        assertThrows(IOException.class, () -> repository().retain(SUBJECT,
            assessment(PRIOR_ART_FOUND, match("SOURCE_SPECIAL_CASE", ""))));
        assertThrows(IOException.class, () -> repository().retain(SUBJECT,
            assessment(PRIOR_ART_FOUND, match("SOURCE_GENERALIZATION", ""))));
        assertThrows(IOException.class, () -> repository().retain(SUBJECT,
            assessment(NO_PRIOR_ART_FOUND_WITHIN_SCOPE, match("EXACT", ""))));
        String documentedGeneralization = match("SOURCE_GENERALIZATION",
            ",\"entailmentRationale\":\"Set t = 0 and restrict the coefficient ring to ℚ.\"");
        assertThrows(IOException.class, () -> repository().retain(SUBJECT,
            assessment(NO_PRIOR_ART_FOUND_WITHIN_SCOPE, documentedGeneralization)));

        var prior = repository().retain(SUBJECT, assessment(PRIOR_ART_FOUND, documentedGeneralization));
        assertEquals(PRIOR_ART_FOUND, prior.status());
        assertEquals(NO_PRIOR_ART_FOUND_WITHIN_SCOPE, repository().retain(SUBJECT,
            assessment(NO_PRIOR_ART_FOUND_WITHIN_SCOPE, match("RELATED", ""))).status());
    }

    @Test
    void rejectsUnsupportedNoveltyUnscopedAssessmentAndInvalidJson() throws IOException {
        String valid = new String(assessment(UNRESOLVED, "[]"), StandardCharsets.UTF_8);
        for (String invalid : List.of(
            valid.replace("UNRESOLVED", "EXTERNALLY_NOVEL"),
            valid.replace("[{\"source\": \"Example index\", \"versionOrDate\": \"2025-11-06\"}]", "[]"),
            valid.replace("\"Faltung ℚ\"", "21"),
            valid.replace("\"matches\":", "\"extra\":null,\"matches\":"),
            valid.replace("\"reviewedBy\":", "\"reviewedBy\":\"Other\",\"reviewedBy\":"),
            valid + "{}",
            " ".repeat(1_048_576) + valid
        )) {
            assertThrows(IOException.class, () -> repository().retain(SUBJECT, invalid.getBytes(StandardCharsets.UTF_8)));
        }
        assertEquals(List.of(), repository().list(SUBJECT));
    }

    @Test
    void refusesToFollowStorageSymlinks() throws IOException {
        var root = work.resolve("assessments");
        if (!Files.getFileStore(work).supportsFileAttributeView("posix")) return;
        Files.createSymbolicLink(root, Files.createDirectory(work.resolve("other")));
        assertThrows(IOException.class, () -> repository().retain(SUBJECT, assessment(UNRESOLVED, "[]")));
        assertThrows(IOException.class, () -> repository().list(SUBJECT));
    }

    @Test
    void refusesToTreatModifiedStoredContentAsAnOriginalAssessment() throws IOException {
        byte[] original = assessment(UNRESOLVED, "[]");
        repository().retain(SUBJECT, original);
        Path stored;
        try (var entries = Files.newDirectoryStream(work.resolve("assessments"), "*.json")) {
            stored = entries.iterator().next();
        }
        String modified = Files.readString(stored).replace("Ada Reviewer", "Other Reviewer");
        Files.writeString(stored, modified);
        assertThrows(IOException.class, () -> repository().list(SUBJECT));
        assertThrows(IOException.class, () -> repository().retain(SUBJECT, original));
        assertEquals(modified, Files.readString(stored));
    }

    private CandidateLiteratureAssessments repository() {
        return new CandidateLiteratureAssessments(work.resolve("assessments"));
    }

    private static byte[] assessment(CandidateLiteratureAssessments.Status status, String matches) {
        return assessment(status, matches, "2026-11-08");
    }

    private static byte[] assessment(CandidateLiteratureAssessments.Status status, String matches, String date) {
        return """
            {
              "schema": "regelsuche.literature-assessment/v1",
              "subject": {"runId": "%s", "candidateId": "%s", "artifactHash": "%s"},
              "reviewedOn": "%s", "reviewedBy": "Ada Reviewer",
              "scope": [{"source": "Example index", "versionOrDate": "2025-11-06"}],
              "queries": ["Faltung ℚ"], "status": "%s", "matches": %s
            }
            """.formatted(SUBJECT.runId(), SUBJECT.candidateId(), SUBJECT.artifactHash(),
                date, status, matches).getBytes(StandardCharsets.UTF_8);
    }

    private static String match(String relation, String extra) {
        return """
            [{"source": %s, "relation": "%s", "assumptions": ["Ring ℚ"], "rationale": "Compare Theorem 3." %s}]
            """.formatted(SOURCE, relation, extra);
    }

    private static String hash(String digit) {
        return "sha256:" + digit.repeat(64);
    }
}
