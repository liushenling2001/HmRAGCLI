from __future__ import annotations

import json
import re

import httpx
from pydantic import BaseModel

from app.core.config import settings


class OpenAICompatibleProvider:
    def __init__(self, base_url: str, api_key: str, model: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model

    @staticmethod
    def _extract_json(content: str) -> dict:
        text = content.strip()
        if text.startswith("```"):
            match = re.search(r"```(?:json)?\s*(.*?)\s*```", text, flags=re.DOTALL)
            if match:
                text = match.group(1).strip()
        return json.loads(text)

    def generate_structured(self, prompt: str, input_text: str, schema: type[BaseModel]) -> BaseModel:
        payload = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": prompt},
                {"role": "user", "content": input_text},
            ],
            "response_format": {"type": "json_object"},
        }
        headers = {
            "Content-Type": "application/json",
        }
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"

        timeout = httpx.Timeout(
            connect=min(30.0, float(settings.ai_request_timeout_seconds)),
            read=float(settings.ai_request_timeout_seconds),
            write=min(30.0, float(settings.ai_request_timeout_seconds)),
            pool=min(30.0, float(settings.ai_request_timeout_seconds)),
        )
        last_error: Exception | None = None
        with httpx.Client(timeout=timeout, trust_env=False) as client:
            for attempt in range(max(1, settings.ai_request_max_retries + 1)):
                try:
                    response = client.post(
                        f"{self.base_url}/chat/completions",
                        headers=headers,
                        json=payload,
                    )
                    if response.status_code >= 400:
                        fallback_payload = {
                            "model": self.model,
                            "messages": [
                                {"role": "system", "content": prompt + "\n只返回 JSON，不要输出解释或 Markdown。"},
                                {"role": "user", "content": input_text},
                            ],
                        }
                        response = client.post(
                            f"{self.base_url}/chat/completions",
                            headers=headers,
                            json=fallback_payload,
                        )
                    response.raise_for_status()
                    data = response.json()
                    content = data["choices"][0]["message"]["content"]
                    return schema.model_validate(self._extract_json(content))
                except httpx.TimeoutException as exc:
                    last_error = TimeoutError(
                        f"AI analysis timed out after {settings.ai_request_timeout_seconds}s "
                        f"using model {self.model}"
                    )
                    if attempt >= settings.ai_request_max_retries:
                        break
                except Exception as exc:
                    last_error = exc
                    break
        raise last_error or RuntimeError("AI structured generation failed")

    @staticmethod
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

    def embed_texts(self, texts: list[str], model: str) -> list[list[float]]:
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        try:
            timeout = httpx.Timeout(
                connect=min(30.0, float(settings.embedding_request_timeout_seconds)),
                read=float(settings.embedding_request_timeout_seconds),
                write=min(30.0, float(settings.embedding_request_timeout_seconds)),
                pool=min(30.0, float(settings.embedding_request_timeout_seconds)),
            )
            with httpx.Client(timeout=timeout, trust_env=False) as client:
                response = client.post(
                    f"{self.base_url}/embeddings",
                    headers=headers,
                    json={"model": model, "input": texts},
                )
            response.raise_for_status()
            data = response.json()
            items = sorted(data["data"], key=lambda item: item.get("index", 0))
            return [item["embedding"] for item in items]
        except Exception:
            return self._fallback_embed_texts(texts)
