from pydantic import BaseModel, ConfigDict


class StrictModel(BaseModel):
    """Base model that forbids unknown fields.

    This makes every nested model serialize with `additionalProperties: false`
    in its JSON schema — required by Claude's structured outputs
    (output_config.format), and equally load-bearing on the prompt-embedded
    fallback path, where it lets Pydantic reject fabricated fields.
    """

    model_config = ConfigDict(extra="forbid")
