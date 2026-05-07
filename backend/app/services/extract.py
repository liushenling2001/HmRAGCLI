from app.models.content import Chunk, KnowledgeUnit
from app.services.ai_extract import build_knowledge_unit as build_knowledge_unit_ai


def build_knowledge_unit(doc_type: str, chunk: Chunk, use_ai: bool = True) -> KnowledgeUnit:
    if use_ai:
        return build_knowledge_unit_ai(doc_type, chunk)
    # Reuse the same builder but force fallback by calling the internal fallback path through a safe wrapper.
    from app.services.ai_extract import _fallback_extract

    return _fallback_extract(doc_type, chunk)

__all__ = ["build_knowledge_unit"]
