"""Benchmark sink: accepts every event, writes nothing."""
__version__ = "1.0.0"
PROPERTIES = {}


def process(event_data, properties):
    return {"ok": True}


def process_batch(events, properties):
    return [{"ok": True} for _ in events]
