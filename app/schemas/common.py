from pydantic import BaseModel, ConfigDict


class StrictModel(BaseModel):
    """Base model that forbids unknown fields.

    This makes every nested model serialize with `additionalProperties: false`
    in its JSON schema, which Claude's structured-outputs feature requires.
    """

    model_config = ConfigDict(extra="forbid")
