CREATE TABLE IF NOT EXISTS search_index_documents (
    document_id TEXT,
    type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    title TEXT NOT NULL DEFAULT '',
    body TEXT NOT NULL DEFAULT '',
    facets JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    search_vector TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce(title, '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(body, '')), 'B')
    ) STORED,
    PRIMARY KEY (type, entity_id)
);

CREATE INDEX IF NOT EXISTS idx_search_index_documents_vector
    ON search_index_documents USING GIN (search_vector);

CREATE INDEX IF NOT EXISTS idx_search_index_documents_facets
    ON search_index_documents USING GIN (facets jsonb_path_ops);

CREATE INDEX IF NOT EXISTS idx_hypothesis_candidates_assumptions
    ON hypothesis_candidates USING GIN (assumptions jsonb_path_ops);

CREATE INDEX IF NOT EXISTS idx_seed_expressions_tags
    ON seed_expressions USING GIN (tags jsonb_path_ops);

CREATE INDEX IF NOT EXISTS idx_seed_expressions_domain
    ON seed_expressions (domain);

CREATE INDEX IF NOT EXISTS idx_benchmark_results_name
    ON benchmark_results (benchmark_name);
