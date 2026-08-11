#!/usr/bin/env python3
"""Publish a synthetic NetLicensing event stream to AuditFlow, for KPI dashboard development.

Simulates a licensing business rather than emitting random events: customers are created, take out
subscriptions, are billed every month, upgrade, downgrade, churn and occasionally get refunded,
while their software validates licenses in the background. That coherence is the point — MRR,
retention and LTV are only meaningful if `subscription.started` and `subscription.expired` refer to
the same subscription and the payments in between line up with it.

The emitted events and fields follow NETLICENSING_KPI.md; every query in that file returns
something sensible against this data set.

Usage
-----
    just ch-seed                                  # defaults: 40 customers over 800 days
    python3 examples/clickhouse/seed_kpi_dashboards.py --help
    python3 examples/clickhouse/seed_kpi_dashboards.py --customers 200 --days 800
    python3 examples/clickhouse/seed_kpi_dashboards.py --dry-run | head

Standard library only, so it runs anywhere `python3` does with no virtualenv.

Re-running is safe: event ids are UUID v5 values derived from the event's identity, so a second run
republishes the *same* ids. AuditFlow deduplicates them within its idempotency window, and the
ClickHouse table is a ReplacingMergeTree keyed on `event_id`, so the row count does not grow.
Change `--seed` to generate a genuinely different business.
"""
import argparse
import json
import random
import sys
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

# Stable namespace for UUID v5 event ids — see the idempotency note in the module docstring.
NAMESPACE = uuid.UUID("6f1b7d4c-9c1a-5f2e-8a3b-0c4d5e6f7a8b")

SOURCE_SYSTEM = "netlicensing/core"
BASE_CURRENCY = "EUR"

# Billing currency → rate to the reporting currency. Fixed per run: the point of `fxRate` is that
# the rate is frozen at transaction time, and a wandering rate would make the seed unreproducible.
CURRENCIES = {"EUR": 1.0, "USD": 0.92, "GBP": 1.17}

COUNTRIES = [
    # (country, weight, currency, customer type bias toward B2B)
    ("DE", 22, "EUR", 0.7), ("US", 30, "USD", 0.6), ("GB", 12, "GBP", 0.6),
    ("FR", 10, "EUR", 0.7), ("NL", 7, "EUR", 0.8), ("ES", 6, "EUR", 0.5),
    ("IN", 8, "USD", 0.4), ("BR", 5, "USD", 0.3),
]

SEGMENTS = [("enterprise", 15), ("smb", 45), ("individual", 40)]
CHANNELS = [("direct", 50), ("marketplace", 25), ("reseller", 15), ("partner", 10)]

# Product catalog: number, module, licensing model, license type, monthly price in EUR.
PRODUCTS = [
    ("PROD-A", "MOD-CORE", "Subscription", "SUBSCRIPTION", "LT-A-PRO", 99.0),
    ("PROD-A", "MOD-ANALYTICS", "Subscription", "SUBSCRIPTION", "LT-A-ANALYTICS", 49.0),
    ("PROD-B", "MOD-CORE", "Floating", "FLOATING", "LT-B-FLOAT", 199.0),
    ("PROD-C", "MOD-CORE", "TryAndBuy", "TIMEVOLUME", "LT-C-STARTER", 29.0),
]

API_OPERATIONS = [
    ("licensee.get", 40), ("license.create", 12), ("licensee.list", 18),
    ("product.get", 15), ("token.create", 8), ("licensee.update", 7),
]

DENY_REASONS = [
    "license expired", "no active license for product module",
    "concurrent session limit reached", "licensee suspended",
]

VAT = {"DE": 0.19, "FR": 0.20, "NL": 0.21, "ES": 0.21, "GB": 0.20,
       "US": 0.0, "IN": 0.18, "BR": 0.0}


def _weighted(rng, pairs):
    values, weights = zip(*pairs)
    return rng.choices(values, weights=weights, k=1)[0]


def _money(value):
    """Two-decimal string. Amounts travel as strings so no float ever reaches Decimal columns."""
    return f"{round(value + 1e-9, 2):.2f}"


def _iso(dt):
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def _event(kind, when, tenant, identity, extra, geo=None):
    """One AuditEvent. `identity` makes the id reproducible across runs."""
    event = {
        "eventId": str(uuid.uuid5(NAMESPACE, f"{tenant}|{kind}|{identity}")),
        "eventTime": _iso(when),
        "eventType": kind,
        "sourceSystem": SOURCE_SYSTEM,
        "tenantId": tenant,
        "extra": {k: v for k, v in extra.items() if v is not None and v != ""},
    }
    if geo:
        event["geolocation"] = geo
    return event


class Business:
    """Generates a coherent licensing business over a time window."""

    def __init__(self, args, rng):
        self.args = args
        self.rng = rng
        self.now = datetime.now(timezone.utc).replace(microsecond=0)
        self.start = self.now - timedelta(days=args.days)
        self.events = []
        # (licensee, product, live_from, live_until, country) — drives which customers generate
        # validation and API traffic on a given day, so operational volume tracks the customer
        # base rather than being flat across the whole window.
        self.spans = []

    # ── helpers ──────────────────────────────────────────────────────────────────────────────

    def add(self, kind, when, identity, extra, geo=None):
        if when > self.now:
            return
        self.events.append(_event(kind, when, self.args.tenant, identity, extra, geo))

    def request_origin(self, home_country):
        """Country the *request* came from. Usually the customer's own, sometimes not — and that
        gap is the point: `geo_*` answers "where is this software running", `customer_country`
        answers "who is the customer". Traffic from a country with no customers is a piracy
        signal, and it only shows up if the two are kept apart."""
        if self.rng.random() < 0.88:
            return home_country
        return self.rng.choice([c[0] for c in COUNTRIES] + ["CN", "RU", "VN"])

    def amounts(self, price_eur, currency, country):
        """Build the full money block for a charge of `price_eur` billed in `currency`."""
        rate = CURRENCIES[currency]
        gross_local = price_eur / rate
        tax_rate = VAT.get(country, 0.0)
        net_local = gross_local / (1 + tax_rate)
        return {
            "grossAmount": _money(gross_local),
            "netAmount": _money(net_local),
            "taxAmount": _money(gross_local - net_local),
            "currency": currency,
            "baseAmount": _money(price_eur),
            "baseCurrency": BASE_CURRENCY,
            "fxRate": f"{rate:.5f}",
        }

    # ── generation ───────────────────────────────────────────────────────────────────────────

    def generate(self):
        self.catalog()
        for i in range(self.args.customers):
            self.customer(i)
        self.operations()
        self.events.sort(key=lambda e: e["eventTime"])
        return self.events

    def catalog(self):
        """Product/template events. Low volume, but they are what annotates a revenue chart."""
        seen_products = set()
        for product, module, model, license_type, template, price in PRODUCTS:
            when = self.start - timedelta(days=1)
            # PRODUCTS lists one row per module, so a product with several modules appears more
            # than once — the product itself is only created once.
            if product not in seen_products:
                seen_products.add(product)
                self.add("product.created", when, product,
                         {"productNumber": product, "entityStatus": "ACTIVE"})
            self.add("product_module.created", when, f"{product}/{module}",
                     {"productNumber": product, "moduleNumber": module,
                      "licensingModel": model, "entityStatus": "ACTIVE"})
            self.add("license_template.created", when, template,
                     {"productNumber": product, "moduleNumber": module,
                      "licenseTemplateNumber": template, "licenseType": license_type,
                      "grossAmount": _money(price), "currency": BASE_CURRENCY,
                      "billingPeriod": "MONTH", "entityStatus": "ACTIVE"})

        # One mid-window price rise, so "why did ARPA jump in month N" has an answer on the chart.
        bump_at = self.start + timedelta(days=int(self.args.days * 0.55))
        product, module, model, license_type, template, price = PRODUCTS[0]
        self.add("license_template.updated", bump_at, f"{template}|raise",
                 {"productNumber": product, "moduleNumber": module,
                  "licenseTemplateNumber": template, "licenseType": license_type,
                  "grossAmount": _money(price * 1.1), "currency": BASE_CURRENCY,
                  "billingPeriod": "MONTH", "entityStatus": "ACTIVE",
                  "actionMessage": "annual price adjustment"})

    def customer(self, index):
        rng = self.rng
        licensee = f"LIC-{1000 + index}"
        country, _weight, currency, b2b_bias = _weighted(rng, [(c, c[1]) for c in COUNTRIES])
        customer_type = "B2B" if rng.random() < b2b_bias else "B2C"
        segment = _weighted(rng, SEGMENTS)
        channel = _weighted(rng, CHANNELS)
        reseller = f"RES-{rng.randint(1, 4)}" if channel in ("reseller", "partner") else None

        # Signups accelerate over the window — a flat arrival rate makes every growth chart a
        # straight line, which is not what a real dashboard has to cope with.
        progress = (index + 1) / self.args.customers
        created = self.start + timedelta(
            days=self.args.days * (1 - (1 - progress) ** 1.6) * rng.uniform(0.92, 1.0),
            hours=rng.randint(0, 23), minutes=rng.randint(0, 59))

        common = {"licenseeNumber": licensee, "customerType": customer_type,
                  "customerCountry": country, "customerSegment": segment,
                  "acquisitionChannel": channel, "resellerNumber": reseller}

        self.add("licensee.created", created, licensee, {**common, "entityStatus": "ACTIVE"})

        product, module, model, license_type, template, price = rng.choice(PRODUCTS)
        seats = rng.choice([1, 1, 1, 2, 5, 10, 25]) if customer_type == "B2B" else 1
        monthly = price * seats
        subscription = f"SUB-{1000 + index}"
        license_number = f"L-{1000 + index}"

        self.add("license.created", created + timedelta(minutes=2), license_number,
                 {**common, "licenseNumber": license_number, "productNumber": product,
                  "moduleNumber": module, "licenseTemplateNumber": template,
                  "licenseType": license_type, "licensingModel": model,
                  "quantity": seats, "entityStatus": "ACTIVE"})

        trial = rng.random() < 0.35
        if trial:
            self.add("subscription.trial.started", created, f"{subscription}|trial",
                     {**common, "subscriptionNumber": subscription, "productNumber": product,
                      "isTrial": True, "entityStatus": "ACTIVE"})
            if rng.random() < 0.45:  # trial that never converts: no subscription, no revenue
                self.add("subscription.trial.expired", created + timedelta(days=14),
                         f"{subscription}|trialend",
                         {**common, "subscriptionNumber": subscription,
                          "productNumber": product, "isTrial": True, "entityStatus": "EXPIRED"})
                return
            started = created + timedelta(days=14)
            self.add("subscription.trial.converted", started, f"{subscription}|conv",
                     {**common, "subscriptionNumber": subscription, "productNumber": product,
                      "isTrial": False, "entityStatus": "ACTIVE"})
        else:
            started = created

        self.add("subscription.started", started, subscription,
                 {**common, "subscriptionNumber": subscription, "productNumber": product,
                  "moduleNumber": module, "licenseTemplateNumber": template,
                  "licenseType": license_type, "licensingModel": model, "quantity": seats,
                  "mrrDelta": _money(monthly), "baseCurrency": BASE_CURRENCY,
                  "billingPeriod": "MONTH", "billingPeriodCount": 1,
                  "periodStart": _iso(started), "periodEnd": _iso(started + timedelta(days=30)),
                  "entityStatus": "ACTIVE"})

        # Lifetime: a mixed population of quick churners and long-lived accounts.
        lifetime_months = max(1, int(rng.expovariate(1 / 11.0)) + 1)
        if segment == "enterprise":
            lifetime_months += 6

        self.billing_cycle(common, subscription, product, module, template, license_type, model,
                           seats, monthly, started, lifetime_months, currency, country)

    def billing_cycle(self, common, subscription, product, module, template, license_type,
                      model, seats, monthly, started, lifetime_months, currency, country):
        rng = self.rng
        current_monthly = monthly
        churned_at = None

        for month in range(lifetime_months):
            period_start = started + timedelta(days=30 * month)
            if period_start > self.now:
                break
            period_end = period_start + timedelta(days=30)

            if month > 0:
                self.add("subscription.renewed", period_start, f"{subscription}|r{month}",
                         {**common, "subscriptionNumber": subscription,
                          "productNumber": product, "billingPeriod": "MONTH",
                          "periodStart": _iso(period_start), "periodEnd": _iso(period_end),
                          "entityStatus": "ACTIVE"})

            # Plan changes: expansion is more likely than contraction, as in a healthy business.
            if month > 0 and rng.random() < 0.12:
                expand = rng.random() < 0.7
                factor = rng.choice([1.5, 2.0]) if expand else rng.choice([0.5, 0.75])
                new_monthly = round(current_monthly * factor, 2)
                delta = new_monthly - current_monthly
                self.add("subscription.upgraded" if expand else "subscription.downgraded",
                         period_start + timedelta(days=2), f"{subscription}|c{month}",
                         {**common, "subscriptionNumber": subscription,
                          "productNumber": product, "licenseTemplateNumber": template,
                          "quantity": max(1, int(seats * factor)),
                          "mrrDelta": _money(delta), "baseCurrency": BASE_CURRENCY,
                          "entityStatus": "ACTIVE"})
                current_monthly = new_monthly

            # Charge for the period. ~4% of attempts fail — the involuntary-churn signal.
            transaction = f"TR-{subscription[4:]}-{month:02d}"
            charged_at = period_start + timedelta(hours=rng.randint(0, 6))
            if rng.random() < 0.04:
                self.add("payment.failed", charged_at, transaction,
                         {**common, "subscriptionNumber": subscription,
                          "transactionNumber": transaction, "productNumber": product,
                          "paymentMethod": "card", "actionStatus": "FAILURE",
                          "actionMessage": "card declined"})
                if rng.random() < 0.5:
                    churned_at = period_start + timedelta(days=3)
                    self.end_subscription(common, subscription, product, current_monthly,
                                          churned_at, voluntary=False)
                    break
                continue

            self.add("payment.succeeded", charged_at, transaction,
                     {**common, "subscriptionNumber": subscription,
                      "transactionNumber": transaction, "productNumber": product,
                      "moduleNumber": module, "licenseTemplateNumber": template,
                      "licenseType": license_type, "licensingModel": model,
                      "quantity": seats, "paymentMethod": rng.choice(["card", "card", "invoice",
                                                                      "paypal"]),
                      "billingPeriod": "MONTH", "billingPeriodCount": 1,
                      "periodStart": _iso(period_start), "periodEnd": _iso(period_end),
                      "actionStatus": "SUCCESS",
                      **self.amounts(current_monthly, currency, country)})

            if rng.random() < 0.035:
                refund_at = charged_at + timedelta(days=rng.randint(1, 10))
                self.add("payment.refunded", refund_at, f"{transaction}|rf",
                         {**common, "subscriptionNumber": subscription,
                          "transactionNumber": transaction, "productNumber": product,
                          "actionStatus": "SUCCESS", "actionMessage": "goodwill refund",
                          **self.amounts(current_monthly, currency, country)})

        licensee = common["licenseeNumber"]
        home = common["customerCountry"]
        if churned_at is not None:
            self.spans.append((licensee, product, started, churned_at, home))
            return

        end_at = started + timedelta(days=30 * lifetime_months)
        if end_at <= self.now:
            self.end_subscription(common, subscription, product, current_monthly, end_at,
                                  voluntary=True)
            self.spans.append((licensee, product, started, end_at, home))
        else:
            self.spans.append((licensee, product, started, None, home))

    def end_subscription(self, common, subscription, product, monthly, when, voluntary):
        reason = ("switched to competitor" if voluntary else "payment recovery failed")
        if voluntary:
            # Cancellation is intent; revenue keeps running until the period ends. Emitting it
            # separately is what stops churn from being counted twice or counted early.
            self.add("subscription.cancelled", when - timedelta(days=12),
                     f"{subscription}|cancel",
                     {**common, "subscriptionNumber": subscription, "productNumber": product,
                      "entityStatus": "CANCELLED", "periodEnd": _iso(when),
                      "actionMessage": reason})
        self.add("subscription.expired", when, f"{subscription}|expire",
                 {**common, "subscriptionNumber": subscription, "productNumber": product,
                  "mrrDelta": _money(-monthly), "baseCurrency": BASE_CURRENCY,
                  "entityStatus": "EXPIRED", "actionMessage": reason})
        self.add("license.expired", when, f"{subscription}|licexp",
                 {**common, "productNumber": product, "entityStatus": "EXPIRED"})
        self.add("licensee.status.changed", when, f"{subscription}|status",
                 {**common, "entityStatus": "CLOSED", "entityStatusPrev": "ACTIVE",
                  "actionMessage": reason})

    def operations(self):
        """Validation, API and session traffic — the high-volume stream the ops KPIs read."""
        rng = self.rng
        if not self.spans:
            return

        for day_offset in range(self.args.days):
            day = self.start + timedelta(days=day_offset)
            # Only customers with a live subscription generate traffic that day, so operational
            # volume follows the customer base instead of being flat across the whole window.
            live = [(licensee, product, home)
                    for licensee, product, live_from, live_until, home in self.spans
                    if live_from <= day and (live_until is None or day < live_until)]
            if not live:
                continue
            # Weekends are quieter — a perfectly flat series hides every real anomaly.
            weekday_factor = 0.45 if day.weekday() >= 5 else 1.0
            count = max(1, int(self.args.validations_per_day * weekday_factor * len(live) / 10))

            for i in range(count):
                licensee, product, home = rng.choice(live)
                when = day + timedelta(hours=rng.randint(6, 21), minutes=rng.randint(0, 59))
                self.validation(when, f"val|{day_offset}|{i}", licensee, product, home)

            for i in range(max(1, int(self.args.api_calls_per_day * len(live) / 10))):
                licensee, product, home = rng.choice(live)
                when = day + timedelta(hours=rng.randint(6, 21), minutes=rng.randint(0, 59))
                failed = rng.random() < 0.03
                self.add("api.call", when, f"api|{day_offset}|{i}",
                         {"licenseeNumber": licensee, "productNumber": product,
                          "actionName": _weighted(rng, API_OPERATIONS),
                          "actionStatus": "FAILURE" if failed else "SUCCESS",
                          "responseStatus": 500 if failed else 200,
                          "durationMs": rng.randint(300, 900) if failed
                          else rng.randint(8, 220)},
                         geo={"countryCode": self.request_origin(home)})

        # Traffic in the last few minutes, so the "current active sessions" panels are not empty
        # the moment the seed finishes — both the session-event version and the validation-based
        # fallback need something inside their window.
        current = [s for s in self.spans if s[3] is None] or self.spans
        for i in range(12):
            licensee, product, _from, _until, home = rng.choice(current)
            when = self.now - timedelta(minutes=rng.randint(0, 12))
            self.add("session.started", when, f"live-session|{i}",
                     {"licenseeNumber": licensee, "productNumber": product,
                      "moduleNumber": "MOD-CORE", "sessionId": f"live-{i:02d}",
                      "nodeId": f"node-{rng.randint(1, 25):03d}"},
                     geo={"countryCode": self.request_origin(home)})
            self.validation(when + timedelta(seconds=rng.randint(1, 120)),
                            f"live-val|{i}", licensee, product, home,
                            session_id=f"live-{i:02d}")

    def validation(self, when, identity, licensee, product, home, session_id=None):
        rng = self.rng
        denied = rng.random() < 0.06
        self.add("validation.requested", when, identity,
                 {"licenseeNumber": licensee, "productNumber": product,
                  "moduleNumber": "MOD-CORE",
                  "sessionId": session_id or f"sess-{rng.randint(1, 40):03d}",
                  "nodeId": f"node-{rng.randint(1, 25):03d}",
                  "actionStatus": "DENIED" if denied else "SUCCESS",
                  "actionMessage": rng.choice(DENY_REASONS) if denied else None,
                  "durationMs": rng.randint(4, 25) if denied else rng.randint(4, 60)},
                 geo={"countryCode": self.request_origin(home)})


def publish(events, url, verbose):
    endpoint = url.rstrip("/") + "/audit/publish"
    ok = failed = 0
    first_error = None

    for i, event in enumerate(events, start=1):
        request = urllib.request.Request(
            endpoint, data=json.dumps(event).encode("utf-8"),
            headers={"Content-Type": "application/json"}, method="POST")
        try:
            with urllib.request.urlopen(request, timeout=30):
                ok += 1
        except urllib.error.HTTPError as e:
            failed += 1
            if first_error is None:
                first_error = f"HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:300]}"
        except OSError as e:
            failed += 1
            if first_error is None:
                first_error = str(e)
            # A connection error on the very first event means the stack is not reachable;
            # hammering it with thousands more only delays the message.
            if ok == 0 and i >= 3:
                print(f"Cannot reach {endpoint} — is the stack up? ({first_error})",
                      file=sys.stderr)
                return ok, failed
        if verbose and i % 250 == 0:
            print(f"  published {i}/{len(events)}")

    if first_error:
        print(f"First failure: {first_error}", file=sys.stderr)
    return ok, failed


def main():
    parser = argparse.ArgumentParser(
        description=__doc__.split("\n")[0],
        formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", default="http://localhost:8080",
                        help="AuditFlow base URL (default: %(default)s). Use "
                             "http://backend:8080 from inside the compose network.")
    parser.add_argument("--tenant", default="demo",
                        help="tenantId to publish as; must be provisioned in tenants/ "
                             "(default: %(default)s)")
    parser.add_argument("--customers", type=int, default=40,
                        help="number of licensees to simulate (default: %(default)s)")
    parser.add_argument("--days", type=int, default=800,
                        help="length of the simulated history in days. Year-over-year compares "
                             "the last 12 months against the 12 before them, so it needs >730 "
                             "to show anything — and the audit_events TTL has to cover the same "
                             "span (default: %(default)s)")
    parser.add_argument("--validations-per-day", type=int, default=4,
                        help="validation.requested events per day (default: %(default)s)")
    parser.add_argument("--api-calls-per-day", type=int, default=2,
                        help="api.call events per day (default: %(default)s)")
    parser.add_argument("--seed", type=int, default=42,
                        help="RNG seed; same seed produces the same business (default: "
                             "%(default)s)")
    parser.add_argument("--dry-run", action="store_true",
                        help="print the events as NDJSON instead of publishing")
    args = parser.parse_args()

    events = Business(args, random.Random(args.seed)).generate()

    if args.dry_run:
        for event in events:
            print(json.dumps(event))
        return 0

    kinds = {}
    for event in events:
        kinds[event["eventType"]] = kinds.get(event["eventType"], 0) + 1
    print(f"Simulated {args.customers} customers over {args.days} days "
          f"→ {len(events)} events, {len(kinds)} types.")
    print(f"Publishing to {args.url} as tenant '{args.tenant}'...")

    ok, failed = publish(events, args.url, verbose=True)
    print(f"Published: {ok}   Failed: {failed}")

    if failed:
        print("Some events were rejected — check that the tenant is provisioned and enabled.",
              file=sys.stderr)
        return 1

    print("\nDelivery is asynchronous and the ClickHouse sink waits for each insert to flush,")
    print("so give the pipeline a moment to drain, then:")
    print("  just ch \"SELECT event_type, count() FROM audit_events GROUP BY event_type "
          "ORDER BY 2 DESC FORMAT PrettyCompactMonoBlock\"")
    print("  open examples/clickhouse/NETLICENSING_KPI.md for the KPI query book")
    return 0


if __name__ == "__main__":
    sys.exit(main())
