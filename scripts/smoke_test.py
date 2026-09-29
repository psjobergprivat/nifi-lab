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

    def __init__(self, lab):
        self.events = []
        self.response = urllib.request.urlopen(f"{lab}/api/live/{SCENARIO}", timeout=120)
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        try:
            for line in self.response:
                line = line.decode().strip()
                if line.startswith("data:"):
                    self.events.append(json.loads(line[5:]))
        except Exception:
            pass

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
    return finish()


def finish():
    failed = results.count(False)
    print(f"\n{len(results) - failed}/{len(results)} checks passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
