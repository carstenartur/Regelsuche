package de.regelsuche.persistence.relational;

import de.regelsuche.mining.HypothesisRepository;
import jakarta.persistence.EntityManagerFactory;
import java.util.Optional;

public final class RelationalPersistenceAdapters implements AutoCloseable {
    private final EntityManagerFactory entityManagerFactory;
    private final HibernateHypothesisRepository hypotheses;
    private final HibernateEntityRepository<DiscoveryExperimentEntity> experiments;
    private final HibernateEntityRepository<SearchRunEntity> searchRuns;
    private final HibernateEntityRepository<ProofJobMetadataEntity> proofJobs;
    private final HibernateEntityRepository<ExportReportEntity> reports;
    private final HibernateEntityRepository<SeedExpressionEntity> seeds;
    private final HibernateEntityRepository<BenchmarkResultEntity> benchmarks;
    private final HibernateEntityRepository<CounterexampleEntity> counterexamples;
    private final FacetedSearchIndex searchIndex;

    private RelationalPersistenceAdapters(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
        this.hypotheses = new HibernateHypothesisRepository(entityManagerFactory);
        this.experiments = new HibernateEntityRepository<>(entityManagerFactory, DiscoveryExperimentEntity.class);
        this.searchRuns = new HibernateEntityRepository<>(entityManagerFactory, SearchRunEntity.class);
        this.proofJobs = new HibernateEntityRepository<>(entityManagerFactory, ProofJobMetadataEntity.class);
        this.reports = new HibernateEntityRepository<>(entityManagerFactory, ExportReportEntity.class);
        this.seeds = new HibernateEntityRepository<>(entityManagerFactory, SeedExpressionEntity.class);
        this.benchmarks = new HibernateEntityRepository<>(entityManagerFactory, BenchmarkResultEntity.class);
        this.counterexamples = new HibernateEntityRepository<>(entityManagerFactory, CounterexampleEntity.class);
        this.searchIndex = new HibernateSearchFacetedSearchIndex(entityManagerFactory);
    }

    public static RelationalPersistenceAdapters of(EntityManagerFactory entityManagerFactory) {
        return new RelationalPersistenceAdapters(entityManagerFactory);
    }

    public Optional<HypothesisRepository> hypotheses() { return Optional.of(hypotheses); }
    public HibernateEntityRepository<DiscoveryExperimentEntity> experiments() { return experiments; }
    public HibernateEntityRepository<SearchRunEntity> searchRuns() { return searchRuns; }
    public HibernateEntityRepository<ProofJobMetadataEntity> proofJobs() { return proofJobs; }
    public HibernateEntityRepository<ExportReportEntity> reports() { return reports; }
    public HibernateEntityRepository<SeedExpressionEntity> seeds() { return seeds; }
    public HibernateEntityRepository<BenchmarkResultEntity> benchmarks() { return benchmarks; }
    public HibernateEntityRepository<CounterexampleEntity> counterexamples() { return counterexamples; }
    public FacetedSearchIndex searchIndex() { return searchIndex; }

    @Override
    public void close() {
        if (entityManagerFactory.isOpen()) {
            entityManagerFactory.close();
        }
    }
}
