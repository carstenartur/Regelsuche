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

    private static HypothesisCandidate candidate(String hash) {
        RuleCandidate ruleCandidate = new RuleCandidate(
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
        return HypothesisCandidate.from(ruleCandidate, 1.0).withAssumptions(List.of("b != 0"));
    }

    @Test
    void saveAndFindByIdRoundTrip() {
        InMemoryHypothesisRepository repo = new InMemoryHypothesisRepository();
        HypothesisCandidate c = candidate("hash-1");

        repo.save("h1", c);
        Optional<HypothesisCandidate> found = repo.findById("h1");

        assertTrue(found.isPresent());
        assertEquals("hash-1", found.get().id());
        assertEquals(List.of("b != 0"), found.get().assumptions());
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

        List<HypothesisCandidate> all = repo.findAll();
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

        Optional<HypothesisCandidate> found = repo.findById("h1");
        assertTrue(found.isPresent());
        assertEquals("hash-new", found.get().id());
        assertEquals(1, repo.findAll().size());
    }

    @Test
    void findAllIsDefensiveCopy() {
        InMemoryHypothesisRepository repo = new InMemoryHypothesisRepository();
        repo.save("h1", candidate("hash-1"));
        List<HypothesisCandidate> snapshot = repo.findAll();
        repo.save("h2", candidate("hash-2"));

        assertEquals(1, snapshot.size(),
            "findAll() result must not be affected by subsequent saves");
    }
}
