ALTER TABLE IF EXISTS domain_memory_packs
    ADD COLUMN IF NOT EXISTS structured_content_json JSONB NOT NULL DEFAULT '{}'::jsonb;
