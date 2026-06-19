"""Pass-through transformer: returns the input event unchanged."""

__version__ = "1.0.0"

PROPERTIES = {}


def transform(input_data: dict) -> dict:
    """
    This is the 'zero' transformation.
    It performs no transformation and simply returns the input data as is.
    """
    return input_data
