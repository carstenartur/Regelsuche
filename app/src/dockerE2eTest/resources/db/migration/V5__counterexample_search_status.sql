ALTER TABLE hypothesis_candidates
    ADD COLUMN IF NOT EXISTS counterexample_status VARCHAR(64),
    ADD COLUMN IF NOT EXISTS counterexample_attempted_sources JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS counterexample_explanation TEXT NOT NULL DEFAULT '';
