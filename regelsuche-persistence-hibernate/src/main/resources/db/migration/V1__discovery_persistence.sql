CREATE TABLE IF NOT EXISTS search_runs (
    id TEXT PRIMARY KEY,
    source_expression TEXT NOT NULL,
    target_expression TEXT NOT NULL DEFAULT '',
    strategy TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'CREATED',
    visited_states INTEGER NOT NULL DEFAULT 0 CHECK (visited_states >= 0),
    frontier_size INTEGER NOT NULL DEFAULT 0 CHECK (frontier_size >= 0),
    best_path_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS discovery_experiments (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'DRAFT',
    search_run_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS hypothesis_candidates (
    id TEXT PRIMARY KEY,
    experiment_id TEXT REFERENCES discovery_experiments(id) ON DELETE SET NULL,
    left_pattern TEXT NOT NULL,
    right_pattern TEXT NOT NULL,
    assumptions JSONB NOT NULL DEFAULT '[]'::jsonb,
    proof_status TEXT NOT NULL DEFAULT 'OBSERVED',
    counterexample_found BOOLEAN,
    novelty_score DOUBLE PRECISION NOT NULL DEFAULT 0.0 CHECK (novelty_score >= 0.0 AND novelty_score <= 1.0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS counterexamples (
    id TEXT PRIMARY KEY,
    hypothesis_id TEXT NOT NULL REFERENCES hypothesis_candidates(id) ON DELETE CASCADE,
    input_expression TEXT NOT NULL DEFAULT '',
    expected_expression TEXT NOT NULL DEFAULT '',
    actual_expression TEXT NOT NULL DEFAULT '',
    assumptions JSONB NOT NULL DEFAULT '[]'::jsonb,
    found_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS benchmark_results (
    id TEXT PRIMARY KEY,
    experiment_id TEXT REFERENCES discovery_experiments(id) ON DELETE SET NULL,
    benchmark_name TEXT NOT NULL,
    duration_millis BIGINT NOT NULL DEFAULT 0 CHECK (duration_millis >= 0),
    solved_count INTEGER NOT NULL DEFAULT 0 CHECK (solved_count >= 0),
    total_count INTEGER NOT NULL DEFAULT 0 CHECK (total_count >= 0),
    quality_score DOUBLE PRECISION NOT NULL DEFAULT 0.0 CHECK (quality_score >= 0.0 AND quality_score <= 1.0),
    measured_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS seed_expressions (
    id TEXT PRIMARY KEY,
    expression TEXT NOT NULL,
    domain TEXT NOT NULL DEFAULT 'general',
    difficulty TEXT NOT NULL DEFAULT 'unknown',
    tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS export_reports (
    id TEXT PRIMARY KEY,
    experiment_id TEXT REFERENCES discovery_experiments(id) ON DELETE SET NULL,
    title TEXT NOT NULL,
    format TEXT NOT NULL DEFAULT 'markdown',
    storage_uri TEXT NOT NULL DEFAULT '',
    referenced_search_run_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS proof_job_metadata (
    id TEXT PRIMARY KEY,
    hypothesis_id TEXT REFERENCES hypothesis_candidates(id) ON DELETE SET NULL,
    prover TEXT NOT NULL DEFAULT 'unknown',
    status TEXT NOT NULL DEFAULT 'QUEUED',
    artifact_uri TEXT NOT NULL DEFAULT '',
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);
