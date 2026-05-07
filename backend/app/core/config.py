from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

PROJECT_ROOT = Path(__file__).resolve().parents[3]


class Settings(BaseSettings):
    app_name: str = "HmRAGCLI API"
    app_version: str = "0.1.0"
    api_prefix: str = "/api/v1"
    postgres_host: str = "localhost"
    postgres_port: int = 5432
    postgres_db: str = "hmrag"
    postgres_user: str = "postgres"
    postgres_password: str = ""
    ai_provider: str = "mock"
    ai_base_url: str = ""
    ai_api_key: str = ""
    ai_model: str = "qwen2.5-3b-instruct"
    ai_request_timeout_seconds: int = 300
    ai_request_max_retries: int = 1
    ai_extract_chunk_limit: int = 8
    async_enhance_after_ingest: bool = True
    ingest_build_max_workers: int = 3
    stale_processing_seconds: int = 180
    enhancement_worker_poll_seconds: float = 2.0
    enhancement_queue_max_pending: int = 12
    enhancement_queue_fill_batch_size: int = 4
    embedding_chunk_limit_per_file: int = 120
    pdf_preview_page_limit: int = 5
    pdf_parse_timeout_seconds: int = 45
    embedding_provider: str = "fallback"
    embedding_base_url: str = ""
    embedding_api_key: str = ""
    embedding_model: str = "text-embedding-v1"
    embedding_request_timeout_seconds: int = 300
    libreoffice_path: str = "soffice"
    parse_mode: str = "auto"
    enhanced_parser_backend: str = "none"
    enhanced_pdf_size_kb: int = 512
    enhanced_docx_size_kb: int = 256
    search_keyword_weight: float = 0.7
    search_unit_vector_weight: float = 0.8
    search_unit_vector_boost: float = 0.4
    search_chunk_keyword_weight: float = 0.65
    search_chunk_vector_weight: float = 0.85
    search_chunk_vector_boost: float = 0.45
    search_exact_phrase_boost: float = 0.9
    search_title_hit_boost: float = 0.35
    search_filename_hit_boost: float = 0.2
    search_summary_hit_boost: float = 0.18
    search_caption_hit_boost: float = 0.22
    search_doc_dev_title_penalty: float = 0.18
    search_doc_dev_text_penalty: float = 0.06
    search_doc_code_block_penalty: float = 0.08
    search_doc_table_penalty: float = 0.05
    search_doc_link_penalty: float = 0.03
    search_doc_max_noise_penalty: float = 0.4
    search_doc_min_noise_penalty: float = -0.08
    search_query_ascii_miss_penalty: float = 0.85
    search_query_non_ascii_miss_penalty: float = 0.12
    search_query_hit_bonus: float = 0.05
    search_query_max_hit_bonus: float = 0.12

    @property
    def database_url(self) -> str:
        return (
            f"postgresql+psycopg://{self.postgres_user}:{self.postgres_password}"
            f"@{self.postgres_host}:{self.postgres_port}/{self.postgres_db}"
        )

    model_config = SettingsConfigDict(
        env_file=str(PROJECT_ROOT / ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
    )


settings = Settings()
