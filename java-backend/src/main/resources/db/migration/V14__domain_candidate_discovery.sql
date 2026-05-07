CREATE TABLE IF NOT EXISTS domain_candidates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    keywords_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence_refs_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_snapshot_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(50) NOT NULL DEFAULT 'suggested',
    trigger_source VARCHAR(50) NOT NULL DEFAULT 'auto',
    review_note TEXT,
    accepted_domain_id UUID REFERENCES domain_definitions(id) ON DELETE SET NULL,
    discovery_window_start TIMESTAMPTZ,
    discovery_window_end TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_domain_candidates_status
    ON domain_candidates(status);

CREATE INDEX IF NOT EXISTS idx_domain_candidates_trigger_source
    ON domain_candidates(trigger_source);

CREATE INDEX IF NOT EXISTS idx_domain_candidates_created_at
    ON domain_candidates(created_at DESC);
