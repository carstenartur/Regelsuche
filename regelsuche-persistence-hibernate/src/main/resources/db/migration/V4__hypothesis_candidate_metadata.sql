ALTER TABLE hypothesis_candidates
    ADD COLUMN IF NOT EXISTS supporting_paths JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS supporting_expressions JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS parameter_relations JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS expression_placeholders JSONB NOT NULL DEFAULT '[]'::jsonb;
