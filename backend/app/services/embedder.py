from __future__ import annotations

from datetime import UTC, datetime
from math import sqrt
import httpx

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.content import Chunk
from app.core.config import settings
from app.models.content import KnowledgeUnit
from app.models.embedding import ChunkEmbedding, KnowledgeUnitEmbedding


def _http_client() -> httpx.Client:
    # Local model endpoints should bypass shell/proxy environment variables.
    timeout = httpx.Timeout(
        connect=min(30.0, float(settings.embedding_request_timeout_seconds)),
        read=float(settings.embedding_request_timeout_seconds),
        write=min(30.0, float(settings.embedding_request_timeout_seconds)),
        pool=min(30.0, float(settings.embedding_request_timeout_seconds)),
    )
    return httpx.Client(timeout=timeout, trust_env=False)


def _embedding_text(unit: KnowledgeUnit) -> str:
    parts = [
        unit.title or "",
        unit.subject or "",
        unit.indicator or "",
        unit.normalized_text or "",
        unit.content or "",
    ]
    return "\n".join(part for part in parts if part).strip()[:6000]


def _chunk_embedding_text(chunk: Chunk) -> str:
    parts = [
        chunk.title or "",
        chunk.chunk_type or "",
        chunk.content or "",
    ]
    return "\n".join(part for part in parts if part).strip()[:6000]


def _fallback_embed_texts(texts: list[str]) -> list[list[float]]:
    vectors: list[list[float]] = []
    dims = 32
    for text in texts:
        vec = [0.0] * dims
        for i, ch in enumerate(text[:2048]):
            vec[i % dims] += (ord(ch) % 97) / 97.0
        norm = sum(v * v for v in vec) ** 0.5 or 1.0
        vectors.append([v / norm for v in vec])
    return vectors


def _truncation_candidates(text: str) -> list[str]:
    limits = [1800, 1400, 1000, 700]
    candidates = [text]
    for limit in limits:
        if len(text) > limit:
            candidates.append(text[:limit])
    seen: set[str] = set()
    ordered: list[str] = []
    for item in candidates:
        if item not in seen:
            seen.add(item)
            ordered.append(item)
    return ordered


def _openai_embed_texts(texts: list[str]) -> list[list[float]]:
    headers = {"Content-Type": "application/json"}
    if settings.embedding_api_key:
        headers["Authorization"] = f"Bearer {settings.embedding_api_key}"
    with _http_client() as client:
        response = client.post(
            f"{settings.embedding_base_url.rstrip('/')}/embeddings",
            headers=headers,
            json={"model": settings.embedding_model, "input": texts},
        )
    response.raise_for_status()
    data = response.json()
    items = sorted(data["data"], key=lambda item: item.get("index", 0))
    return [item["embedding"] for item in items]


def _ollama_embed_texts(texts: list[str]) -> list[list[float]]:
    base = settings.embedding_base_url.rstrip("/")
    with _http_client() as client:
        try:
            response = client.post(
                f"{base}/api/embed",
                json={"model": settings.embedding_model, "input": texts},
            )
            response.raise_for_status()
            data = response.json()
            embeddings = data.get("embeddings")
            if embeddings:
                return embeddings
        except Exception:
            pass

        vectors = []
        for text in texts:
            vector = None
            last_error: Exception | None = None
            for candidate in _truncation_candidates(text):
                try:
                    response = client.post(
                        f"{base}/api/embeddings",
                        json={"model": settings.embedding_model, "prompt": candidate},
                    )
                    response.raise_for_status()
                    data = response.json()
                    if "embedding" in data:
                        vector = data["embedding"]
                        break
                    raise ValueError("Ollama embedding response missing 'embedding'")
                except Exception as exc:
                    last_error = exc
            if vector is None:
                raise last_error or ValueError("Ollama embedding request failed")
            vectors.append(vector)
        return vectors


def _embed_texts(texts: list[str]) -> list[list[float]]:
    provider = (settings.embedding_provider or "").strip().lower()
    try:
        if provider in {"ollama"} and settings.embedding_base_url:
            return _ollama_embed_texts(texts)
        if provider in {"openai_compatible", "openai-compatible"} and settings.embedding_base_url:
            return _openai_embed_texts(texts)
    except Exception:
        return _fallback_embed_texts(texts)
    return _fallback_embed_texts(texts)


def embed_knowledge_units(db: Session, units: list[KnowledgeUnit]) -> None:
    if not units:
        return
    texts = [_embedding_text(unit) for unit in units]
    vectors = _embed_texts(texts)
    now = datetime.now(UTC)
    for unit, vector in zip(units, vectors, strict=False):
        row = db.scalar(
            select(KnowledgeUnitEmbedding).where(KnowledgeUnitEmbedding.knowledge_unit_id == unit.id)
        )
        if row is None:
            row = KnowledgeUnitEmbedding(
                knowledge_unit_id=unit.id,
                embedding_model=settings.embedding_model,
                dimensions=len(vector),
                vector_json=vector,
                vector=vector if len(vector) == 768 else None,
                created_at=now,
                updated_at=now,
            )
            db.add(row)
        else:
            row.embedding_model = settings.embedding_model
            row.dimensions = len(vector)
            row.vector_json = vector
            row.vector = vector if len(vector) == 768 else None
            row.updated_at = now


def embed_chunks(db: Session, chunks: list[Chunk]) -> None:
    if not chunks:
        return
    texts = [_chunk_embedding_text(chunk) for chunk in chunks]
    vectors = _embed_texts(texts)
    now = datetime.now(UTC)
    for chunk, vector in zip(chunks, vectors, strict=False):
        row = db.scalar(
            select(ChunkEmbedding).where(ChunkEmbedding.chunk_id == chunk.id)
        )
        if row is None:
            row = ChunkEmbedding(
                chunk_id=chunk.id,
                embedding_model=settings.embedding_model,
                dimensions=len(vector),
                vector_json=vector,
                vector=vector if len(vector) == 768 else None,
                created_at=now,
                updated_at=now,
            )
            db.add(row)
        else:
            row.embedding_model = settings.embedding_model
            row.dimensions = len(vector)
            row.vector_json = vector
            row.vector = vector if len(vector) == 768 else None
            row.updated_at = now


def cosine_similarity(vec1: list[float], vec2: list[float]) -> float:
    if not vec1 or not vec2 or len(vec1) != len(vec2):
        return 0.0
    dot = sum(a * b for a, b in zip(vec1, vec2, strict=False))
    norm1 = sqrt(sum(a * a for a in vec1)) or 1.0
    norm2 = sqrt(sum(b * b for b in vec2)) or 1.0
    return dot / (norm1 * norm2)


def embed_query(text: str) -> list[float]:
    vectors = _embed_texts([text])
    return vectors[0] if vectors else []
