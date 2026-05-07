CREATE TABLE IF NOT EXISTS domain_candidate_discovery_state (
    trigger_source VARCHAR(50) PRIMARY KEY,
    cursor_window_start TIMESTAMPTZ,
    cursor_window_end TIMESTAMPTZ,
    last_run_at TIMESTAMPTZ,
    coverage_completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_domain_candidate_discovery_state_last_run_at
    ON domain_candidate_discovery_state(last_run_at DESC);
