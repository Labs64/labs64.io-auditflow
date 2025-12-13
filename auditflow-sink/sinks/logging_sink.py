"""
Logging Sink - Simple sink that logs events.

This sink writes audit events to the console/logs.
Useful for debugging and development.
"""
import logging
import json

logger = logging.getLogger(__name__)


def process(event_data: dict, properties: dict) -> dict:
    """
    Process an audit event by logging it.

    Args:
        event_data: The transformed audit event data
        properties: Configuration properties (log-level, format, etc.)

    Returns:
        dict: Processing result information
    """
    # Get log level from properties (default: INFO)
    log_level = properties.get('log-level', 'INFO').upper()
    log_format = properties.get('format', 'json')  # json or text

    # Set up logger level
    numeric_level = getattr(logging, log_level, logging.INFO)
    logger.setLevel(numeric_level)

    # Format the message
    if log_format == 'json':
        message = json.dumps(event_data, indent=2)
    else:
        message = event_data

    # Log the event
    logger.log(numeric_level, f"Audit Event Logged:\n{message}")

    return {
        "logged": True,
        "log_level": log_level,
        "format": log_format,
        "event_id": event_data.get('eventId', 'unknown')
    }
