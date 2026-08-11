<p align="center"><img src="https://raw.githubusercontent.com/Labs64/.github/refs/heads/master/assets/labs64-io-ecosystem.png"></p>

## Sink Module

This `sink` module is a core component within the AuditFlow system, designed to process, convert, and enrich data streams as they flow through various stages of the application. Its primary function is to adapt data from one format or structure to another, ensuring compatibility and enhancing its utility for subsequent operations like auditing, analysis, or storage.

## Adding a sink

Drop `mysink.py` into `sinks/` (or `sinks_bootstrap/` at runtime) implementing
`process(event_data: dict, properties: dict) -> dict` — see
[DEVELOPERS.md](../DEVELOPERS.md#adding-a-new-sink) for the full walkthrough.

**`extra` is an open map — a sink must honour three invariants** (see e.g.
`auditflow-sink/tests/test_syslog_sink.py`, which exercises exactly this for one sink; there is no
single cross-module contract test on the sink side the way there is for transformers):

1. **Open** — no `extra` key is required.
2. **Absent is absent** — never fabricate `"unknown"` / `"N/A"` for a missing key. A placeholder is
   indistinguishable from a publisher that really sent it.
3. **Nothing is dropped** — a non-promoted key always reaches the destination, via whatever
   metadata channel the destination offers (a map column, structured-log fields, a CEF extension, …).

Promotion itself — `make_transform(extra_promoted=None, module_id=None)` and `transform.promoted` —
is a **transformer-side** mechanism (see
[`auditflow-transformer/README.md`](../auditflow-transformer/README.md#adding-a-transformer)). A
sink has no equivalent: it receives whatever the transformer already produced and does not promote
keys itself.

A **domain** sink may require its own keys — `netlicensing_sink` needs `extra.transaction` —
provided it documents them and gates on `eventType` first.