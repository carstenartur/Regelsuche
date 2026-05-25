package de.regelsuche.persistence.relational;

import de.regelsuche.mining.HypothesisCandidate;
import de.regelsuche.mining.HypothesisRepository;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Optional;

public final class HibernateHypothesisRepository implements HypothesisRepository {
    private final HibernateEntityRepository<HypothesisCandidateEntity> entities;

    public HibernateHypothesisRepository(EntityManagerFactory entityManagerFactory) {
        this.entities = new HibernateEntityRepository<>(entityManagerFactory, HypothesisCandidateEntity.class);
    }

    @Override
    public void save(String hypothesisId, HypothesisCandidate hypothesis) {
        if (hypothesisId == null || hypothesisId.isBlank()) {
            throw new IllegalArgumentException("hypothesisId must not be blank");
        }
        if (hypothesis == null) {
            throw new IllegalArgumentException("hypothesis must not be null");
        }
        if (!hypothesisId.equals(hypothesis.id())) {
            throw new IllegalArgumentException("hypothesisId must match hypothesis.id()");
        }
        entities.save(HypothesisCandidateEntity.from(hypothesis));
    }

    @Override
    public Optional<HypothesisCandidate> findById(String hypothesisId) {
        return entities.findById(hypothesisId).map(HypothesisCandidateEntity::toHypothesisCandidate);
    }

    @Override
    public List<HypothesisCandidate> findAll() {
        return entities.findAll().stream().map(HypothesisCandidateEntity::toHypothesisCandidate).toList();
    }

    @Override
    public void delete(String hypothesisId) {
        entities.delete(hypothesisId);
    }
}
