<p align="center"><img src="https://raw.githubusercontent.com/Labs64/.github/refs/heads/master/assets/labs64-io-ecosystem.png"></p>

## Sink Module

This `sink` module is a core component within the AuditFlow system, designed to process, convert, and enrich data streams as they flow through various stages of the application. Its primary function is to adapt data from one format or structure to another, ensuring compatibility and enhancing its utility for subsequent operations like auditing, analysis, or storage.

## Adding a sink

Drop `mysink.py` into `sinks/` (or `sinks_bootstrap/` at runtime) implementing
`process(event_data: dict, properties: dict) -> dict` — see
[DEVELOPERS.md](../DEVELOPERS.md#adding-a-new-sink) for the full walkthrough.

**`extra` is an open map — four invariants** (`auditflow-transformer/tests/test_extra_contract.py`
enforces them across every module in `transformers/`, so a new transformer is covered on arrival):

1. **Open** — no `extra` key is required; the well-known 7 are a convention, not a schema.
2. **Absent is absent** — never fabricate `"unknown"` / `"N/A"` / `level: "UNKNOWN"` for a missing
   key. A placeholder is indistinguishable from a publisher that really sent it.
3. **Nothing is dropped** — a non-promoted key always reaches the sink via its metadata channel.
4. **Uniform extension** — every promoting transformer exposes
   `make_transform(extra_promoted=None, module_id=None)` and `transform.promoted`.

These bind the **generic** modules. A **domain** module may require its own keys (`netlicensing_sink`
needs `extra.transaction`) provided it documents them and gates on `eventType` first.