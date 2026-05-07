from typing import Protocol, TypeVar

from pydantic import BaseModel

SchemaT = TypeVar("SchemaT", bound=BaseModel)


class AIProvider(Protocol):
    def generate_structured(self, prompt: str, input_text: str, schema: type[SchemaT]) -> SchemaT:
        ...
