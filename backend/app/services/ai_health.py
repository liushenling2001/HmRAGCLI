from app.ai.client import get_ai_provider
from app.ai.schemas import DocumentClassificationResult
from app.core.config import settings
from app.schemas.ai import AIHealthResponse


def check_ai_health() -> AIHealthResponse:
    provider_name = settings.ai_provider
    model_name = settings.ai_model
    provider = get_ai_provider()
    sample_input = "\n".join(
        [
            "file_name: sample.docx",
            "file_ext: .docx",
            "title_guess: 差旅管理办法",
            "content_preview:",
            "第一条 为规范差旅报销管理，制定本办法。员工出差住宿标准按照规定执行。",
        ]
    )

    try:
        result = provider.generate_structured(
            prompt="将文档分类为 rule/speech/report/excel/notice/minutes/unknown，输出 JSON。",
            input_text=sample_input,
            schema=DocumentClassificationResult,
        )
        return AIHealthResponse(
            provider=provider_name,
            model=model_name,
            ok=True,
            result=result.model_dump(),
            error=None,
        )
    except Exception as exc:
        return AIHealthResponse(
            provider=provider_name,
            model=model_name,
            ok=False,
            result=None,
            error=str(exc),
        )
