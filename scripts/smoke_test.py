#!/usr/bin/env python3
"""End-to-end smoke test for a running NiFi Lab (docker compose up, or quarkus:dev + NiFi).

Usage: python3 scripts/smoke_test.py [--lab http://localhost:8080] [--listen http://localhost:9101/hello]
Needs only the Python standard library.
"""
import argparse
import http.client
import json
import ssl
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

SCENARIO = "hello-flowfile"
results = []


def check(name, ok, detail=""):
    results.append(ok)
    print(f"  {'PASS' if ok else 'FAIL'}  {name}{'  - ' + detail if detail else ''}")
    return ok


def call(method, url, body=None, headers=None, timeout=30):
    data = body.encode() if isinstance(body, str) else body
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    ctx = ssl._create_unverified_context()  # NiFi's self-signed certificate
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as r:
            raw = r.read()
            return r.status, raw
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def get_json(url):
    status, raw = call("GET", url)
    return status, json.loads(raw) if raw else None


def post(lab, path, body=None, content_type="text/plain"):
    status, raw = call("POST", f"{lab}/api/scenarios/{SCENARIO}/{path}", body,
                       {"Content-Type": content_type} if body is not None else {})
    return status, json.loads(raw) if raw else None


def wait_for(description, predicate, timeout):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            last = predicate()
            if last:
                return last
        except (urllib.error.URLError, ConnectionError, OSError) as e:
            last = e
        time.sleep(2)
    print(f"  ...gave up waiting for {description} ({last})")
    return None


class Sse:
    """Collects events from /api/live/<scenario> in a background thread."""

    def __init__(self, lab, scenario=SCENARIO):
        self.events = []
        self.response = urllib.request.urlopen(f"{lab}/api/live/{scenario}", timeout=120)
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        try:
            for line in self.response:
                line = line.decode().strip()
                if line.startswith("data:"):
                    self.events.append(json.loads(line[5:]))
        except Exception:
            pass

    def items(self, lane=None):
        return [e["data"] for e in list(self.events) if e["type"] == "item" and (lane is None or e["data"]["lane"] == lane)]

    def wait(self, description, predicate, timeout=30):
        deadline = time.time() + timeout
        while time.time() < deadline:
            for e in list(self.events):
                if predicate(e):
                    return e
            time.sleep(0.3)
        print(f"  ...no SSE event for {description}")
        return None

    def close(self):
        self.response.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--lab", default="http://localhost:8080")
    parser.add_argument("--listen", default="http://localhost:9101/hello", help="scenario 01 ListenHTTP, as seen from here")
    args = parser.parse_args()
    lab = args.lab

    print("Backend and NiFi")
    nifi = wait_for("NiFi to be reachable", lambda: (lambda s, j: j if s == 200 and j["reachable"] else None)(
        *get_json(f"{lab}/api/nifi")), 240)
    if not check("backend reaches NiFi", bool(nifi), nifi and f"NiFi {nifi['version']}"):
        return finish()
    status, _ = call("GET", nifi["uiUrl"])
    check("NiFi UI answers at the deep-link base", status == 200, nifi["uiUrl"])

    print("Web UI")
    status, html = call("GET", f"{lab}/")
    check("index.html is served", status == 200 and b'<div id="root">' in html)
    script = html.decode().split('src="')[1].split('"')[0] if b'src="' in html else None
    status, _ = call("GET", f"{lab}{script}") if script else (0, None)
    check("UI bundle is served", status == 200, script or "no script tag")

    print("Catalogue and deployment")
    scenario = wait_for("scenario to be deployed", lambda: (lambda s, j: j if j["deployment"]["state"] == "DEPLOYED" else None)(
        *get_json(f"{lab}/api/scenarios/{SCENARIO}")), 180)
    if not check("scenario deployed to NiFi", bool(scenario)):
        return finish()
    status, catalogue = get_json(f"{lab}/api/scenarios")
    check("catalogue lists the scenario", status == 200 and catalogue[0]["id"] == SCENARIO)
    check("guided steps link into NiFi", all(s["nifiLink"] and s["nifiLink"].startswith(nifi["uiUrl"]) for s in scenario["steps"]),
          f"{len(scenario['steps'])} steps")
    status, flow = get_json(f"{lab}/api/scenarios/{SCENARIO}/flow")
    check("flow diagram data: 5 processors, 4 connections",
          status == 200 and len(flow["nodes"]) == 5 and len(flow["edges"]) == 4)

    print("Run the scenario")
    status, d = post(lab, "reset")
    check("reset -> stopped", status == 200 and d["runState"] == "STOPPED")
    status, err = post(lab, "input", "should be refused")
    check("input while stopped is refused with a hint", status == 409 and "start the scenario" in err["message"], err and err["message"][:60])

    sse = Sse(lab)
    status, d = post(lab, "start")
    check("start -> running", status == 200 and d["runState"] == "RUNNING")
    running = sse.wait("all processors running", lambda e: e["type"] == "status"
                       and len(e["data"]["processors"]) == 5
                       and all(p["runStatus"] == "Running" for p in e["data"]["processors"]))
    check("live status shows 5 running processors", bool(running))

    # ListenHTTP may need a moment after start before it accepts connections.
    sent = wait_for("ListenHTTP to accept input", lambda: (lambda s, j: j if s == 200 else None)(
        *post(lab, "input", "hello nifi")), 30)
    check("input accepted", bool(sent), sent and f"trace {sent['traceId'][:8]}")
    if sent:
        item = sse.wait("the result item", lambda e: e["type"] == "item" and e["data"]["traceId"] == sent["traceId"])
        check("result delivered back over SSE", bool(item))
        if item:
            data = item["data"]
            check("content transformed", data["content"] == "Hello from NiFi! You wrote: HELLO NIFI", repr(data["content"]))
            attrs = data["attributes"]
            check("attributes added by UpdateAttribute", {"received.at", "message.size.bytes", "greeting.language"} <= attrs.keys())
            check("core + lab attributes travel along", {"uuid", "filename", "lab.trace.id", "lab.scenario"} <= attrs.keys())
            check("message.size.bytes = 10", attrs.get("message.size.bytes") == "10")
        counted = sse.wait("processor counters", lambda e: e["type"] == "status"
                           and any(p["flowFilesOut"] > 0 for p in e["data"]["processors"]), 15)
        check("processor counters move", bool(counted))

    print("Errors and bulletins")
    # http.client keeps header-name case; ListenHTTP's "lab\..*" header pattern is case-sensitive.
    url = urllib.parse.urlsplit(args.listen)
    conn = http.client.HTTPConnection(url.hostname, url.port, timeout=10)
    conn.request("POST", url.path, "will fail",
                 {"lab.sink.url": "http://no-such-host.invalid", "lab.trace.id": "smoke-broken"})
    status = conn.getresponse().status
    conn.close()
    check("bad delivery injected directly into ListenHTTP", status == 200)
    bulletin = sse.wait("an ERROR bulletin", lambda e: e["type"] == "bulletin" and e["data"]["level"] == "ERROR"
                        and "no-such-host" in e["data"]["message"], 30)
    check("NiFi bulletin reaches the lab", bool(bulletin), bulletin and bulletin["data"]["sourceName"])

    print("Reset")
    status, d = post(lab, "reset")
    check("reset -> stopped", status == 200 and d["runState"] == "STOPPED")
    check("reset event published", bool(sse.wait("reset event", lambda e: e["type"] == "reset", 10)))
    empty = sse.wait("status with empty queues after reset", lambda e: e["type"] == "status"
                     and e["timestamp"] > time.time() * 1000 - 5000 and e["data"]["queuedCount"] == 0
                     and all(p["runStatus"] != "Running" for p in e["data"]["processors"]), 15)
    check("queues empty and processors stopped", bool(empty))
    sse.close()

    for scenario_check in (check_routing, check_transform, check_split_merge, check_enrichment, check_errors_retry,
                           check_back_pressure, check_provenance, check_scheduling, check_external_systems,
                           check_deduplication, check_parameters):
        scenario_check(lab)
    return finish()


# --- phase 2 scenarios ---------------------------------------------------------------------------------------

def scenario_api(lab, sid, path, body=None, content_type="text/plain"):
    method = "PUT" if path.split("?")[0] in ("controls", "lookup") else "POST"
    status, raw = call(method, f"{lab}/api/scenarios/{sid}/{path}", body,
                       {"Content-Type": content_type} if body is not None else {})
    return status, json.loads(raw) if raw else None


def begin(lab, sid, controls=None):
    """Reset, apply controls, start, and open the live stream. Returns (sse, scenario detail)."""
    print(sid)
    sse = Sse(lab, sid)
    scenario_api(lab, sid, "reset")
    if controls:
        scenario_api(lab, sid, "controls", json.dumps(controls), "application/json")
    status, _ = scenario_api(lab, sid, "start")
    _, detail = get_json(f"{lab}/api/scenarios/{sid}")
    check(f"{sid} started", status == 200)
    time.sleep(2)  # ListenHTTP needs a moment after start before it accepts connections
    return sse, detail


def send_samples(lab, sid, detail, names=None):
    for sample in detail["samples"]:
        if names is None or sample["name"] in names:
            status, _ = scenario_api(lab, sid, "input", sample["content"])
            if status != 200:
                check(f"sample '{sample['name']}' accepted", False, f"HTTP {status}")


def finish_scenario(lab, sid, sse):
    scenario_api(lab, sid, "reset")
    sse.close()


def check_routing(lab):
    sse, detail = begin(lab, "routing")
    send_samples(lab, "routing", detail)
    sse.wait("7 routed items", lambda e: len(sse.items()) >= 7, 20)
    lanes = {}
    for item in sse.items():
        lanes.setdefault(item["lane"], []).append(json.loads(item["content"])["orderId"] if item["lane"] != "invalid" else "x")
    check("orders routed to eu / us / unmatched", lanes.get("eu") and lanes.get("us") and lanes.get("unmatched"), str(lanes))
    check("big Swedish order copied into eu AND high-value",
          "o-1003" in lanes.get("eu", []) and "o-1003" in lanes.get("high-value", []))
    check("non-JSON goes to invalid", len(lanes.get("invalid", [])) == 1)
    finish_scenario(lab, "routing", sse)


def check_transform(lab):
    sse, detail = begin(lab, "transform")
    send_samples(lab, "transform", detail, ["Sales CSV", "Broken CSV"])
    sse.wait("5 results", lambda e: len(sse.items()) >= 5, 20)
    lanes = {i["lane"]: i["content"] for i in sse.items()}
    summary = json.loads(lanes.get("summary", "[]"))
    check("CSV converted to JSON", len(json.loads(lanes.get("as-json", "[]"))) == 5)
    check("SQL summary groups by product", summary and summary[0] == {"product": "coffee", "units": 43, "revenue": 193.5},
          str(summary[:1]))
    check("reshaped records have renamed/computed fields", '"line_total"' in lanes.get("reshaped", ""))
    check("broken CSV goes to failure", "lots" in lanes.get("failure", ""))
    finish_scenario(lab, "transform", sse)


def check_split_merge(lab):
    sse, detail = begin(lab, "split-merge")
    send_samples(lab, "split-merge", detail, ["Shopping list"])
    merged = sse.wait("merged text", lambda e: e["type"] == "item" and e["data"]["lane"] == "merged", 30)
    check("split into 4 lines", len(sse.items("line")) == 4)
    check("merged back in order", merged and merged["data"]["content"] == "#1/4: MILK\n#2/4: BREAD\n#3/4: EGGS\n#4/4: COFFEE",
          merged and repr(merged["data"]["content"]))
    finish_scenario(lab, "split-merge", sse)


def check_errors_retry(lab):
    sse, detail = begin(lab, "errors-retry", {"service.mode": "down"})
    send_samples(lab, "errors-retry", detail, ["Payment", "Invalid payment"])
    rejected = sse.wait("rejected item", lambda e: e["type"] == "item" and e["data"]["lane"] == "rejected", 20)
    check("4xx is not retried (rejected at once)", bool(rejected))
    dead = sse.wait("dead-letter item", lambda e: e["type"] == "item" and e["data"]["lane"] == "dead-letter", 45)
    attempts = [e["data"]["attempt"] for e in sse.events if e["type"] == "service" and e["data"]["status"] == 503]
    check("5xx retried 3 times, then dead letter", bool(dead) and max(attempts or [0]) == 4, f"attempts {attempts}")
    # Service back up: replay the dead letter
    scenario_api(lab, "errors-retry", "controls", json.dumps({"service.mode": "normal"}), "application/json")
    if dead:
        scenario_api(lab, "errors-retry", "input", dead["data"]["content"])
    paid = sse.wait("replayed payment paid", lambda e: e["type"] == "item" and e["data"]["lane"] == "paid", 20)
    check("replayed dead letter gets paid", bool(paid))
    finish_scenario(lab, "errors-retry", sse)


def check_back_pressure(lab):
    sse, _ = begin(lab, "back-pressure", {"producer.rate": "10", "service.latency-ms": "500"})
    refused = sse.wait("503 refusals", lambda e: e["type"] == "producer" and e["data"]["refused"] > 0, 40)
    check("producer is pushed back with 503", bool(refused), refused and f"{refused['data']['refused']} refused")
    full = sse.wait("full queues", lambda e: e["type"] == "status"
                    and sum(1 for c in e["data"]["connections"] if c["queuedCount"] >= 20) >= 2, 20)
    check("two queues reach their back pressure threshold", bool(full))
    scenario_api(lab, "back-pressure", "controls", json.dumps({"producer.rate": "0", "service.latency-ms": "0"}),
                 "application/json")
    accepted = [e["data"]["accepted"] for e in sse.events if e["type"] == "producer"][-1]
    drained = sse.wait("all accepted events delivered", lambda e: len(sse.items()) >= accepted, 40)
    check("no accepted event is lost", bool(drained), f"{len(sse.items())}/{accepted} delivered")
    finish_scenario(lab, "back-pressure", sse)


def check_provenance(lab):
    sse, detail = begin(lab, "provenance")
    send_samples(lab, "provenance", detail)
    sse.wait("3 country groups", lambda e: len(sse.items()) >= 3, 20)
    groups = {i["attributes"].get("order.country"): i for i in sse.items()}
    de = groups.get("DE")
    check("orders regrouped per country across batches",
          de is not None and "b1-4" in de["content"] and "b2-1" in de["content"], str(sorted(groups)))
    if de:
        time.sleep(2)  # provenance is indexed asynchronously
        status, view = get_json(f"{lab}/api/lineage/{de['attributes']['uuid']}")
        types = [e["eventType"] for e in view["lineage"]["events"]] if status == 200 else []
        check("lineage reaches back to both batches (2 RECEIVE, FORK, JOIN, SEND)",
              types.count("RECEIVE") == 2 and "FORK" in types and "JOIN" in types and "SEND" in types, str(types))
    finish_scenario(lab, "provenance", sse)


# --- phase 3 scenarios ---------------------------------------------------------------------------------------

def check_enrichment(lab):
    sse, detail = begin(lab, "enrichment")
    send_samples(lab, "enrichment", detail, ["ACME order", "Unknown customer"])
    sse.wait("2 items", lambda e: len(sse.items()) >= 2, 20)
    enriched = sse.items("enriched")
    check("order enriched with customer from the lookup service",
          enriched and '"ACME Corporation"' in enriched[0]["content"])
    check("unknown customer goes to its own lane", len(sse.items("unknown-customer")) == 1)
    rows = detail["lookup"]["rows"] + [{"customerId": "c-999", "name": "Late Ltd", "tier": "new", "country": "NO"}]
    status, _ = scenario_api(lab, "enrichment", "lookup", json.dumps(rows), "application/json")
    check("lookup table can be edited", status == 200)
    scenario_api(lab, "enrichment", "input", '{"orderId": "o-9", "customerId": "c-999", "amount": 1}')
    late = sse.wait("re-sent order enriched", lambda e: e["type"] == "item" and e["data"]["lane"] == "enriched"
                    and "Late Ltd" in e["data"]["content"], 20)
    check("edited lookup data is used by the next lookup", bool(late))
    finish_scenario(lab, "enrichment", sse)


def check_scheduling(lab):
    sse, _ = begin(lab, "scheduling")
    # Files stay in the folder (and are listed again after a reset), so each run uses its own content.
    run = str(int(time.time()))
    first, second = f"first file {run}", f"second file {run}"
    scenario_api(lab, "scheduling", "input?target=event", "as event")
    scenario_api(lab, "scheduling", "input?target=folder", first)
    status, _ = scenario_api(lab, "scheduling", "input?target=nope", "x")
    check("unknown input target is rejected", status == 400)
    folder = sse.wait("file picked up from the folder", lambda e: e["type"] == "item" and e["data"]["content"] == first, 25)
    sse.wait("a cron firing", lambda e: e["type"] == "item" and e["data"]["lane"] == "cron", 20)
    lanes = {i["lane"] for i in sse.items()}
    check("event, folder, timer and cron lanes all receive data", {"event", "folder", "timer", "cron"} <= lanes, str(lanes))
    check("polled file carries its content", bool(folder))
    timers = sorted(i["receivedAt"] for i in sse.items("timer"))
    gaps = [b - a for a, b in zip(timers, timers[1:])]
    check("timer fires every ~5 s", gaps and all(4000 <= g <= 6000 for g in gaps), str(gaps))
    # State is polled once a second, so allow a moment after the pickup.
    state = sse.wait("ListFile state", lambda e: e["type"] == "state" and e["data"]
                     and any(en["key"] == "listing.timestamp" for en in e["data"][0]["entries"]), 5)
    check("ListFile's listing timestamp is visible as processor state", bool(state))
    # A second file: only the new one is listed, thanks to that state.
    before = len(sse.items("folder"))
    scenario_api(lab, "scheduling", "input?target=folder", second)
    sse.wait("second file", lambda e: e["type"] == "item" and e["data"]["content"] == second, 25)
    time.sleep(11)  # one more listing round
    contents = [i["content"] for i in sse.items("folder")[before:]]
    check("already listed files are not listed again", contents == [second], str(contents))
    finish_scenario(lab, "scheduling", sse)


def check_external_systems(lab):
    sse, detail = begin(lab, "external-systems")
    send_samples(lab, "external-systems", detail, ["Order", "Two orders"])
    polled = sse.wait("rows polled back", lambda e: e["type"] == "item" and e["data"]["lane"] == "polled", 30)
    check("published to Kafka", len(sse.items("published")) == 2)
    check("consumed from Kafka and stored in PostgreSQL (trace id kept via Kafka headers)",
          len(sse.items("stored")) == 2 and all(i["traceId"] for i in sse.items("stored")))
    check("new rows polled by QueryDatabaseTableRecord", bool(polled))
    table = sse.wait("3 rows in the table", lambda e: e["type"] == "database" and len(e["data"]["rows"]) == 3, 15)
    check("backend sees the 3 rows in PostgreSQL", bool(table))
    state = sse.wait("max id in state", lambda e: e["type"] == "state" and e["data"]
                     and any(en["value"] == "3" for en in e["data"][0]["entries"]), 15)
    check("poller remembers the highest id (3) in processor state", bool(state))
    finish_scenario(lab, "external-systems", sse)


def check_deduplication(lab):
    sse, detail = begin(lab, "deduplication")
    send_samples(lab, "deduplication", detail)
    sse.wait("4 items", lambda e: len(sse.items()) >= 4, 20)
    dup = sse.items("duplicates")
    check("repeated event id goes to duplicates, with the original's description",
          len(dup) == 1 and "event #" in dup[0]["attributes"].get("original.flowfile.description", ""),
          str([(i["lane"], i["content"], i["attributes"].get("original.flowfile.description")) for i in sse.items()]))
    check("event without id goes to invalid at once", len(sse.items("invalid")) == 1)
    check("two unique events", len(sse.items("unique")) == 2)
    finish_scenario(lab, "deduplication", sse)
    sse, _ = begin(lab, "deduplication")
    scenario_api(lab, "deduplication", "input", '{"eventId": "e-1"}')
    again = sse.wait("e-1 after reset", lambda e: e["type"] == "item", 15)
    check("reset empties the cache and the counter",
          again and again["data"]["lane"] == "unique" and again["data"]["attributes"].get("event.number") == "1")
    finish_scenario(lab, "deduplication", sse)


def check_parameters(lab):
    sse, _ = begin(lab, "parameters")

    def route(amount):
        before = len(sse.items())
        scenario_api(lab, "parameters", "input", json.dumps({"orderId": "p", "amount": amount}))
        sse.wait("routed order", lambda e: len(sse.items()) > before, 15)
        item = sse.items()[-1] if len(sse.items()) > before else None
        return item and (item["lane"], item["attributes"].get("env.name"), json.loads(item["content"]).get("apiToken"))

    check("dev: 500 is above threshold 100, dev token used", route(500) == ("above-threshold", "development", "dev-****"))
    status, values = scenario_api(lab, "parameters", "controls", json.dumps({"environment": "prod"}), "application/json")
    check("switching to prod updates the threshold control", status == 200 and values.get("threshold") == "1000", str(values))
    check("prod: same order is below threshold 1000, prod token used",
          route(500) == ("below-threshold", "production", "prod****"))
    scenario_api(lab, "parameters", "controls", json.dumps({"threshold": "200"}), "application/json")
    check("changing the parameter changes routing without editing the flow", route(500)[0] == "above-threshold")
    finish_scenario(lab, "parameters", sse)
    _, detail = get_json(f"{lab}/api/scenarios/parameters")
    check("reset restores dev / 100", detail["controlValues"] == {"environment": "dev", "threshold": "100"},
          str(detail["controlValues"]))


def finish():
    failed = results.count(False)
    print(f"\n{len(results) - failed}/{len(results)} checks passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
