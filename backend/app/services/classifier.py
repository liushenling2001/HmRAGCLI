from pathlib import Path

from app.ai.client import get_ai_provider
from app.ai.prompts import CLASSIFY_DOCUMENT_PROMPT
from app.ai.schemas import DocumentClassificationResult


def classify_document(file_path: str, preview_text: str) -> DocumentClassificationResult:
    path = Path(file_path)
    input_text = "\n".join(
        [
            f"file_name: {path.name}",
            f"file_ext: {path.suffix.lower()}",
            f"title_guess: {path.stem}",
            "content_preview:",
            preview_text[:4000],
        ]
    )
    provider = get_ai_provider()
    try:
        return provider.generate_structured(
            prompt=CLASSIFY_DOCUMENT_PROMPT,
            input_text=input_text,
            schema=DocumentClassificationResult,
        )
    except Exception as exc:
        return fallback_classification(file_path, preview_text, reason=str(exc))


def fallback_classification(file_path: str, preview_text: str, reason: str | None = None) -> DocumentClassificationResult:
    path = Path(file_path)
    suffix = path.suffix.lower()
    preview = (preview_text or "")[:4000]

    if suffix in {".xlsx", ".xls", ".xlsm", ".xltx", ".xltm"}:
        doc_type = "excel"
    elif any(token in path.stem for token in ["制度", "规定", "办法", "细则", "条例"]):
        doc_type = "rule"
    elif any(token in path.stem for token in ["讲话", "发言", "致辞"]):
        doc_type = "speech"
    elif any(token in path.stem for token in ["统计", "公报", "报告"]):
        doc_type = "report"
    elif any(token in preview for token in ["应当", "不得", "必须", "审批", "标准"]):
        doc_type = "rule"
    elif any(token in preview for token in ["强调", "指出", "提出", "要求"]):
        doc_type = "speech"
    elif any(token in preview for token in ["同比", "环比", "增长", "下降", "指标", "统计"]):
        doc_type = "report"
    else:
        doc_type = "unknown"

    reasons = ["ai_fallback"]
    if reason:
        reasons.append(reason[:200])
    return DocumentClassificationResult(
        doc_type=doc_type,
        confidence=0.2,
        reasons=reasons,
        secondary_types=[],
    )


DEV_TITLE_HINTS = [
    "schema",
    "draft",
    "ddl",
    "pydantic",
    "fastapi",
    "api",
    "ingestion-design",
    "knowledge-base-design",
]

DEV_TEXT_HINTS = [
    "```",
    "class ",
    "def ",
    "create table",
    "select ",
    "sqlalchemy",
    "fastapi",
    "pydantic",
    "post /api",
    "get /api",
]


def detect_document_domain(file_path: str, preview_text: str) -> tuple[bool, str]:
    path = Path(file_path)
    title_l = path.stem.lower()
    file_name_l = path.name.lower()
    preview_l = (preview_text or "")[:4000].lower()

    title_hits = sum(1 for token in DEV_TITLE_HINTS if token in title_l or token in file_name_l)
    text_hits = sum(1 for token in DEV_TEXT_HINTS if token in preview_l)

    if path.suffix.lower() in {".sql", ".py", ".yaml", ".yml", ".toml", ".json"}:
        return True, "development"
    if title_hits >= 1 or text_hits >= 2:
        return True, "development"
    return False, "business"
