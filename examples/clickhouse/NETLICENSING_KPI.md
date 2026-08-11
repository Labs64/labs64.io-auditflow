# NetLicensing → AuditFlow → ClickHouse → Dashboard

The reference model for building per-tenant licensing and monetization KPIs on the AuditFlow
audit stream:

```
NetLicensing ──POST /audit/publish──▶ AuditFlow ──audit_clickhouse──▶ clickhouse_sink
                                                                          │
                                          audit.audit_events ◀────────────┘
                                                    │
                                    NetLicensing Dashboard / Grafana
```

Two halves, and they have to agree:

1. **[What NetLicensing emits](#event-catalog)** — the event catalog. A KPI is only as good as the
   event that feeds it; almost every wrong licensing dashboard is wrong because the emitter left a
   field out, not because the query was bad.
2. **[How the dashboard queries it](#kpi-query-book)** — the query book, one entry per KPI.

AuditFlow itself is domain-free — nothing below is baked into the product. Licensing is a
**use-case layer**, three files that extend the generic audit pipeline and have to agree with each
other:

| Layer | File |
|---|---|
| Columns | [`schema-netlicensing.sql`](schema-netlicensing.sql) — `ALTER TABLE` on top of the core [`schema.sql`](schema.sql), plus the `ops_daily` rollup |
| Promotion | [`../netlicensing/audit_clickhouse_netlicensing.py`](../netlicensing/audit_clickhouse_netlicensing.py) — the generic `audit_clickhouse` transformer plus the key map [below](#field-vocabulary) |
| Vocabulary | this document |

`auditflow-transformer/tests/test_audit_clickhouse_netlicensing.py` fails if any of the three
drifts from the others. To model a different domain, copy the trio rather than editing the core.

The generic audit-semantics keys every bundled transformer already promotes (`userId`,
`actionName`, `actionStatus`, `actionMessage`, `sessionId`, `durationMs`, `responseStatus`) are
published in the `Extra` schema of
[`openapi-audit-v1.yaml`](../../auditflow-api/src/main/resources/openapi/openapi-audit-v1.yaml) and
are used throughout this document without being repeated in the table below.

> Every query below is written for a single vendor: `WHERE tenant_id = 'demo'`. Replace `'demo'`
> with the vendor's tenant id — or with `$tenant` in a Grafana dashboard variable. `tenant_id` is
> the leading column of the sorting key, so this filter is what makes every query cheap.

**Contents:** [Quick Start](#quick-start) ·
[Six decisions](#six-decisions-that-make-or-break-the-report) ·
[Field vocabulary](#field-vocabulary) · [Event catalog](#event-catalog) ·
[KPI query book](#kpi-query-book) ([Sales](#sales) · [Demographic](#demographic) ·
[Operations](#operations)) · [Integration notes](#integration-notes) · [Grafana](#grafana)

---

## Quick Start

Four steps to confirm the whole layer works — transformer, schema, and data — before relying on
anything in the reference sections below. Takes a couple of minutes; assumes the stack is already
running (`just up` from the repo root — see [DEVELOPERS.md](../../DEVELOPERS.md#quick-start) if
not).

**1. Seed a synthetic licensing business**
```bash
just ch-seed
```
Publishes ~3,000 events for a coherent business — 40 customers over 800 days: created, subscribed,
billed monthly, upgraded, downgraded, churned, and validating licenses throughout. Delivery is
asynchronous, so wait a few seconds, then check it landed:
```bash
just ch "SELECT count() FROM audit_events WHERE tenant_id = 'demo'"
```
Expect a number in the thousands. (A completely fresh `just clean && just up` seeds exactly 3,018;
running `just ch-seed` again on top of other `demo` activity just adds to whatever's already
there — re-running it is always safe, since its event IDs are stable UUID v5 values that
AuditFlow deduplicates. See the docstring in `seed_kpi_dashboards.py`.)

> **Gotcha:** if you manually `TRUNCATE TABLE audit_events` to force a clean slate instead of
> `just clean`, a re-seed will publish successfully but **land zero rows** — AuditFlow's
> idempotency store still remembers those exact event IDs as already delivered, so the consumer
> silently skips them. Restart the backend (`docker compose restart backend`) or use
> `just clean && just up` to clear that state before re-seeding.

**2. Run the first KPI query — current MRR**
```bash
just ch "SELECT sum(mrr_delta) AS mrr, anyIf(base_currency, base_currency != '') AS currency
         FROM audit_events FINAL WHERE tenant_id = 'demo' AND mrr_delta IS NOT NULL"
```
Expect one row: a positive number, currency `EUR`. If it's empty, the `clickhouse-analytics`
pipeline in `tenants/demo.yaml` isn't pointed at the NetLicensing transformer — check that
`transformer.name: audit_clickhouse_netlicensing`.

**3. Confirm the field vocabulary landed, not just the core columns**
```bash
just ch "SELECT licensee_number, product_number, licensing_model, base_amount, base_currency
         FROM audit_events WHERE tenant_id = 'demo' AND event_type = 'payment.succeeded' LIMIT 1"
```
Expect a row with real values in every column. An empty result here — with step 2 fine — usually
means `schema-netlicensing.sql` was never applied: init scripts only run on an empty ClickHouse
data dir, so `just clean && just up` after adding or editing it. See
[DEVELOPERS.md](../../DEVELOPERS.md#clickhouse-analytics-sink) for the schema-layering model.

**4. Open it in Grafana** *(optional)*
```bash
just up obs   # if the observability overlay isn't already running
```
Then jump to [Grafana](#grafana) below and paste in any query from the
[KPI query book](#kpi-query-book).

From here, the rest of this document is reference: the six decisions behind the schema, the full
field vocabulary, the event catalog a publisher implements against, and the query book every
dashboard panel is built from.

---

## Six decisions that make or break the report

These are the choices that separate a KPI report you can put in front of a customer from one that
quietly disagrees with the billing system. They are baked into the schema and the queries; read
them before changing either.

### 1. Group on `event_time`, never on `timestamp`

`timestamp` is when **AuditFlow received** the event. `event_time` is when the **business action
happened**. They differ whenever there is a broker backlog, a retry, a DLQ replay, or a backfill —
and a backfill of two years of history would otherwise land every euro of it in today's bucket.

The table is partitioned and sorted on `event_time` for exactly this reason. `timestamp` is kept
for forensics ("what did we ingest at 09:00?") and has its own minmax index.

**NetLicensing must set `eventTime`** to the business time of the action. If it is omitted,
AuditFlow falls back to the receipt time, and every historical report becomes a report about when
the integration happened to run.

### 2. Sum `base_amount`, never `gross_amount`

`gross_amount` is in `currency` — the currency the customer was billed in. Summing a column that
mixes EUR and USD produces a number with no unit. `base_amount` is the same charge converted to the
tenant's reporting currency **at the rate that applied at the time of the transaction**, which
NetLicensing froze into `fx_rate`.

Freezing the rate at event time is deliberate: a report for Q1 must not change because the euro
moved in Q3.

Use `gross_amount` only when you are also grouping by `currency` (e.g. a per-currency payments
breakdown). Everything else — revenue by product, LTV, deal size, YoY — uses `base_amount`.

### 3. MRR is a running sum of `mrr_delta`, not a sum of payments

"Payments received this month" is cash, not MRR. An annual subscription paid up front is one
payment of 1200 and an MRR of 100. A mid-month upgrade is a partial charge but a full MRR step.

So NetLicensing emits `mrrDelta`: the **signed change to normalized monthly recurring revenue**
this event causes, in base currency. MRR on any date is then `sum(mrr_delta)` over everything up to
that date — one scan, no point-in-time reconstruction of subscription state.

Only the emitter can compute this correctly, because only it knows the billing period, the
proration and the plan change. Rules:

| Event | `mrrDelta` |
|---|---|
| `subscription.started` | `+` normalized monthly amount |
| `subscription.renewed` | `0` / absent — a renewal at the same price does not change MRR |
| `subscription.upgraded` | `+` (new monthly − old monthly) |
| `subscription.downgraded` | `−` (old monthly − new monthly) |
| `subscription.paused` | `−` full monthly amount |
| `subscription.resumed` | `+` full monthly amount |
| `subscription.cancelled` | absent — cancellation is *intent*; revenue runs to `periodEnd` |
| `subscription.expired` | `−` full monthly amount — this is the actual churn moment |
| `payment.*` | absent — payments are cash. The subscription events own the MRR movement; a recurring charge that also carried `mrrDelta` would count the same revenue twice. |

Splitting `cancelled` (intent) from `expired` (effect) is what keeps churn from being counted twice
or counted early. Both are needed: cancellations are the leading indicator, expirations are the
number that moves MRR.

### 4. `actionStatus` and `entityStatus` are different things

`actionStatus` is the outcome of the **call**: `SUCCESS`, `FAILURE`, `DENIED`. `entityStatus` is
the state the **entity** is in afterwards: `ACTIVE`, `SUSPENDED`, `EXPIRED`, `CANCELLED`, `DELETED`.

A successful API call can suspend a licensee. Collapsing the two makes both unusable — the licensee
status report starts reading `SUCCESS`, and the API error-rate report starts counting suspensions.

### 5. Demographics come from `customer_country`, not from geolocation

`geo_*` is derived from the request IP. For a server-to-server licensing API that IP is the
**vendor's** infrastructure, so "customers by country" built on it is a map of the vendor's
datacenters. NetLicensing emits the declared/billing country as `customerCountry`.

Keep the geo columns anyway — they are the right answer for a different question (where validation
traffic physically comes from, which is a piracy/abuse signal).

### 6. Revenue is read with `FINAL`

AuditFlow is at-least-once with a ~24h idempotency window. A DLQ replayed after that window inserts
a second copy. `audit_events` is a `ReplacingMergeTree` keyed on `event_id`, so the duplicate is
collapsed — but only on merge, which means exact numbers need `FINAL` at query time.

Money queries below therefore use `FINAL`; it is cheap because payments are a tiny fraction of the
stream. The high-volume operational metrics read the `ops_daily` rollup instead and skip `FINAL` —
a duplicated validation is noise, and a materialized view is an insert trigger that never sees the
deduplication anyway.

---

## Field vocabulary

The `extra` keys this use case adds, and the column each is promoted into. They are additive: a
publisher may send any of them, an event carries only the ones it is about, and a key with no
matching column (or a deployment that never applied `schema-netlicensing.sql`) is dropped at
insert time rather than failing delivery. Promotion is by literal key match — a misspelled key
silently degrades to an untyped entry in the `extra` map column.

These keys are a **layer on top of** the generic AuditFlow contract, not part of it — NetLicensing
is one worked example of modeling a domain, not a set of reserved names. The generic half is the
`Extra` schema of
[`openapi-audit-v1.yaml`](../../auditflow-api/src/main/resources/openapi/openapi-audit-v1.yaml):
its seven well-known keys are what every bundled transformer promotes regardless of domain: nothing
below extends or narrows that contract.

### Customer

| Key | Column | Meaning |
|---|---|---|
| `licenseeNumber` | `licensee_number` | The customer the event is **about** — as distinct from `userId`, the actor who performed it. |
| `customerType` | `customer_type` | `B2B`, `B2C`, … |
| `customerCountry` | `customer_country` | Declared/billing country (ISO 3166-1 alpha-2). Prefer this over IP-derived `geolocation` for demographics — for a server-to-server API the request IP is the vendor's infrastructure. |
| `customerSegment` | `customer_segment` | Vendor-defined segment, e.g. `enterprise`, `smb`. |
| `acquisitionChannel` | `acquisition_channel` | How the customer was acquired, e.g. `direct`, `marketplace`, `reseller`. |
| `resellerNumber` | `reseller_number` | Reseller/partner attributed to the transaction. |

### Product & licensing

| Key | Column | Meaning |
|---|---|---|
| `productNumber` | `product_number` | Product the event concerns. |
| `moduleNumber` | `module_number` | Product module the event concerns. |
| `licenseNumber` | `license_number` | The individual license. |
| `licenseTemplateNumber` | `license_template_number` | License template — carries the price and license type, so it is the dimension that separates a re-pricing from a volume change. |
| `licenseType` | `license_type` | e.g. `FEATURE`, `TIMEVOLUME`, `FLOATING`, `QUANTITY`, `SUBSCRIPTION`. |
| `licensingModel` | `licensing_model` | e.g. `TryAndBuy`, `Rental`, `Subscription`, `Floating`, `NodeLocked`, `PricingTable`, `MultiFeature`, `PayPerUse`, `Quota`. |
| `nodeId` | `node_id` | Node/device a floating or node-locked license is bound to. |

### Entity state

| Key | Column | Meaning |
|---|---|---|
| `entityStatus` | `entity_status` | State of the subject entity **after** this event: `ACTIVE`, `SUSPENDED`, `EXPIRED`, `CANCELLED`, `DELETED`, … Not the same thing as `actionStatus` — see [decision 4](#4-actionstatus-and-entitystatus-are-different-things). |
| `entityStatusPrev` | `entity_status_prev` | State before this event; makes transitions queryable without a self-join. |

### Commerce

| Key | Column | Meaning |
|---|---|---|
| `transactionNumber` | `transaction_number` | Payment/order transaction reference. |
| `subscriptionNumber` | `subscription_number` | Subscription the event belongs to. |
| `paymentMethod` | `payment_method` | e.g. `card`, `paypal`, `invoice`. |
| `quantity` | `quantity` | Number of units/seats (integer). |
| `grossAmount` | `gross_amount` | Amount charged including tax, in `currency`. |
| `netAmount` | `net_amount` | Amount excluding tax. |
| `discountAmount` | `discount_amount` | Discount applied. |
| `taxAmount` | `tax_amount` | Tax component. |
| `currency` | `currency` | ISO 4217 code of the transaction currency. |
| `baseAmount` | `base_amount` | `grossAmount` converted to the tenant's reporting currency **at the rate that applied when the transaction happened**. Required for any cross-currency total to be meaningful — see [decision 2](#2-sum-base_amount-never-gross_amount). |
| `baseCurrency` | `base_currency` | ISO 4217 code of the reporting currency. |
| `fxRate` | `fx_rate` | Rate used for that conversion; frozen at event time so historical reports stay reproducible. |

Amounts land in `Decimal(18, 4)` columns; never sum money as a float. All amounts are **positive** —
refunds and chargebacks are negated by the `revenue_signed` alias.

### Recurring revenue

| Key | Column | Meaning |
|---|---|---|
| `mrrDelta` | `mrr_delta` | Signed change to normalized **monthly** recurring revenue caused by this event, in base currency — see [decision 3](#3-mrr-is-a-running-sum-of-mrr_delta-not-a-sum-of-payments) for the per-event rules. |
| `billingPeriod` | `billing_period` | Period `grossAmount` covers: `DAY`, `WEEK`, `MONTH`, `YEAR`, `ONE_TIME`. |
| `billingPeriodCount` | `billing_period_count` | Number of those periods, e.g. `3` for quarterly (integer). |
| `periodStart` | `period_start` | Start of the service period this charge covers (ISO 8601). |
| `periodEnd` | `period_end` | End of that period (ISO 8601). |
| `isTrial` | `is_trial` | Whether the subscription is in trial (boolean). |

---

## Event catalog

What NetLicensing should publish. Columns:

- **Required** — without these the event is not usable for its KPIs.
- **Recommended** — adds a dimension or a KPI; cheap to include.

Every event additionally carries the envelope: `eventType`, `sourceSystem` (`netlicensing/core`),
`tenantId` (the vendor number), `eventId` (stable UUID — this is what makes retries idempotent),
`eventTime`, and optionally `correlationId`.

Start with the ★ rows. They cover every KPI on the sales/demographic/operations list; the rest add
resolution.

### Customer lifecycle

| `eventType` | Emitted when | Required `extra` | Recommended |
|---|---|---|---|
| ★ `licensee.created` | A licensee is created | `licenseeNumber`, `entityStatus` | `customerType`, `customerCountry`, `customerSegment`, `acquisitionChannel`, `resellerNumber`, `productNumber` |
| `licensee.updated` | Licensee attributes change | `licenseeNumber` | the changed attributes, `entityStatus` |
| ★ `licensee.status.changed` | Active ⇄ suspended ⇄ closed | `licenseeNumber`, `entityStatus`, `entityStatusPrev` | `actionMessage` (reason) |
| `licensee.deleted` | Licensee removed | `licenseeNumber`, `entityStatus: DELETED` | |

*Powers:* customers growth, customers by country/type/segment, licensee status (NLIC-1399),
churn denominators, LTV cohorts.

### Product & catalog (low volume, high explanatory value)

| `eventType` | Emitted when | Required `extra` | Recommended |
|---|---|---|---|
| `product.created` / `.updated` / `.deleted` | Product catalog changes | `productNumber`, `entityStatus` | `actionMessage` |
| `product_module.created` / `.updated` / `.deleted` | Module changes | `productNumber`, `moduleNumber`, `entityStatus` | `licensingModel` |
| `license_template.created` / `.updated` / `.deleted` | Price or template changes | `productNumber`, `moduleNumber`, `licenseTemplateNumber`, `licenseType` | `grossAmount`, `currency`, `billingPeriod` |

*Powers:* annotating revenue charts with pricing changes — the difference between "revenue dropped"
and "revenue dropped because we re-priced PROD-B on the 14th". Volume is negligible; emit them.

### Licensing

| `eventType` | Emitted when | Required `extra` | Recommended |
|---|---|---|---|
| ★ `license.created` | A license is issued | `licenseeNumber`, `licenseNumber`, `productNumber`, `licenseTemplateNumber`, `licenseType`, `entityStatus` | `moduleNumber`, `licensingModel`, `quantity`, `transactionNumber` |
| `license.updated` | License changes | `licenseNumber`, `entityStatus` | changed attributes |
| `license.expired` | A license lapses | `licenseeNumber`, `licenseNumber`, `entityStatus: EXPIRED` | `productNumber` |
| `license.deleted` | A license is removed | `licenseeNumber`, `licenseNumber`, `entityStatus: DELETED` | |

*Powers:* licenses per day, licenses per type/product/module, active license base.

### Subscription & recurring revenue

| `eventType` | Emitted when | Required `extra` | Recommended |
|---|---|---|---|
| ★ `subscription.started` | New recurring subscription | `licenseeNumber`, `subscriptionNumber`, `mrrDelta`, `baseCurrency`, `billingPeriod`, `entityStatus: ACTIVE` | `productNumber`, `moduleNumber`, `licenseTemplateNumber`, `licenseType`, `licensingModel`, `quantity`, `periodStart`, `periodEnd`, `isTrial` |
| `subscription.renewed` | Period rolls over | `subscriptionNumber`, `periodStart`, `periodEnd` | `licenseeNumber`, `transactionNumber` |
| `subscription.upgraded` | Plan/seats up | `subscriptionNumber`, `mrrDelta` (+), `baseCurrency` | `licenseeNumber`, `quantity`, `licenseTemplateNumber` |
| `subscription.downgraded` | Plan/seats down | `subscriptionNumber`, `mrrDelta` (−), `baseCurrency` | as above |
| `subscription.paused` / `.resumed` | Suspension | `subscriptionNumber`, `mrrDelta`, `baseCurrency`, `entityStatus` | `licenseeNumber` |
| ★ `subscription.cancelled` | Cancellation **requested** | `licenseeNumber`, `subscriptionNumber`, `entityStatus: CANCELLED`, `periodEnd` | `actionMessage` (churn reason) |
| ★ `subscription.expired` | Subscription actually ends | `licenseeNumber`, `subscriptionNumber`, `mrrDelta` (−), `baseCurrency`, `entityStatus: EXPIRED` | `productNumber` |
| `subscription.trial.started` / `.converted` / `.expired` | Trial funnel | `licenseeNumber`, `subscriptionNumber`, `isTrial` | `mrrDelta` on `.converted` |

*Powers:* MRR and every derivative (net new MRR, expansion/contraction, gross & net revenue
retention, churn rate), daily subscribers added/lost, trial conversion.

### Money

| `eventType` | Emitted when | Required `extra` | Recommended |
|---|---|---|---|
| ★ `payment.succeeded` | Payment captured | `licenseeNumber`, `transactionNumber`, `grossAmount`, `currency`, `baseAmount`, `baseCurrency`, `fxRate`, `actionStatus: SUCCESS` | `netAmount`, `taxAmount`, `discountAmount`, `paymentMethod`, `productNumber`, `moduleNumber`, `licenseType`, `licensingModel`, `subscriptionNumber`, `billingPeriod`, `periodStart`, `periodEnd`, `resellerNumber` |
| ★ `payment.refunded` | Refund issued | `licenseeNumber`, `transactionNumber`, `grossAmount`, `currency`, `baseAmount`, `baseCurrency` | `productNumber`, `actionMessage` (reason) |
| `payment.chargeback` | Chargeback | same as refund | |
| `payment.failed` | Payment attempt fails | `licenseeNumber`, `transactionNumber`, `actionStatus: FAILURE`, `actionMessage` | `paymentMethod`, `subscriptionNumber` — the dunning / involuntary-churn signal |

Amounts are always **positive**. Refunds and chargebacks are made negative by the `revenue_signed`
alias, so a net figure is a plain `sum(revenue_signed)`.

*Powers:* net payments, revenue per day/product/module/license type, average deal size, LTV, YoY,
refund rate, involuntary churn.

### Operations

| `eventType` | Emitted when | Required `extra` | Recommended |
|---|---|---|---|
| ★ `validation.requested` | Every license validation | `productNumber`, `actionStatus` | `licenseeNumber`, `moduleNumber`, `licenseType`, `licensingModel`, `sessionId`, `nodeId`, `durationMs`, `actionMessage` (deny reason) |
| ★ `api.call` | Every REST API call | `actionName` (operation), `actionStatus`, `responseStatus` | `licenseeNumber`, `productNumber`, `durationMs`, `userId` |
| `session.started` / `.heartbeat` / `.ended` | Floating-license sessions | `productNumber`, `sessionId` | `licenseeNumber`, `moduleNumber`, `nodeId` |
| `auth.login` / `auth.login.failed` | Vendor console login | `userId`, `actionStatus` | `actionMessage` |
| `quota.exceeded` | Quota/limit hit | `licenseeNumber`, `productNumber`, `actionMessage` | `licenseType` |

*Powers:* validation and API request counts, requests per day per product (NLIC-2113), active
sessions per product (NLIC-2113), latency percentiles, deny-reason breakdown.

> `validation.requested` and `api.call` are the high-volume events — typically 100–10 000× the
> payment stream. They are what `ops_daily` exists for. If ingest volume is a concern, sample them
> (and record the sampling rate in `extra`) rather than dropping fields.

### Example payloads

A recurring subscription payment — the event that carries the most weight:

```json
{
  "eventId": "9d1f5c3a-6a5e-4a5f-9a2a-1f0b6c1f2e10",
  "eventTime": "2026-08-01T09:14:22Z",
  "eventType": "payment.succeeded",
  "sourceSystem": "netlicensing/core",
  "tenantId": "V12345678",
  "extra": {
    "licenseeNumber": "LIC-1042",
    "subscriptionNumber": "SUB-88",
    "transactionNumber": "TR-98765",
    "productNumber": "PROD-A",
    "moduleNumber": "MOD-CORE",
    "licenseTemplateNumber": "LT-PRO-M",
    "licenseType": "SUBSCRIPTION",
    "licensingModel": "Subscription",
    "quantity": 5,
    "grossAmount": 119.00,
    "netAmount": 100.00,
    "taxAmount": 19.00,
    "currency": "USD",
    "baseAmount": 110.00,
    "baseCurrency": "EUR",
    "fxRate": 0.92437,
    "paymentMethod": "card",
    "billingPeriod": "MONTH",
    "periodStart": "2026-08-01T00:00:00Z",
    "periodEnd": "2026-09-01T00:00:00Z",
    "actionStatus": "SUCCESS"
  }
}
```

Churn, in its two halves:

```json
{ "eventId": "…", "eventTime": "2026-08-03T11:02:00Z", "eventType": "subscription.cancelled",
  "sourceSystem": "netlicensing/core", "tenantId": "V12345678",
  "extra": { "licenseeNumber": "LIC-1042", "subscriptionNumber": "SUB-88",
             "entityStatus": "CANCELLED", "periodEnd": "2026-09-01T00:00:00Z",
             "actionMessage": "switched to competitor" } }
```
```json
{ "eventId": "…", "eventTime": "2026-09-01T00:00:00Z", "eventType": "subscription.expired",
  "sourceSystem": "netlicensing/core", "tenantId": "V12345678",
  "extra": { "licenseeNumber": "LIC-1042", "subscriptionNumber": "SUB-88",
             "entityStatus": "EXPIRED", "mrrDelta": -110.00, "baseCurrency": "EUR",
             "productNumber": "PROD-A" } }
```

A validation — high volume, so it stays lean:

```json
{ "eventId": "…", "eventTime": "2026-08-08T12:00:01Z", "eventType": "validation.requested",
  "sourceSystem": "netlicensing/core", "tenantId": "V12345678",
  "extra": { "licenseeNumber": "LIC-1042", "productNumber": "PROD-A", "moduleNumber": "MOD-CORE",
             "licensingModel": "Floating", "sessionId": "sess-7f3a", "nodeId": "node-19",
             "actionStatus": "SUCCESS", "durationMs": 12 } }
```

---

## KPI query book

Run these with `just ch "<SQL>"`, at <http://localhost:8123/play>, or paste them into a Grafana
ClickHouse panel. Seed a realistic dataset first with `just ch-seed`.

### Sales

#### MRR — current, and its monthly movement

```sql
-- MRR right now (running sum of every movement to date)
SELECT sum(mrr_delta) AS mrr, anyIf(base_currency, base_currency != '') AS currency
FROM audit.audit_events FINAL
WHERE tenant_id = 'demo' AND mrr_delta IS NOT NULL;
```

```sql
-- MRR at the end of each month, plus the movement that got it there
SELECT month, movement, sum(movement) OVER (ORDER BY month) AS mrr
FROM (
    SELECT toStartOfMonth(event_time) AS month, sum(mrr_delta) AS movement
    FROM audit.audit_events FINAL
    WHERE tenant_id = 'demo' AND mrr_delta IS NOT NULL
    GROUP BY month
)
ORDER BY month;
```

```sql
-- Net new MRR decomposed — the chart that actually explains a flat MRR line
SELECT
    toStartOfMonth(event_time) AS month,
    sumIf(mrr_delta, event_type = 'subscription.started')                          AS new_mrr,
    sumIf(mrr_delta, event_type IN ('subscription.upgraded', 'subscription.resumed')) AS expansion,
    sumIf(mrr_delta, event_type IN ('subscription.downgraded', 'subscription.paused')) AS contraction,
    sumIf(mrr_delta, event_type = 'subscription.expired')                          AS churned,
    sum(mrr_delta)                                                                 AS net_new_mrr
FROM audit.audit_events FINAL
WHERE tenant_id = 'demo' AND mrr_delta IS NOT NULL
GROUP BY month
ORDER BY month;
```

#### Monthly net payments & paying subscribers

Cash, not MRR — grouped by `currency` because these are the amounts actually billed.

```sql
SELECT
    toStartOfMonth(event_time) AS month,
    currency,
    sumIf(gross_amount, event_type = 'payment.succeeded')                              AS payments,
    sumIf(gross_amount, event_type IN ('payment.refunded', 'payment.chargeback'))       AS refunds,
    payments - refunds                                                                  AS net_payments,
    uniqExactIf(licensee_number, event_type = 'payment.succeeded')                      AS paying_customers
FROM audit.audit_events FINAL
WHERE tenant_id = 'demo' AND event_type LIKE 'payment.%' AND currency != ''
GROUP BY month, currency
ORDER BY month DESC, currency;
```

Reporting-currency version — one number, safe to total:

```sql
SELECT toStartOfMonth(event_time) AS month,
       sum(revenue_signed) AS net_revenue,
       anyIf(base_currency, base_currency != '') AS currency
FROM audit.audit_events FINAL
WHERE tenant_id = 'demo' AND event_type LIKE 'payment.%'
GROUP BY month ORDER BY month DESC;
```

#### Daily subscribers added / lost

```sql
SELECT
    toDate(event_time) AS day,
    countIf(event_type = 'subscription.started')   AS added,
    countIf(event_type = 'subscription.expired')   AS lost,
    countIf(event_type = 'subscription.cancelled') AS cancellations_requested,
    added - lost                                   AS net_change,
    sum(added - lost) OVER (ORDER BY day)          AS active_subscriptions
FROM audit.audit_events FINAL
WHERE tenant_id = 'demo'
  AND event_type IN ('subscription.started', 'subscription.expired', 'subscription.cancelled')
GROUP BY day
ORDER BY day DESC;
```

`cancellations_requested` leads `lost` by up to a billing period — that gap is the window in which
a save/win-back campaign can still act.

#### Year-over-year growth

```sql
SELECT
    m                                                    AS month,
    round(current_year, 2)                               AS current_year,
    round(previous_year, 2)                              AS previous_year,
    -- nullIf, not a bare division: the first year of data has no prior year, and 0 would make
    -- the whole column inf instead of empty.
    round((current_year - previous_year) / nullIf(previous_year, 0) * 100, 1) AS yoy_growth_pct
FROM (
    SELECT
        toMonth(event_time) AS m,
        sumIf(revenue_signed, toYear(event_time) = toYear(now()))     AS current_year,
        sumIf(revenue_signed, toYear(event_time) = toYear(now()) - 1) AS previous_year
    FROM audit.audit_events FINAL
    WHERE tenant_id = 'demo' AND event_type LIKE 'payment.%'
    GROUP BY m
)
ORDER BY month;
```

Rolling 12-month version, which is what a board deck usually wants:

```sql
SELECT
    sumIf(revenue_signed, event_time >= now() - INTERVAL 365 DAY)                                AS last_12m,
    sumIf(revenue_signed, event_time >= now() - INTERVAL 730 DAY AND event_time < now() - INTERVAL 365 DAY) AS prior_12m,
    round((last_12m - prior_12m) / nullIf(prior_12m, 0) * 100, 1)                                AS yoy_growth_pct
FROM audit.audit_events FINAL
WHERE tenant_id = 'demo' AND event_type LIKE 'payment.%';
```

#### Licenses and revenue per day

```sql
SELECT
    toDate(event_time) AS day,
    countIf(event_type = 'license.created')     AS licenses_issued,
    uniqExactIf(licensee_number, event_type = 'license.created') AS customers_buying,
    sumIf(revenue_signed, event_type LIKE 'payment.%')           AS net_revenue
FROM audit.audit_events FINAL
WHERE tenant_id = 'demo' AND (event_type = 'license.created' OR event_type LIKE 'payment.%')
GROUP BY day
ORDER BY day DESC;
```

#### Revenue per license type / product / module / licensing model

One query, four dimensions — `GROUPING SETS` gives every breakdown in a single scan instead of
four panels each re-reading the table.

```sql
SELECT
    product_number, module_number, license_type, licensing_model,
    round(sum(revenue_signed), 2) AS net_revenue,
    count()                       AS transactions,
    uniqExact(licensee_number)    AS customers
FROM audit.audit_events FINAL
WHERE tenant_id = 'demo' AND event_type LIKE 'payment.%'
GROUP BY GROUPING SETS (
    (product_number),
    (product_number, module_number),
    (license_type),
    (licensing_model)
)
ORDER BY net_revenue DESC;
```

Per product alone:

```sql
SELECT product_number,
       round(sum(revenue_signed), 2) AS net_revenue,
       round(sumIf(revenue_signed, event_type = 'payment.refunded') / nullIf(sumIf(revenue_signed, event_type = 'payment.succeeded'), 0) * -100, 1) AS refund_rate_pct
FROM audit.audit_events FINAL
WHERE tenant_id = 'demo' AND event_type LIKE 'payment.%' AND product_number != ''
GROUP BY product_number
ORDER BY net_revenue DESC;
```

#### Average deal size

```sql
SELECT
    round(avg(base_amount), 2)                  AS avg_deal_size,
    round(median(base_amount), 2)               AS median_deal_size,
    round(quantile(0.9)(base_amount), 2)        AS p90_deal_size,
    anyIf(base_currency, base_currency != '')   AS currency
FROM audit.audit_events FINAL
WHERE tenant_id = 'demo' AND event_type = 'payment.succeeded' AND base_amount IS NOT NULL;
```

Report the median next to the mean. A licensing business with a handful of enterprise deals has a
mean that describes no actual customer.

#### Lifetime value

Two different numbers, both worth showing:

```sql
-- Realized LTV: revenue actually collected per customer so far.
-- Segmented by lifecycle, because averaging a 3-year customer with a 3-day one is meaningless.
SELECT
    multiIf(age_days < 90, '0-3m', age_days < 365, '3-12m', '12m+') AS cohort_age,
    count()                          AS customers,
    round(avg(revenue), 2)           AS avg_realized_ltv,
    round(median(revenue), 2)        AS median_realized_ltv
FROM (
    SELECT licensee_number,
           sum(revenue_signed)                        AS revenue,
           dateDiff('day', min(event_time), now())    AS age_days
    FROM audit.audit_events FINAL
    WHERE tenant_id = 'demo' AND event_type LIKE 'payment.%' AND licensee_number != ''
    GROUP BY licensee_number
)
GROUP BY cohort_age
ORDER BY cohort_age;
```

```sql
-- Predictive LTV = ARPA / monthly customer churn rate.
WITH
    (SELECT sum(mrr_delta) FROM audit.audit_events FINAL
      WHERE tenant_id = 'demo' AND mrr_delta IS NOT NULL) AS mrr,
    (SELECT uniqExact(licensee_number) FROM audit.audit_events FINAL
      WHERE tenant_id = 'demo' AND event_type = 'subscription.started') -
    (SELECT uniqExact(licensee_number) FROM audit.audit_events FINAL
      WHERE tenant_id = 'demo' AND event_type = 'subscription.expired') AS active_customers,
    (SELECT count() FROM audit.audit_events FINAL
      WHERE tenant_id = 'demo' AND event_type = 'subscription.expired'
        AND event_time >= now() - INTERVAL 30 DAY) AS churned_30d
SELECT
    round(mrr / nullIf(active_customers, 0), 2)                                   AS arpa,
    round(churned_30d / nullIf(active_customers, 0) * 100, 2)                     AS monthly_churn_pct,
    round(mrr / nullIf(active_customers, 0) / nullIf(churned_30d / nullIf(active_customers, 0), 0), 2) AS predictive_ltv;
```

#### Retention (the KPI nobody asks for until the board does)

```sql
-- Net and gross revenue retention, monthly.
SELECT
    month,
    round(starting_mrr, 2)                                                   AS starting_mrr,
    round((starting_mrr + expansion + contraction + churned) / nullIf(starting_mrr, 0) * 100, 1) AS nrr_pct,
    round((starting_mrr + contraction + churned) / nullIf(starting_mrr, 0) * 100, 1)             AS grr_pct
FROM (
    SELECT
        month, expansion, contraction, churned,
        sum(movement) OVER (ORDER BY month ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS starting_mrr
    FROM (
        SELECT toStartOfMonth(event_time) AS month,
               sum(mrr_delta)                                                                 AS movement,
               sumIf(mrr_delta, event_type IN ('subscription.upgraded','subscription.resumed'))   AS expansion,
               sumIf(mrr_delta, event_type IN ('subscription.downgraded','subscription.paused'))  AS contraction,
               sumIf(mrr_delta, event_type = 'subscription.expired')                              AS churned
        FROM audit.audit_events FINAL
        WHERE tenant_id = 'demo' AND mrr_delta IS NOT NULL
        GROUP BY month
    )
)
WHERE starting_mrr > 0
ORDER BY month;
```

### Demographic

Customer attributes change, so take the **latest** value per licensee with `argMax` rather than
counting one row per event.

```sql
-- Customers by country
SELECT country, count() AS customers
FROM (
    SELECT licensee_number, argMax(customer_country, event_time) AS country
    FROM audit.audit_events FINAL
    WHERE tenant_id = 'demo' AND event_type LIKE 'licensee.%' AND customer_country != ''
    GROUP BY licensee_number
)
GROUP BY country
ORDER BY customers DESC;
```

```sql
-- Customers by type and segment.
-- The aliases are deliberately not `customer_type` / `customer_segment`: reusing a column name
-- for its own aggregate makes ClickHouse resolve the inner WHERE against the alias and fail with
-- ILLEGAL_AGGREGATION.
SELECT type, segment, count() AS customers
FROM (
    SELECT licensee_number,
           argMax(customer_type, event_time)    AS type,
           argMax(customer_segment, event_time) AS segment
    FROM audit.audit_events FINAL
    WHERE tenant_id = 'demo' AND event_type LIKE 'licensee.%' AND customer_type != ''
    GROUP BY licensee_number
)
GROUP BY type, segment
ORDER BY customers DESC;
```

```sql
-- Revenue by country — where the money is, not just where the logos are
SELECT c.country, round(sum(p.revenue), 2) AS net_revenue, count(DISTINCT p.licensee_number) AS customers
FROM (
    SELECT licensee_number, revenue_signed AS revenue
    FROM audit.audit_events FINAL
    WHERE tenant_id = 'demo' AND event_type LIKE 'payment.%'
) AS p
INNER JOIN (
    SELECT licensee_number, argMax(customer_country, event_time) AS country
    FROM audit.audit_events FINAL
    WHERE tenant_id = 'demo' AND customer_country != ''
    GROUP BY licensee_number
) AS c USING (licensee_number)
GROUP BY c.country
ORDER BY net_revenue DESC;
```

```sql
-- Where validation traffic physically comes from (geo, not billing country).
-- A country with heavy validation traffic and no customers is a piracy signal.
SELECT geo_country_code, count() AS validations, uniqExact(licensee_number) AS licensees
FROM audit.audit_events
WHERE tenant_id = 'demo' AND event_type = 'validation.requested' AND geo_country_code != ''
GROUP BY geo_country_code
ORDER BY validations DESC;
```

### Operations

These read `ops_daily` — pre-aggregated at insert time, so they stay fast at any volume.

```sql
-- Validation and API request counts by outcome
SELECT day, event_type, action_status, countMerge(events) AS requests
FROM audit.ops_daily
WHERE tenant_id = 'demo' AND event_type IN ('validation.requested', 'api.call')
GROUP BY day, event_type, action_status
ORDER BY day DESC, requests DESC;
```

```sql
-- Requests per day per product (NLIC-2113)
SELECT day, product_number,
       countMerge(events)          AS requests,
       uniqIfMerge(licensees)      AS licensees,
       round(quantilesIfMerge(0.5, 0.95, 0.99)(latency_ms)[2]) AS p95_ms
FROM audit.ops_daily
WHERE tenant_id = 'demo' AND event_type = 'validation.requested' AND product_number != ''
GROUP BY day, product_number
ORDER BY day DESC, requests DESC;
```

```sql
-- API latency and error rate by operation
SELECT action_name,
       countMerge(events) AS calls,
       round(countMergeIf(events, action_status != 'SUCCESS') / countMerge(events) * 100, 2) AS error_pct,
       quantilesIfMerge(0.5, 0.95, 0.99)(latency_ms) AS p50_p95_p99_ms
FROM audit.ops_daily
WHERE tenant_id = 'demo' AND event_type = 'api.call' AND day >= today() - 30
GROUP BY action_name
ORDER BY calls DESC;
```

```sql
-- Licensee status (NLIC-1399) — current state per licensee
SELECT status, count() AS licensees
FROM (
    SELECT licensee_number, argMax(entity_status, event_time) AS status
    FROM audit.audit_events FINAL
    WHERE tenant_id = 'demo' AND event_type LIKE 'licensee.%' AND entity_status != ''
    GROUP BY licensee_number
)
GROUP BY status
ORDER BY licensees DESC;
```

```sql
-- ...and the drill-down list behind that chart
SELECT licensee_number,
       argMax(entity_status, event_time) AS status,
       max(event_time)                   AS last_change,
       argMax(action_message, event_time) AS reason
FROM audit.audit_events FINAL
WHERE tenant_id = 'demo' AND event_type LIKE 'licensee.%' AND entity_status != ''
GROUP BY licensee_number
ORDER BY last_change DESC
LIMIT 100;
```

```sql
-- Current active sessions per product (NLIC-2113).
-- A session counts as active if it started or beat within the window and has not ended.
SELECT product_number, uniqExact(session_id) AS active_sessions, uniqExact(node_id) AS active_nodes
FROM audit.audit_events
WHERE tenant_id = 'demo'
  AND event_type IN ('session.started', 'session.heartbeat')
  AND event_time >= now() - INTERVAL 15 MINUTE
  AND session_id NOT IN (
      SELECT session_id FROM audit.audit_events
      WHERE tenant_id = 'demo' AND event_type = 'session.ended'
        AND event_time >= now() - INTERVAL 1 DAY
  )
GROUP BY product_number
ORDER BY active_sessions DESC;
```

If NetLicensing does not emit explicit session events, approximate from validations — concurrency
for a floating license is exactly "distinct sessions seen recently":

```sql
SELECT product_number, uniqExact(session_id) AS active_sessions
FROM audit.audit_events
WHERE tenant_id = 'demo' AND event_type = 'validation.requested'
  AND event_time >= now() - INTERVAL 15 MINUTE AND session_id != ''
GROUP BY product_number
ORDER BY active_sessions DESC;
```

```sql
-- Customer growth, daily and cumulative
SELECT day, new_customers, sum(new_customers) OVER (ORDER BY day) AS total_customers
FROM (
    SELECT toDate(event_time) AS day, uniqExact(licensee_number) AS new_customers
    FROM audit.audit_events FINAL
    WHERE tenant_id = 'demo' AND event_type = 'licensee.created'
    GROUP BY day
)
ORDER BY day DESC;
```

```sql
-- Top deny reasons — the report that turns support tickets into a product fix
SELECT product_number, action_message, count() AS denials
FROM audit.audit_events
WHERE tenant_id = 'demo' AND event_type = 'validation.requested'
  AND action_status != 'SUCCESS' AND event_time >= now() - INTERVAL 30 DAY
GROUP BY product_number, action_message
ORDER BY denials DESC
LIMIT 20;
```

---

## Integration notes

**Ordering does not matter.** Every query aggregates on `event_time`, so events can arrive late,
out of order, or as a bulk backfill. Backfilling history is just publishing old events with the
right `eventTime`.

**Set `eventId` to something stable and derived** (e.g. a UUID v5 of the NetLicensing entity plus
the transaction), not a fresh random per attempt. That is what makes a retry idempotent within
AuditFlow's ~24h window and what lets `ReplacingMergeTree` collapse a late DLQ replay.

**One tenant per vendor.** `tenantId` is the vendor number and is authoritative at the gateway.
Pipelines are per tenant, so onboarding a vendor to ClickHouse analytics is adding
`tenants/<vendorId>.yaml` — no code change, hot-reloaded within ~5s.

**Redaction happens before the pipeline.** Anything AuditFlow is configured to mask or drop is
masked or dropped in what ClickHouse stores. Check the redaction rules before promoting a field to
a KPI dimension — a redacted column is silently empty, not obviously broken.

**Keep PII out.** The schema deliberately carries `licenseeNumber`, not names or emails. The
dashboard resolves numbers to names against NetLicensing itself, so the analytics store never
becomes a second copy of the customer database.

**Retention.** Both `audit_events` and `ops_daily` carry a 1095-day TTL on `event_time`. YoY needs
at least 24 months of the raw table — check the TTL in `schema.sql` against the longest window any
dashboard looks back over before you need the history, not after.

---

## Grafana

The observability overlay (`just up obs`) provisions a ClickHouse data source pointing at the
`audit` database, so panels can use the SQL above unchanged.

1. <http://localhost:3000> (`admin` / `admin`)
2. **Dashboards → New → Add visualization → ClickHouse**
3. Paste a query. Time series panels want the time column first and aliased `time`; stat panels
   take the single-value queries as-is.
4. Add a dashboard variable `tenant` with query
   `SELECT DISTINCT tenant_id FROM audit.audit_events` and replace `'demo'` with `$tenant`.

The data source is installed via `GF_INSTALL_PLUGINS`, which downloads the plugin at container
start — the first `just up obs` on a fresh machine needs network access, and on an air-gapped host
Grafana starts without it.

Outside Grafana: `just ch-events`, `just ch-stats`, `just ch "<SQL>"`, `just ch-shell`, or the
built-in console at <http://localhost:8123/play> (`auditflow` / `auditflow`). External BI tools
connect on `localhost:9000` (native) or `localhost:8123` (HTTP).
