# NetLicensing & Payment Gateway events in ClickHouse

A worked example of AuditFlow's **use-case layer**: the NetLicensing API and the Payment Gateway
publish `api.call` audit events, and this layer promotes their fields into ClickHouse columns so
they can be queried as dimensions rather than dug out of a JSON map.

It is deliberately **not** a full analytics model. There is no MRR, retention, cohort or LTV
machinery here — those belong to a reporting system, not to an audit router's example. What this
shows is what a column store buys you over a log line: aggregation, percentiles and funnels over
the audit stream itself.

| | Generic (shipped) | This layer |
|---|---|---|
| Columns | [`schema.sql`](schema.sql) | [`schema-netlicensing.sql`](schema-netlicensing.sql) |
| Promotion | `audit_clickhouse` | [`audit_clickhouse_netlicensing.py`](../netlicensing/audit_clickhouse_netlicensing.py) |
| Vocabulary | `Extra` in the AuditEvent contract | this document |
| Pipeline | `tenants/_platform.yaml` | `tenants/demo.yaml` |

The three files have to agree —
`auditflow-transformer/tests/test_audit_clickhouse_netlicensing.py` fails if they drift.

## Quick start

```bash
just up                     # ClickHouse applies both schema files on first start
just ch-seed                # ~2000 events over 30 days
just ch-stats               # counts by tenant / API method / status
just ch-shell               # interactive clickhouse-client
```

`just ch-seed "--events 20000 --days 90"` for a larger stream, `--seed 7` for a reproducible one.

## Event shape

Every event follows the spec's universal template: `eventType` is always `api.call`, the endpoint
goes in `extra.actionMethod`, and everything else is promoted out of `extra` into a column.

```json
{
    "eventId": "74ae6d35-2b2a-429c-b309-2096d304c63c",
    "eventTime": "2026-08-12T05:31:13.074Z",
    "correlationId": "RX97EHN2CFU469M6Y",
    "eventType": "api.call",
    "sourceSystem": "netlicensing/core",
    "tenantId": "V00001611",
    "extra": {
        "actionMethod": "licensee/validate",
        "actionStatus": "SUCCESS",
        "statusCode": 200,
        "licenseeNumber": "IMONITORING",
        "productNumber": "PMONITORING",
        "isDryRun": true,
        "validationOutcome": "EXPIRING_SOON",
        "validUntil": "2026-09-01T00:00:00Z"
    }
}
```

`correlationId` is what ties a multi-step operation together — the whole payment lifecycle below
shares one, so a stuck payment is traceable with a single `WHERE`.

## Field vocabulary

Promoted by the **generic** layer, available to every deployment: `userId`, `actionName`,
`actionStatus`, `actionMessage`, `sessionId`, `durationMs`, `responseStatus`. Documented in the
`Extra` schema of the AuditEvent contract, not here.

Promoted by **this** layer:

### Standard API call

| `extra` key | Column | Meaning |
|---|---|---|
| `actionMethod` | `action_method` | The API endpoint called (`licensee/validate`, `payments/pay`). |
| `statusCode` | `status_code` | HTTP or internal status code. |

> `action_method` sits alongside the core `action_name`, and `status_code` alongside
> `response_status`, rather than replacing them. They are different granularities — the wire
> endpoint vs. the semantic action — and an event normally fills one pair or the other. If your
> publisher only ever emits API calls, querying `action_method` is enough.

### Licensing & validation

| `extra` key | Column | Meaning |
|---|---|---|
| `licenseeNumber` | `licensee_number` | The customer the event is *about*. Distinct from `user_id`, the actor who performed it. |
| `productNumber` | `product_number` | Product the call concerns. |
| `moduleNumber` | `module_number` | Product module. Mapped from `productModuleNumber`. |
| `nodeId` | `node_id` | Device a node-locked or floating license is bound to. Mapped from `nodeSecret`. |
| `isDryRun` | `is_dry_run` | Validation probe that consumes and binds nothing. Exclude it from usage metrics. |
| `validationArgs` | `validation_args` | Multi-module validations, kept as the serialized JSON the publisher sent. |
| `validationOutcome` | `validation_outcome` | The licensing verdict: `VALID`, `EXPIRING_SOON`, `EXPIRED`, `GRACE_PERIOD`, `INVALID`. |
| `validUntil` | `valid_until` | When the validated license lapses. |

`validationOutcome` is separate from `actionStatus` on purpose: a validation that correctly
reports an expired license is a **SUCCESS**ful API call with an **EXPIRED** outcome. It is also
how expirations and renewal warnings become queryable at all — NetLicensing returns them in the
`licensee/validate` response, so there is no `licensee/expire` event to count instead.

### Payment Gateway

| `extra` key | Column | Meaning |
|---|---|---|
| `transactionNumber` | `transaction_number` | Payment/order reference; shared by every step of one lifecycle. |
| `paymentMethod` | `payment_method` | Resolved from `paymentProviderId` (`stripe`, `paypal`, …). |
| `grossAmount` / `netAmount` / `taxAmount` | `gross_amount` / `net_amount` / `tax_amount` | `purchaseOrder` totals, stored as `Decimal(18,4)` — never Float. |
| `currency` | `currency` | The currency the amount was **billed in**. |
| `customerCountry` | `customer_country` | From `billingInfo.country`. |
| `billingPeriod` | `billing_period` | From `recurrence.expression` (`P1M` → `MONTH`). |

> **Amounts are in mixed currencies.** `sum(gross_amount)` across the whole table adds euros to
> dollars. Always group by `currency`, or have the publisher emit a converted amount. This example
> deliberately stops short of a reporting-currency model.

### Payment lifecycle methods

`payments/create` · `payments/pay` · `payments/close` · `payments/delete` ·
`checkout-sessions/confirmation` · `checkout-sessions/return` · `checkout-sessions/cancel` ·
`webhooks/receive` · `payment-transactions/get` · `payment-transactions/list`

## Queries

Everything below runs against the seeded dataset. Paste into `just ch-shell`, the ClickHouse Play
UI at http://localhost:8123/play, or `just ch "<SQL>"`.

### API traffic and health

Which endpoints carry the load, and which of them are failing:

```sql
SELECT action_method,
       count()                            AS calls,
       countIf(action_status = 'FAILURE') AS failures,
       round(100 * failures / calls, 2)   AS failure_pct,
       round(quantile(0.50)(duration_ms)) AS p50_ms,
       round(quantile(0.95)(duration_ms)) AS p95_ms,
       round(quantile(0.99)(duration_ms)) AS p99_ms
FROM audit_events
WHERE action_method != ''
GROUP BY action_method
ORDER BY calls DESC
LIMIT 20;
```

Failures broken down by status code — where `status_code` earns its typed column:

```sql
SELECT status_code, action_method, count() AS failures
FROM audit_events
WHERE action_status = 'FAILURE' AND status_code >= 400
GROUP BY status_code, action_method
ORDER BY failures DESC
LIMIT 20;
```

Daily call volume per subsystem. `event_time` is the business time — grouping on `timestamp`
would move a backfill into today:

```sql
SELECT toDate(event_time) AS day, source_system, count() AS calls
FROM audit_events
GROUP BY day, source_system
ORDER BY day DESC, calls DESC
LIMIT 30;
```

### Licensing & validation

Validation volume and outcome mix, excluding dry runs — a dry run consumes nothing, and counting
it as real usage overstates every consumption number:

```sql
SELECT validation_outcome,
       count()                                              AS validations,
       uniqExactIf(licensee_number, licensee_number != '')  AS licensees
FROM audit_events
WHERE action_method = 'licensee/validate' AND is_dry_run IS NULL
GROUP BY validation_outcome
ORDER BY validations DESC;
```

**Who is about to expire** — the renewal-warning query, and the reason `validation_outcome` and
`valid_until` exist.

A licensee's current state is whatever its **most recent** validation reported, so this cannot
filter on `validation_outcome` in the `WHERE` clause: a three-week-old `EXPIRING_SOON` row has a
`valid_until` that is now in the past, and matching on it would report licenses that already
lapsed — or were renewed since. `argMax` picks the value from the latest event per licensee, and
the `HAVING` filters on that instead:

```sql
SELECT licensee_number,
       argMax(validation_outcome, event_time) AS latest_outcome,
       argMax(valid_until, event_time)        AS expires_at,
       dateDiff('day', now(), expires_at)     AS days_left,
       max(event_time)                        AS last_validated
FROM audit_events
WHERE action_method = 'licensee/validate' AND valid_until IS NOT NULL
GROUP BY licensee_number
HAVING latest_outcome IN ('EXPIRING_SOON', 'GRACE_PERIOD', 'EXPIRED')
   AND days_left BETWEEN -30 AND 30
ORDER BY days_left
LIMIT 25;
```

Busiest licensees, and how many distinct devices each is running on. Both `uniq` states are
conditional — an absent `String` column stores `''`, and counting the empty string as a distinct
value reports "1 node" for every licensee that has none:

```sql
SELECT licensee_number,
       count()                                  AS validations,
       uniqExactIf(node_id, node_id != '')      AS nodes,
       uniqExactIf(product_number, product_number != '') AS products
FROM audit_events
WHERE action_method = 'licensee/validate'
GROUP BY licensee_number
ORDER BY validations DESC
LIMIT 20;
```

Reading into the multi-module payloads without a column per module — `validation_args` is a JSON
string, and ClickHouse queries into it directly:

```sql
SELECT JSONExtractString(arrayJoin(JSONExtractArrayRaw(validation_args)),
                         'productModuleNumber') AS module,
       count() AS validations
FROM audit_events
WHERE validation_args != ''
GROUP BY module
ORDER BY validations DESC
LIMIT 15;
```

### Payments

The payment funnel — how far transactions get, per step:

```sql
SELECT action_method,
       uniqExact(transaction_number) AS transactions
FROM audit_events
WHERE action_method LIKE 'payments/%' OR action_method LIKE 'checkout-sessions/%'
GROUP BY action_method
ORDER BY transactions DESC;
```

Payments per month, **grouped by currency** so nothing is summed across them. `FINAL` deduplicates
the ReplacingMergeTree — cheap here because payments are the low-volume events:

```sql
SELECT toStartOfMonth(event_time) AS month,
       currency,
       count()                    AS payments,
       round(sum(gross_amount), 2) AS gross,
       round(sum(tax_amount), 2)   AS tax,
       uniqExactIf(licensee_number, licensee_number != '') AS paying_customers
FROM audit_events FINAL
WHERE action_method = 'payments/pay' AND action_status = 'SUCCESS'
GROUP BY month, currency
ORDER BY month DESC, gross DESC;
```

Where the money comes from, and how it is paid. `customer_country` is the **declared billing
country** — `geo_country_code` is derived from the request IP, which for a server-to-server API is
the vendor's infrastructure, not the customer's location:

```sql
SELECT customer_country, currency, payment_method,
       count()                     AS payments,
       round(sum(gross_amount), 2) AS gross
FROM audit_events FINAL
WHERE action_method = 'payments/pay' AND action_status = 'SUCCESS'
GROUP BY customer_country, currency, payment_method
ORDER BY gross DESC
LIMIT 20;
```

Trace one transaction end to end. Every step of a lifecycle shares a `correlation_id`, so this is
the query you run when a payment is stuck:

```sql
SELECT event_time, action_method, action_status, status_code, duration_ms
FROM audit_events
WHERE correlation_id = (
    SELECT correlation_id FROM audit_events
    WHERE action_method = 'payments/close' ORDER BY event_time DESC LIMIT 1
)
ORDER BY event_time;
```

One-off vs. recurring revenue, without a subscription model — `billing_period` is enough to split
them:

```sql
SELECT billing_period, currency,
       count()                     AS payments,
       round(sum(gross_amount), 2) AS gross,
       round(avg(gross_amount), 2) AS avg_ticket
FROM audit_events FINAL
WHERE action_method = 'payments/pay' AND action_status = 'SUCCESS'
GROUP BY billing_period, currency
ORDER BY gross DESC;
```

## Notes

- **Aggregate on `event_time`, not `timestamp`.** `timestamp` is when AuditFlow received the
  event; `event_time` is when the action happened. The table is partitioned, sorted and expired on
  `event_time` for exactly that reason.
- **Use `FINAL` when exactness matters.** The table is a `ReplacingMergeTree` and AuditFlow is
  at-least-once, so a replayed DLQ can insert a second copy. Deduplication happens on merge.
- **Absent is absent.** A missing `String` key stores `''` and a missing scalar stores `NULL` —
  the transformer never fabricates a placeholder. Guard `uniq`/`count` with a `!= ''` condition
  wherever the column is optional.
- **A half-applied layer still works.** `clickhouse_sink` inserts with
  `input_format_skip_unknown_fields=1`, so running only `schema.sql` drops these keys at insert
  time instead of dead-lettering the event.

## Modelling your own domain

Copy the trio and replace the contents:

1. `schema-netlicensing.sql` → your `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` script.
2. `audit_clickhouse_netlicensing.py` → `make_transform({your extra key: your column})`.
3. This document → your field vocabulary, so publishers can discover the keys.

Point a tenant pipeline at your module id (`tenants/demo.yaml`) and mount the directory into the
transformer's `transformers_bootstrap` path. The module id is what selects a vocabulary, so two
pipelines can write different column subsets to the same table.
