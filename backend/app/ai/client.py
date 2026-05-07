from app.ai.providers.mock import MockAIProvider
from app.ai.providers.openai_compatible import OpenAICompatibleProvider
from app.core.config import settings


def get_ai_provider():
    provider_name = (settings.ai_provider or "").strip().lower()
    if provider_name in {"openai_compatible", "openai-compatible", "bailian", "dashscope", "vllm"} and settings.ai_base_url and settings.ai_model:
        return OpenAICompatibleProvider(
            base_url=settings.ai_base_url,
            api_key=settings.ai_api_key,
            model=settings.ai_model,
        )
    return MockAIProvider()
