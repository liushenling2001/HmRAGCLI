from app.schemas.common import APIModel


class AIHealthResponse(APIModel):
    provider: str
    model: str
    ok: bool
    result: dict | None = None
    error: str | None = None
