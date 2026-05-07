from __future__ import annotations

import re
from decimal import Decimal, InvalidOperation

from app.ai.client import get_ai_provider
from app.ai.prompts import (
    EXTRACT_EXCEL_PROMPT,
    EXTRACT_GENERIC_PROMPT,
    EXTRACT_REPORT_PROMPT,
    EXTRACT_RULE_PROMPT,
    EXTRACT_SPEECH_PROMPT,
)
from app.ai.schemas import KnowledgeUnitExtractionResult
from app.models.content import Chunk, KnowledgeUnit


NUMBER_PATTERN = re.compile(r"(\d+(?:\.\d+)?)\s*(元/晚|元|亿元|万元|%|人|家)?")
CODE_HINT_PATTERN = re.compile(r"(```|class\s+\w+|def\s+\w+|from\s+\w+\s+import|POST\s+/api/|GET\s+/api/)", re.IGNORECASE)
TIME_PATTERN = re.compile(r"(\d{4}年(?:\d{1,2}月)?|\d{4}-\d{2}(?:-\d{2})?)")
REGION_PATTERN = re.compile(r"((?:[\u4e00-\u9fa5]{2,12})(?:省|市|区|县))")
CONDITION_PATTERN = re.compile(r"(符合[^，。；]{2,60}|经[^，。；]{2,40}(?:批准|同意)|在[^，。；]{2,60}情况下)")
CONSTRAINT_PATTERN = re.compile(r"((?:不超过|不得超过|不得|必须|应当|原则上不高于)[^，。；]{2,80})")
EXCEPTION_PATTERN = re.compile(r"(特殊情况[^，。；]{0,60}|例外情况[^，。；]{0,60}|确需[^，。；]{0,60})")


def _prompt_for_doc_type(doc_type: str) -> str:
    if doc_type == "rule":
        return EXTRACT_RULE_PROMPT
    if doc_type == "speech":
        return EXTRACT_SPEECH_PROMPT
    if doc_type in {"report", "notice", "minutes"}:
        return EXTRACT_REPORT_PROMPT
    if doc_type == "excel":
        return EXTRACT_EXCEL_PROMPT
    return EXTRACT_GENERIC_PROMPT


def _normalize_text(text: str | None, max_len: int = 300) -> str | None:
    if not text:
        return None
    normalized = re.sub(r"\s+", " ", text).strip()
    return normalized[:max_len] if normalized else None


def _looks_like_code_or_schema(text: str | None) -> bool:
    if not text:
        return False
    return bool(CODE_HINT_PATTERN.search(text))


def _parse_numeric_value(doc_type: str, value_text: str | None, fallback_text: str) -> tuple[Decimal | None, str | None]:
    if doc_type not in {"rule", "report", "excel", "notice", "minutes"}:
        return None, None
    if _looks_like_code_or_schema(fallback_text):
        return None, None
    haystack = value_text or fallback_text
    match = NUMBER_PATTERN.search(haystack)
    if not match:
        return None, None
    try:
        value_num = Decimal(match.group(1))
    except InvalidOperation:
        value_num = None
    return value_num, match.group(2)


def _ensure_fields(doc_type: str, fields: dict, chunk: Chunk, normalized_text: str | None) -> dict:
    merged = dict(fields or {})
    if _looks_like_code_or_schema(chunk.content):
        return merged
    if doc_type == "rule":
        merged.setdefault("conditions", [])
        merged.setdefault("constraints", [])
        merged.setdefault("exceptions", [])
        if not merged["constraints"] and normalized_text:
            if any(token in normalized_text for token in ["不得", "不超过", "必须", "应当"]):
                merged["constraints"] = [normalized_text[:160]]
    elif doc_type == "speech":
        merged.setdefault("tasks", [])
        merged.setdefault("stance", normalized_text or chunk.title or "")
    elif doc_type in {"report", "notice", "minutes"}:
        merged.setdefault("dimensions", {})
        if "%" in chunk.content and "trend" not in merged:
            merged["trend"] = "contains_percentage"
    elif doc_type == "excel":
        merged.setdefault("dimensions", {})
        merged.setdefault("metrics", {})
        if chunk.title and "sheet_name" not in merged:
            merged["sheet_name"] = chunk.title
    return merged


def _extract_rule_fields(text: str, fields: dict) -> dict:
    merged = dict(fields)
    conditions = merged.get("conditions") or []
    constraints = merged.get("constraints") or []
    exceptions = merged.get("exceptions") or []

    for pattern, target in [
        (CONDITION_PATTERN, conditions),
        (CONSTRAINT_PATTERN, constraints),
        (EXCEPTION_PATTERN, exceptions),
    ]:
        for match in pattern.findall(text):
            cleaned = match.strip()
            if cleaned and cleaned not in target:
                target.append(cleaned[:120])

    merged["conditions"] = conditions
    merged["constraints"] = constraints
    merged["exceptions"] = exceptions
    if "approval_required" not in merged:
        merged["approval_required"] = any(token in text for token in ["批准", "审批", "同意"])
    return merged


def _extract_report_fields(text: str, fields: dict, indicator: str | None) -> dict:
    merged = dict(fields)
    dimensions = dict(merged.get("dimensions") or {})
    time_match = TIME_PATTERN.search(text)
    region_match = REGION_PATTERN.search(text)
    if time_match and "time" not in dimensions:
        dimensions["time"] = time_match.group(1)
    if region_match and "region" not in dimensions:
        dimensions["region"] = region_match.group(1)
    if indicator and "indicator" not in merged:
        merged["indicator"] = indicator
    if "yoy" not in merged and "同比" in text:
        yoy_match = re.search(r"同比(?:增长|下降)?\s*(\d+(?:\.\d+)?)\s*%", text)
        if yoy_match:
            merged["yoy"] = yoy_match.group(1)
    if "mom" not in merged and "环比" in text:
        mom_match = re.search(r"环比(?:增长|下降)?\s*(\d+(?:\.\d+)?)\s*%", text)
        if mom_match:
            merged["mom"] = mom_match.group(1)
    merged["dimensions"] = dimensions
    return merged


def _extract_excel_fields(text: str, fields: dict, title: str | None) -> dict:
    merged = dict(fields)
    dimensions = dict(merged.get("dimensions") or {})
    metrics = dict(merged.get("metrics") or {})

    if title and "sheet_name" not in merged:
        merged["sheet_name"] = title

    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if lines:
        header = lines[0]
        if "|" in header and "headers" not in merged:
            merged["headers"] = [part.strip() for part in header.split("|") if part.strip()][:20]
    if len(lines) > 1:
        sample_row = lines[1]
        parts = [part.strip() for part in sample_row.split("|") if part.strip()]
        if parts:
            for idx, part in enumerate(parts[:6], start=1):
                if re.search(r"\d", part):
                    metrics[f"col_{idx}"] = part
                else:
                    dimensions[f"col_{idx}"] = part

    merged["dimensions"] = dimensions
    merged["metrics"] = metrics
    return merged


def _postprocess_fields(
    *,
    doc_type: str,
    text: str,
    fields: dict,
    title: str | None,
    indicator: str | None,
) -> dict:
    if doc_type == "rule":
        return _extract_rule_fields(text, fields)
    if doc_type in {"report", "notice", "minutes"}:
        return _extract_report_fields(text, fields, indicator)
    if doc_type == "excel":
        return _extract_excel_fields(text, fields, title)
    return fields


def _build_knowledge_unit(
    *,
    doc_type: str,
    chunk: Chunk,
    unit_type: str,
    title: str | None,
    normalized_text: str | None,
    subject: str | None,
    action: str | None,
    organization: str | None,
    person: str | None,
    region: str | None,
    time_expr: str | None,
    indicator: str | None,
    value_text: str | None,
    unit_name: str | None,
    status: str,
    priority: int,
    confidence: float,
    fields: dict,
) -> KnowledgeUnit:
    parsed_value, parsed_unit = _parse_numeric_value(doc_type, value_text, chunk.content)
    final_unit_name = unit_name or parsed_unit
    final_normalized = normalized_text or _normalize_text(chunk.content)
    final_fields = _ensure_fields(doc_type, fields, chunk, final_normalized)
    final_unit_type = unit_type
    if _looks_like_code_or_schema(chunk.content):
        final_unit_type = "summary"
        parsed_value = None
        final_unit_name = None
    else:
        final_fields = _postprocess_fields(
            doc_type=doc_type,
            text=chunk.content,
            fields=final_fields,
            title=title or chunk.title,
            indicator=indicator,
        )
    return KnowledgeUnit(
        doc_id=chunk.doc_id,
        chunk_id=chunk.id,
        unit_type=final_unit_type,
        title=title or chunk.title,
        content=chunk.content,
        normalized_text=final_normalized,
        subject=subject or chunk.title,
        action=action,
        organization=organization,
        person=person,
        region=region,
        time_expr=time_expr,
        event_date=None,
        indicator=indicator,
        value_num=parsed_value,
        value_text=value_text,
        unit_name=final_unit_name,
        effective_date=None,
        expiry_date=None,
        status=status,
        priority=priority,
        confidence=confidence,
        source_span=f"chunk:{chunk.chunk_no}",
        source_page=chunk.page_no,
        fields_json=final_fields,
    )


def _fallback_extract(doc_type: str, chunk: Chunk) -> KnowledgeUnit:
    unit_type = "summary"
    action = None
    indicator = None
    if doc_type == "rule":
        unit_type = "rule"
        action = "规定"
    elif doc_type == "speech":
        unit_type = "statement"
        action = "强调"
    elif doc_type in {"report", "notice", "minutes"}:
        unit_type = "fact"
        if "%" in chunk.content or "同比" in chunk.content or "环比" in chunk.content:
            indicator = "统计指标"
    elif doc_type == "excel":
        unit_type = "table_record"
        indicator = chunk.title or "表格记录"
    return _build_knowledge_unit(
        doc_type=doc_type,
        chunk=chunk,
        unit_type=unit_type,
        title=chunk.title,
        normalized_text=_normalize_text(chunk.content),
        subject=chunk.title,
        action=action,
        organization=None,
        person=None,
        region=None,
        time_expr=None,
        indicator=indicator,
        value_text=None,
        unit_name=None,
        status="reference",
        priority=0,
        confidence=0.6,
        fields=chunk.metadata_json or {},
    )


def build_knowledge_unit(doc_type: str, chunk: Chunk) -> KnowledgeUnit:
    provider = get_ai_provider()
    input_text = "\n".join(
        [
            f"doc_type: {doc_type}",
            f"chunk_title: {chunk.title or ''}",
            f"chunk_type: {chunk.chunk_type}",
            "chunk_content:",
            chunk.content[:4000],
        ]
    )
    try:
        result = provider.generate_structured(
            prompt=_prompt_for_doc_type(doc_type),
            input_text=input_text,
            schema=KnowledgeUnitExtractionResult,
        )
        return _build_knowledge_unit(
            doc_type=doc_type,
            chunk=chunk,
            unit_type=result.unit_type or "summary",
            title=result.title or chunk.title,
            normalized_text=_normalize_text(result.normalized_text) or _normalize_text(chunk.content),
            subject=result.subject,
            action=result.action,
            organization=result.organization,
            person=result.person,
            region=result.region,
            time_expr=result.time_expr,
            indicator=result.indicator,
            value_text=result.value_text,
            unit_name=result.unit_name,
            status=result.status or "reference",
            priority=result.priority,
            confidence=result.confidence or 0.0,
            fields=result.fields or {},
        )
    except Exception:
        return _fallback_extract(doc_type, chunk)
