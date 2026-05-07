from app.ai.schemas import DocumentClassificationResult


class MockAIProvider:
    def generate_structured(
        self,
        prompt: str,
        input_text: str,
        schema: type[DocumentClassificationResult],
    ) -> DocumentClassificationResult:
        lowered = input_text.lower()
        if ".xlsx" in lowered or ".xls" in lowered:
            return schema(doc_type="excel", confidence=0.99, reasons=["file extension indicates spreadsheet"])
        if any(token in lowered for token in ["讲话", "发言", "致辞", "speech"]):
            return schema(doc_type="speech", confidence=0.88, reasons=["content resembles speech material"])
        if any(token in lowered for token in ["制度", "规定", "办法", "条例", "细则", "rule"]):
            return schema(doc_type="rule", confidence=0.88, reasons=["content resembles formal rules"])
        if any(token in lowered for token in ["统计", "公报", "报告", "同比", "环比", "gdp", "report"]):
            return schema(doc_type="report", confidence=0.82, reasons=["content contains report indicators"])
        if any(token in lowered for token in ["通知", "公告", "通告", "notice"]):
            return schema(doc_type="notice", confidence=0.8, reasons=["content resembles notice"])
        if any(token in lowered for token in ["纪要", "会议", "minutes"]):
            return schema(doc_type="minutes", confidence=0.78, reasons=["content resembles meeting minutes"])
        return schema(doc_type="unknown", confidence=0.2, reasons=["insufficient signal"])

    def embed_texts(self, texts: list[str], model: str) -> list[list[float]]:
        vectors: list[list[float]] = []
        dims = 32
        for text in texts:
            vec = [0.0] * dims
            for i, ch in enumerate(text[:2048]):
                vec[i % dims] += (ord(ch) % 97) / 97.0
            norm = sum(v * v for v in vec) ** 0.5 or 1.0
            vectors.append([v / norm for v in vec])
        return vectors
