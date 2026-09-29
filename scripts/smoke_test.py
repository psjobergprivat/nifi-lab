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

    for scenario_check in (check_routing, check_transform, check_split_merge, check_errors_retry,
                           check_back_pressure, check_provenance):
        scenario_check(lab)
    return finish()


# --- phase 2 scenarios ---------------------------------------------------------------------------------------

def scenario_api(lab, sid, path, body=None, content_type="text/plain"):
    status, raw = call("POST" if path != "controls" else "PUT", f"{lab}/api/scenarios/{sid}/{path}", body,
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


def finish():
    failed = results.count(False)
    print(f"\n{len(results) - failed}/{len(results)} checks passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
