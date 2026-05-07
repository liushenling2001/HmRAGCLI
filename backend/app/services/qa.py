from __future__ import annotations

from typing import Any

from sqlalchemy.orm import Session

from app.schemas.enums import QueryType
from app.schemas.qa import QAQueryRequest, QAQueryResponse, StructuredAnswerOut
from app.services.search import build_citations, search_for_qa


def _detect_query_type(query: str) -> QueryType:
    if any(token in query for token in ["标准", "规定", "要求", "条件", "可以吗", "怎么办"]):
        return QueryType.rule_qa
    if any(token in query for token in ["讲话", "强调", "重点", "任务"]):
        return QueryType.speech_summary
    if any(token in query for token in ["同比", "环比", "多少", "增长", "数据"]):
        return QueryType.data_qa
    if any(token in query for token in ["对比", "变化", "趋势", "关系", "近年"]):
        return QueryType.comparative_analysis
    return QueryType.document_lookup


def _row_text(row: dict[str, Any]) -> str:
    unit = row.get("unit")
    chunk = row.get("chunk")
    if unit is not None:
        return unit.normalized_text or unit.content
    if chunk is not None:
        return chunk.content
    return ""


def answer_question(db: Session, payload: QAQueryRequest) -> QAQueryResponse:
    query_type = _detect_query_type(payload.query)
    rows = search_for_qa(
        db,
        payload.query,
        top_k=payload.top_k,
        exclude_dev_docs=payload.exclude_dev_docs,
    )

    if not rows and ("标准" in payload.query or "规定" in payload.query):
        rows = search_for_qa(
            db,
            "规定 标准 要求",
            top_k=payload.top_k,
            exclude_dev_docs=payload.exclude_dev_docs,
        )

    if not rows:
        return QAQueryResponse(
            query_type=query_type,
            answer="当前知识库中未检索到直接匹配结果。",
            structured_answer=None,
            citations=[],
        )

    top_row = rows[0]
    top_unit = top_row.get("unit")
    top_chunk = top_row.get("chunk")
    top_text = _row_text(top_row)
    citations = build_citations(rows[:3])

    if query_type == QueryType.rule_qa and top_unit is not None:
        exceptions = (top_unit.fields_json or {}).get("exceptions")
        return QAQueryResponse(
            query_type=query_type,
            answer=top_text,
            structured_answer=StructuredAnswerOut(
                subject=top_unit.subject,
                action=top_unit.action,
                constraint=top_unit.content[:120],
                exception=exceptions[0] if isinstance(exceptions, list) and exceptions else None,
            ),
            citations=citations,
        )

    return QAQueryResponse(
        query_type=query_type,
        answer=top_text,
        structured_answer=StructuredAnswerOut(
            subject=top_unit.subject if top_unit is not None else top_chunk.title if top_chunk is not None else None,
            action=top_unit.action if top_unit is not None else None,
            indicator=top_unit.indicator if top_unit is not None else None,
            value=float(top_unit.value_num) if top_unit is not None and top_unit.value_num is not None else None,
            unit_name=top_unit.unit_name if top_unit is not None else None,
            region=top_unit.region if top_unit is not None else None,
            summary_points=[_row_text(row)[:120] for row in rows[:3]],
        ),
        citations=citations,
    )
