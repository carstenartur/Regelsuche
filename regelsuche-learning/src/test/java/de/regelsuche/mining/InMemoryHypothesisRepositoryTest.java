package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.validation.CandidateProofStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link InMemoryHypothesisRepository}. */
class InMemoryHypothesisRepositoryTest {

    private static RuleCandidate candidate(String hash) {
        return new RuleCandidate(
            "x + A",
            "A + x",
            3,
            2.0,
            4,
            true,
            true,
            true,
            List.of(),
            RuleStatus.NEW,
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            hash,
            List.of()
        );
    }

    @Test
    void saveAndFindByIdRoundTrip() {
        InMemoryHypothesisRepository repo = new InMemoryHypothesisRepository();
        RuleCandidate c = candidate("hash-1");

        repo.save("h1", c);
        Optional<RuleCandidate> found = repo.findById("h1");

        assertTrue(found.isPresent());
        assertEquals("hash-1", found.get().canonicalHash());
    }

    @Test
    void findByIdReturnsEmptyForUnknown() {
        InMemoryHypothesisRepository repo = new InMemoryHypothesisRepository();
        assertTrue(repo.findById("does-not-exist").isEmpty());
    }

    @Test
    void findAllReturnsAllSaved() {
        InMemoryHypothesisRepository repo = new InMemoryHypothesisRepository();
        repo.save("h1", candidate("hash-1"));
        repo.save("h2", candidate("hash-2"));
        repo.save("h3", candidate("hash-3"));

        List<RuleCandidate> all = repo.findAll();
        assertEquals(3, all.size());
    }

    @Test
    void deleteRemovesEntry() {
        InMemoryHypothesisRepository repo = new InMemoryHypothesisRepository();
        repo.save("h1", candidate("hash-1"));
        repo.delete("h1");

        assertTrue(repo.findById("h1").isEmpty());
        assertTrue(repo.findAll().isEmpty());
    }

    @Test
    void saveOverwritesExistingEntry() {
        InMemoryHypothesisRepository repo = new InMemoryHypothesisRepository();
        repo.save("h1", candidate("hash-old"));
        repo.save("h1", candidate("hash-new"));

        Optional<RuleCandidate> found = repo.findById("h1");
        assertTrue(found.isPresent());
        assertEquals("hash-new", found.get().canonicalHash());
        assertEquals(1, repo.findAll().size());
    }

    @Test
    void findAllIsDefensiveCopy() {
        InMemoryHypothesisRepository repo = new InMemoryHypothesisRepository();
        repo.save("h1", candidate("hash-1"));
        List<RuleCandidate> snapshot = repo.findAll();
        repo.save("h2", candidate("hash-2"));

        assertEquals(1, snapshot.size(),
            "findAll() result must not be affected by subsequent saves");
    }
}
