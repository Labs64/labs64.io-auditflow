#!/usr/bin/env python3
"""Publish a synthetic NetLicensing API + Payment Gateway event stream to AuditFlow.

Two sources, both shaped exactly as the NetLicensing API audit event spec defines them
(`eventType: "api.call"`, the endpoint in `extra.actionMethod`, `extra.statusCode`):

  * **NetLicensing** — entity CRUD, licensee and license lifecycle, tokens, reporting, and the
    `licensee/validate` traffic that dominates a real deployment (dry runs, node-locked and
    multi-module validations, and the expiry/warning verdicts they return).
  * **Payment Gateway** — the full payment lifecycle per the OpenAPI: create → pay → close, plus
    cancellations, deletions, checkout-session steps, provider webhooks and monthly renewals.

Volume, not variety, is the point: the queries in NETLICENSING_EVENTS.md are aggregations, and an
aggregation over eight rows demonstrates nothing. The default is ~2000 events over 30 days.

This is a *generator*, not a model of a business — there is no MRR, retention or cohort machinery
here. It exists so the ClickHouse queries have something to chew on.

Usage
-----
    just ch-seed
    just ch-seed "--events 20000 --days 90"
    python3 examples/clickhouse/seed_events.py --url http://localhost:8080 --tenant demo
    python3 examples/clickhouse/seed_events.py --dry-run | head
"""
import argparse
import json
import random
import string
import sys
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

# ─────────────────────────────────────────────────────────────────────────────────────────────
# Reference data
# ─────────────────────────────────────────────────────────────────────────────────────────────

# NetLicensing entity CRUD, weighted the way an operational deployment actually calls them: reads
# far outnumber writes, and `licensee/validate` (handled separately below) outnumbers all of them.
CRUD_METHODS = [
    ("licensee/get", 24), ("licensee/list", 10), ("licensee/create", 6), ("licensee/update", 5),
    ("licensee/delete", 1), ("licensee/transfer", 1),
    ("license/get", 8), ("license/list", 5), ("license/create", 6), ("license/update", 3),
    ("license/delete", 1),
    ("product/get", 6), ("product/list", 5), ("product/create", 1), ("product/update", 2),
    ("productmodule/get", 5), ("productmodule/list", 4), ("productmodule/update", 1),
    ("licensetemplate/get", 4), ("licensetemplate/list", 4), ("licensetemplate/create", 2),
    ("licensetemplate/update", 2),
    ("bundle/get", 2), ("bundle/create", 1), ("bundle/obtain", 2),
    ("token/generate", 9), ("token/get", 4), ("token/list", 2), ("token/delete", 2),
    ("transaction/get", 4), ("transaction/list", 3), ("transaction/create", 2),
    ("transaction/sendorderconfirmation", 2),
    ("reporting/get", 5),
    ("vendor/get", 2), ("vendor/update", 1),
]

# Unauthenticated / console endpoints. The spec puts these on the V00000000 placeholder tenant;
# see the note in _public_events() for why this script does not.
PUBLIC_METHODS = [
    ("userinterface/getprojectversion", 8), ("utility/countries", 4),
    ("utility/licensingModels", 3), ("utility/licenseTypes", 3),
    ("userinterface/getuserprofile", 5), ("userinterface/loginoauth", 4),
    ("userinterface/logout", 3), ("userinterface/statistic", 2),
    ("userinterface/resetpassword", 1), ("userinterface/confirmemail", 1),
]

# How long before expiry a license starts reporting EXPIRING_SOON, and how long it keeps working
# after it. The verdict is *derived* from these and the licensee's own expiry date rather than
# drawn per call — a licensee must not flicker VALID → EXPIRED → VALID across the stream, and a
# renewal-warning query is only meaningful if it can watch one licensee cross the boundary.
WARNING_WINDOW_DAYS = 30
GRACE_PERIOD_DAYS = 14

COUNTRIES = [("DE", "EUR"), ("AT", "EUR"), ("FR", "EUR"), ("NL", "EUR"), ("ES", "EUR"),
             ("US", "USD"), ("CA", "USD"), ("GB", "GBP"), ("CH", "CHF"), ("SE", "SEK")]

PAYMENT_METHODS = [("stripe", 46), ("paypal", 31), ("braintree", 13), ("invoice", 10)]
BILLING_PERIODS = [("MONTH", 62), ("YEAR", 24), ("ONE_TIME", 12), ("WEEK", 2)]
SOURCE_SYSTEMS = [("netlicensing/CONSOLE", 34), ("netlicensing/SHOP", 26),
                  ("netlicensing/EXTERNAL", 25), ("netlicensing/core", 15)]

# Failures, by the status code the API actually returns. Kept to a few percent — a stream where
# every tenth call 500s makes the failure-rate query look broken rather than informative.
FAILURE_CODES = [(400, 30), (401, 18), (403, 12), (404, 25), (409, 6), (429, 4), (500, 5)]

TAX_RATES = {"DE": 0.19, "AT": 0.20, "FR": 0.20, "NL": 0.21, "ES": 0.21, "CH": 0.077,
             "SE": 0.25, "GB": 0.20, "US": 0.0, "CA": 0.05}


def _pick(rng, weighted):
    """Weighted choice over [(value, weight), ...]."""
    values, weights = zip(*weighted)
    return rng.choices(values, weights=weights, k=1)[0]


def _correlation_id(rng):
    """NetLicensing request ids are short uppercase alphanumerics (RQUZPIZC4AQ6S5IXK)."""
    return "R" + "".join(rng.choices(string.ascii_uppercase + string.digits, k=16))


def _iso(dt):
    # Milliseconds: the column is DateTime64(3) and the spec's templates carry them.
    return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond // 1000:03d}Z"


# ─────────────────────────────────────────────────────────────────────────────────────────────
# Event construction
# ─────────────────────────────────────────────────────────────────────────────────────────────

class Generator:
    def __init__(self, tenant, days, seed):
        self.tenant = tenant
        self.days = days
        self.rng = random.Random(seed)
        self.now = datetime.now(timezone.utc).replace(microsecond=0)
        self.start = self.now - timedelta(days=days)

        rng = self.rng
        self.products = [f"P{rng.randint(10000, 99999)}" for _ in range(6)]
        self.modules = [f"M{''.join(rng.choices(string.ascii_uppercase + string.digits, k=8))}"
                        for _ in range(12)]
        # A licensee carries its own country/currency so demographic and revenue queries stay
        # coherent: the same customer must not appear in three countries across the stream.
        self.licensees = []
        for i in range(40):
            country, currency = rng.choice(COUNTRIES)
            # Each licensee owns an expiry date, spread so that some cross a boundary *inside* the
            # window: a few already lapsed, a few lapse during it, most run well past the end.
            self.licensees.append({
                "number": f"I{rng.randint(100000, 999999)}" if i % 3 else
                          f"customer{i:02d}@example.com",
                "country": country,
                "currency": currency,
                "product": rng.choice(self.products),
                "module": rng.choice(self.modules),
                "validUntil": self.now + timedelta(days=_pick(rng, [
                    (rng.uniform(-45, -1), 8),    # already lapsed before the window closed
                    (rng.uniform(-10, 25), 14),   # crosses a boundary inside it — the useful case
                    (rng.uniform(45, 700), 78),   # comfortably valid, as most licenses are
                ])),
            })

    def _when(self):
        """A timestamp somewhere in the window, biased toward business hours.

        Clamped to `now`: resampling the hour can push an event past the end of the window, and a
        stream containing future-dated audit events makes every "last 24h" query look wrong.
        """
        rng = self.rng
        day = self.start + timedelta(days=rng.uniform(0, self.days))
        hour = min(23, max(0, int(rng.gauss(13, 4))))
        when = day.replace(hour=hour, minute=rng.randrange(60), second=rng.randrange(60),
                           microsecond=rng.randrange(1000) * 1000)
        return min(when, self.now)

    def _event(self, when, method, extra, source=None, licensee=None, failure_rate=0.03):
        """One `api.call` event on the spec's universal template."""
        rng = self.rng
        failed = rng.random() < failure_rate
        status = _pick(rng, FAILURE_CODES) if failed else rng.choice([200, 200, 200, 201])

        payload = {
            "actionMethod": method,
            "actionStatus": "FAILURE" if failed else "SUCCESS",
            "statusCode": status,
            # Not part of the NetLicensing vocabulary — promoted by the *generic* layer, so the
            # latency percentiles in the query book work without a use-case column of their own.
            "durationMs": rng.randint(180, 2400) if failed else rng.randint(4, 320),
            **extra,
        }
        if failed:
            payload["actionMessage"] = f"{method} failed with {status}"

        event = {
            "eventId": str(uuid.uuid4()),
            # Clamped: lifecycle steps and renewals advance their own clock and can otherwise run
            # past the end of the window.
            "eventTime": _iso(min(when, self.now)),
            "correlationId": _correlation_id(rng),
            "eventType": "api.call",
            "sourceSystem": source or _pick(rng, SOURCE_SYSTEMS),
            "tenantId": self.tenant,
            "extra": payload,
        }
        if licensee:
            event["geolocation"] = {"countryCode": licensee["country"]}
        return event

    # ── NetLicensing ─────────────────────────────────────────────────────────────────────────

    def crud_event(self):
        rng = self.rng
        licensee = rng.choice(self.licensees)
        method = _pick(rng, CRUD_METHODS)
        extra = {}

        entity = method.split("/")[0]
        if entity in ("licensee", "license", "bundle", "transaction"):
            extra["licenseeNumber"] = licensee["number"]
        if entity in ("product", "license", "licensetemplate", "bundle"):
            extra["productNumber"] = licensee["product"]
        if entity == "productmodule":
            extra["moduleNumber"] = licensee["module"]
            extra["productNumber"] = licensee["product"]

        return self._event(self._when(), method, extra, licensee=licensee)

    def validation_event(self):
        """`licensee/validate` — the highest-volume method, and the one with variable args.

        Covers all three shapes the spec documents: dry run with a product number, node-locked
        with a single module, and multi-module with the args kept as serialized JSON.
        """
        rng = self.rng
        licensee = rng.choice(self.licensees)
        extra = {"licenseeNumber": licensee["number"]}

        shape = rng.random()
        if shape < 0.12:                                    # Scenario A — dry run
            extra["productNumber"] = licensee["product"]
            extra["isDryRun"] = True
        elif shape < 0.80:                                  # Scenario B — node-locked, one module
            extra["moduleNumber"] = licensee["module"]
            extra["nodeId"] = "".join(rng.choices(
                string.ascii_letters + string.digits + "_-", k=43))
        else:                                               # Scenario C — multiple modules
            extra["validationArgs"] = json.dumps([
                {"nodeSecret": "".join(rng.choices(string.hexdigits.upper(), k=12)),
                 "productModuleNumber": rng.choice(self.modules)}
                for _ in range(rng.randint(2, 4))
            ], separators=(",", ":"))

        # The licensing verdict, derived from the licensee's own expiry date and *when this call
        # happened*. That is what makes the outcome coherent over time: the same licensee reports
        # VALID early in the window, then EXPIRING_SOON, then GRACE_PERIOD, then EXPIRED — which is
        # exactly the transition a renewal-warning query has to be able to see. A dry run is a
        # "what would happen" probe, so it reports an outcome too; that is why anyone calls it.
        when = self._when()
        valid_until = licensee["validUntil"]
        days_left = (valid_until - when).total_seconds() / 86400

        if rng.random() < 0.01:
            # Revoked key, wrong product, malformed request — unrelated to expiry.
            outcome = "INVALID"
        elif days_left < -GRACE_PERIOD_DAYS:
            outcome = "EXPIRED"
        elif days_left < 0:
            outcome = "GRACE_PERIOD"
        elif days_left <= WARNING_WINDOW_DAYS:
            outcome = "EXPIRING_SOON"
        else:
            outcome = "VALID"

        extra["validationOutcome"] = outcome
        if outcome != "INVALID":
            extra["validUntil"] = _iso(valid_until)

        # Validations are machine-to-machine and rarely fail outright: an expired license is a
        # SUCCESSful call with an EXPIRED outcome, not an API failure.
        return self._event(when, "licensee/validate", extra,
                           source="netlicensing/core", licensee=licensee, failure_rate=0.01)

    def public_events(self):
        """Unauthenticated / console traffic.

        The spec puts these on the `V00000000` placeholder tenant. This script publishes them as
        the seed tenant instead: AuditFlow routes per tenant, and only the tenants under
        `tenants/` have a ClickHouse pipeline — a `V00000000` event would be accepted and then
        land nowhere, making the whole scenario invisible in every query. `netlicensing/EXTERNAL`
        plus the absent licensee context is what marks it as unauthenticated here. A real
        deployment provisions `V00000000` as a tenant of its own.
        """
        return self._event(self._when(), _pick(self.rng, PUBLIC_METHODS), {},
                           source="netlicensing/EXTERNAL")

    # ── Payment Gateway ──────────────────────────────────────────────────────────────────────

    def payment_lifecycle(self):
        """One transaction, start to finish, as a sequence of correlated events.

        Every step shares the `transactionNumber` *and* the `correlationId`, so the funnel is one
        `GROUP BY` away and a stuck payment can be traced by a single id. The paths mirror the
        Payment Gateway OpenAPI: happy path (create → confirmation → pay → return → close),
        customer abandonment (cancel), and vendor-side removal (delete).
        """
        rng = self.rng
        licensee = rng.choice(self.licensees)
        tx = f"TR-{rng.randint(100000, 999999)}"
        correlation = _correlation_id(rng)

        gross = round(rng.choice([19.0, 49.0, 99.0, 149.0, 299.0, 499.0, 1200.0])
                      * rng.choice([1, 1, 1, 2, 3]), 2)
        tax_rate = TAX_RATES.get(licensee["country"], 0.0)
        net = round(gross / (1 + tax_rate), 2)
        period = _pick(rng, BILLING_PERIODS)
        money = {
            "transactionNumber": tx,
            "paymentMethod": _pick(rng, PAYMENT_METHODS),
            "currency": licensee["currency"],
            "grossAmount": gross,
            "netAmount": net,
            "taxAmount": round(gross - net, 2),
            "customerCountry": licensee["country"],
            "billingPeriod": period,
            # The licensee the payment is for. Without it, "paying customers" is unanswerable —
            # and an empty string in a String column would silently count as one customer.
            "licenseeNumber": licensee["number"],
        }

        outcome = rng.random()
        if outcome < 0.74:
            steps = ["payments/create", "checkout-sessions/confirmation", "payments/pay",
                     "checkout-sessions/return", "webhooks/receive", "payments/close"]
        elif outcome < 0.92:
            steps = ["payments/create", "checkout-sessions/confirmation",
                     "checkout-sessions/cancel"]
        else:
            steps = ["payments/create", "payments/delete"]

        events, when = [], self._when()
        for step in steps:
            event = self._event(when, step, dict(money),
                                source="netlicensing/payment-gateway", licensee=licensee,
                                failure_rate=0.02)
            event["correlationId"] = correlation
            events.append(event)
            when += timedelta(seconds=rng.randint(2, 900))

        # Recurring subscriptions renew for as long as the window allows: a new transaction each
        # period, same licensee and amount. This is what makes "payments per month" a trend rather
        # than a single bar.
        if period in ("MONTH", "YEAR") and outcome < 0.74:
            step_days = 30 if period == "MONTH" else 365
            renewal = when + timedelta(days=step_days)
            while renewal < self.now and rng.random() < 0.85:
                renewal_tx = dict(money, transactionNumber=f"TR-{rng.randint(100000, 999999)}")
                renewal_correlation = _correlation_id(rng)
                for step in ("payments/create", "payments/pay", "payments/close"):
                    event = self._event(renewal, step, dict(renewal_tx),
                                        source="netlicensing/payment-gateway",
                                        licensee=licensee, failure_rate=0.02)
                    event["correlationId"] = renewal_correlation
                    events.append(event)
                    renewal += timedelta(seconds=rng.randint(2, 300))
                renewal += timedelta(days=step_days)

        return events


def generate_events(tenant, count=2000, days=30, seed=None):
    """Build ~`count` events over the last `days`, sorted by event time."""
    gen = Generator(tenant, days, seed)
    rng = gen.rng
    events = []

    # Payment lifecycles first: each yields several events, so they set their own pace.
    while len(events) < count * 0.14:
        events.extend(gen.payment_lifecycle())

    while len(events) < count:
        roll = rng.random()
        if roll < 0.58:
            events.append(gen.validation_event())
        elif roll < 0.94:
            events.append(gen.crud_event())
        else:
            events.append(gen.public_events())

    events.sort(key=lambda e: e["eventTime"])
    return events


# ─────────────────────────────────────────────────────────────────────────────────────────────
# Publishing
# ─────────────────────────────────────────────────────────────────────────────────────────────

def publish(events, url, verbose=True):
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
            # A connection error on the very first events means the stack is not reachable;
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
    parser.add_argument("--events", type=int, default=2000,
                        help="approximate number of events (default: %(default)s)")
    parser.add_argument("--days", type=int, default=30,
                        help="how far back the stream reaches (default: %(default)s)")
    parser.add_argument("--seed", type=int, default=None,
                        help="RNG seed, for a reproducible stream")
    parser.add_argument("--dry-run", action="store_true",
                        help="print events as JSON lines instead of publishing")
    args = parser.parse_args()

    events = generate_events(args.tenant, args.events, args.days, args.seed)

    if args.dry_run:
        for event in events:
            print(json.dumps(event))
        return 0

    methods = len({e["extra"]["actionMethod"] for e in events})
    print(f"Generated {len(events)} events across {methods} API methods, "
          f"spanning {args.days} days.")
    print(f"Publishing to {args.url} as tenant '{args.tenant}'...")

    ok, failed = publish(events, args.url)
    print(f"Published: {ok}   Failed: {failed}")

    if failed:
        print("Some events were rejected — check that the stack is up and the tenant exists.",
              file=sys.stderr)
        return 1

    print("\nQuery it:  just ch-stats   |   just ch-events   |   "
          "examples/clickhouse/NETLICENSING_EVENTS.md")
    return 0


if __name__ == "__main__":
    sys.exit(main())
