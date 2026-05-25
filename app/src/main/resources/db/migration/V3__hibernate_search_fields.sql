ALTER TABLE hypothesis_candidates
    ADD COLUMN IF NOT EXISTS assumptions_text TEXT NOT NULL DEFAULT '';

ALTER TABLE seed_expressions
    ADD COLUMN IF NOT EXISTS tags_text TEXT NOT NULL DEFAULT '';

ALTER TABLE benchmark_results
    ADD COLUMN IF NOT EXISTS metrics_text TEXT NOT NULL DEFAULT '';

ALTER TABLE export_reports
    ADD COLUMN IF NOT EXISTS body TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS domain TEXT NOT NULL DEFAULT 'general',
    ADD COLUMN IF NOT EXISTS facets JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS facets_text TEXT NOT NULL DEFAULT '';

ALTER TABLE search_index_documents
    ADD COLUMN IF NOT EXISTS document_id TEXT,
    ADD COLUMN IF NOT EXISTS facets_text TEXT NOT NULL DEFAULT '';

UPDATE search_index_documents
SET document_id = type || ':' || entity_id
WHERE document_id IS NULL OR document_id = '';

ALTER TABLE search_index_documents
    ALTER COLUMN document_id SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_search_index_documents_document_id
    ON search_index_documents (document_id);
