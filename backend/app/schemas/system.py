from app.schemas.common import APIModel


class LibreOfficeHealthResponse(APIModel):
    configured_path: str
    executable_found: bool
    ok: bool
    version: str | None = None
    sample_doc_path: str | None = None
    converted_docx_path: str | None = None
    error: str | None = None


class ComponentHealthResponse(APIModel):
    name: str
    ok: bool
    detail: dict | None = None
    error: str | None = None


class SystemHealthResponse(APIModel):
    ok: bool
    components: list[ComponentHealthResponse]
