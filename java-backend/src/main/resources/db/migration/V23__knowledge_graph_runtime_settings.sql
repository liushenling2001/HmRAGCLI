CREATE TABLE IF NOT EXISTS graph_runtime_settings (
    setting_key VARCHAR(120) PRIMARY KEY,
    setting_value VARCHAR(200) NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO graph_runtime_settings (setting_key, setting_value, description)
VALUES (
    'auto_build_after_index',
    'false',
    'Whether graph skeleton build jobs are enqueued automatically after indexing finishes.'
)
ON CONFLICT (setting_key) DO NOTHING;
