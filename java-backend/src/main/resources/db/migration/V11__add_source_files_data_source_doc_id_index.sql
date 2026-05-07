CREATE INDEX IF NOT EXISTS idx_source_files_data_source_doc_id
    ON source_files(data_source_id, doc_id);

