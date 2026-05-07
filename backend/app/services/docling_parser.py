from __future__ import annotations

import re
from pathlib import Path

from app.services.parser import ParsedChunk


class DoclingUnavailableError(RuntimeError):
    pass


def _split_markdown(text: str, max_chars: int = 2200) -> list[str]:
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    blocks = [block.strip() for block in normalized.split("\n\n") if block.strip()]
    chunks: list[str] = []
    current = ""
    for block in blocks:
        if len(current) + len(block) + 2 <= max_chars:
            current = f"{current}\n\n{block}".strip()
        else:
            if current:
                chunks.append(current)
            if len(block) <= max_chars:
                current = block
            else:
                start = 0
                while start < len(block):
                    chunks.append(block[start : start + max_chars])
                    start += max_chars
                current = ""
    if current:
        chunks.append(current)
    return chunks


def _infer_chunk_type(block: str) -> tuple[str, str | None]:
    lines = [line.strip() for line in block.splitlines() if line.strip()]
    title = None
    if lines and lines[0].startswith("#"):
        title = lines[0].lstrip("#").strip() or None
    if re.search(r"!\[[^\]]*\]\(", block):
        return "figure", title
    if any("|" in line for line in lines[:5]):
        return "table", title
    return "text", title


def parse_with_docling(path: Path) -> list[ParsedChunk]:
    try:
        from docling.document_converter import DocumentConverter
    except ModuleNotFoundError as exc:
        raise DoclingUnavailableError(
            "Docling is not installed in the current Python environment. Prepare and install it offline before enabling ENHANCED_PARSER_BACKEND=docling."
        ) from exc

    try:
        converter = DocumentConverter()
        result = converter.convert(str(path))
        markdown = result.document.export_to_markdown()
    except Exception as exc:
        raise DoclingUnavailableError(f"Docling conversion failed: {exc}") from exc

    chunks: list[ParsedChunk] = []
    for idx, block in enumerate(_split_markdown(markdown), start=1):
        chunk_type, title = _infer_chunk_type(block)
        chunks.append(
            ParsedChunk(
                content=block,
                chunk_type=chunk_type,
                title=title,
                metadata={
                    "enhanced_parser": "docling",
                    "chunk_index": idx,
                    "source_name": Path(path).name,
                },
            )
        )
    return chunks
