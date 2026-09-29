#!/usr/bin/env python3
"""Generates every scenario's flow.json.  Run:  python3 scripts/flowgen/scenarios.py

Each function builds one scenario flow. Component names are what scenario.yaml refers to (guided steps).
"""
import os

from flowgen import COL, RECORD_SERIALIZATION, UPDATE_ATTRIBUTE, Flow

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "lab-backend", "src", "main", "resources", "scenarios")
UPDATE = "org.apache.nifi.processors.attributes.UpdateAttribute"
MOCK_SERVICE = "${lab.backend.url}/mock/${lab.scenario}/service"


def hello_flowfile():
    f = Flow("hello-flowfile", "Hello FlowFile")
    receive = f.listen("Receive message", 9101, "hello")
    add = f.processor("Add attributes", UPDATE, {
        "received.at": "${now():format('yyyy-MM-dd HH:mm:ss')}",
        "message.size.bytes": "${fileSize}",
        "greeting.language": "en"}, at=(0, 1), artifact=UPDATE_ATTRIBUTE,
        dynamic=["received.at", "message.size.bytes", "greeting.language"])
    transform = f.processor("Transform content", "ReplaceText", {
        "Replacement Strategy": "Regex Replace", "Evaluation Mode": "Entire text",
        "Search Value": "(?s)(^.*$)", "Replacement Value": "Hello from NiFi! You wrote: ${'$1':toUpper()}",
        "Character Set": "UTF-8", "Maximum Buffer Size": "1 MB"}, at=(0, 2), auto_terminate=["failure"])
    deliver = f.deliver(at=(0, 3), url="${lab.sink.url}/output")
    f.connect(receive, add)
    f.connect(add, transform)
    f.connect(transform, deliver)
    return f


def routing():
    f = Flow("routing", "Content-based routing")
    receive = f.listen("Receive orders", 9102, "orders")
    extract = f.processor("Extract order fields", "EvaluateJsonPath", {
        "Destination": "flowfile-attribute", "Return Type": "scalar", "Path Not Found Behavior": "ignore",
        "order.id": "$.orderId", "order.country": "$.country", "order.amount": "$.amount",
        "order.priority": "$.priority"}, at=(0, 1),
        dynamic=["order.id", "order.country", "order.amount", "order.priority"])
    route = f.processor("Route orders", "RouteOnAttribute", {
        "Routing Strategy": "Route to Property name",
        "high-value": "${order.amount:toDecimal():ge(1000)}",
        "eu": "${order.country:toUpper():in('SE','DE','FR','NL','ES','IT','FI','DK','NO','PL')}",
        "us": "${order.country:toUpper():equals('US')}"}, at=(0, 2), dynamic=["high-value", "eu", "us"])
    lane = f.set_lane("Set lane from route", "${RouteOnAttribute.Route:replaceNull('invalid')}", at=(0, 3))
    deliver = f.deliver(at=(0, 4))
    f.connect(receive, extract)
    f.connect(extract, route, ["matched"])
    f.connect(extract, lane, ["failure", "unmatched"])
    f.connect(route, lane, ["high-value", "eu", "us", "unmatched"])
    f.connect(lane, deliver)
    f.label("Route to Property name: every rule that matches gets its own copy.\n"
            "A 1500 EUR order from Sweden goes to BOTH 'high-value' and 'eu'.", at=(1, 2))
    return f


def transform():
    f = Flow("transform", "Format conversion and transformation")
    csv = f.service("CSV reader", "org.apache.nifi.csv.CSVReader",
                    "org.apache.nifi.serialization.RecordReaderFactory", {
                        "Schema Access Strategy": "infer-schema", "Treat First Line as Header": "true"})
    json_writer = f.service("JSON writer", "org.apache.nifi.json.JsonRecordSetWriter",
                            "org.apache.nifi.serialization.RecordSetWriterFactory", {
                                "Pretty Print JSON": "true", "Output Grouping": "output-array"})
    receive = f.listen("Receive CSV", 9103, "csv")
    query = f.processor("Query records", "QueryRecord", {
        "Record Reader": csv["identifier"], "Record Writer": json_writer["identifier"],
        "Include Zero Record FlowFiles": "false",
        "as-json": "SELECT * FROM FLOWFILE",
        "reshaped": "SELECT UPPER(product) AS item, quantity AS qty, quantity * price AS line_total "
                    "FROM FLOWFILE",
        "summary": "SELECT product, SUM(quantity) AS units, SUM(quantity * price) AS revenue "
                   "FROM FLOWFILE GROUP BY product ORDER BY revenue DESC",
        "big-orders": "SELECT * FROM FLOWFILE WHERE quantity * price >= 100"},
        at=(0, 1), services=["Record Reader", "Record Writer"], auto_terminate=["original"],
        dynamic=["as-json", "reshaped", "summary", "big-orders"])
    lane = f.set_lane("Set lane from query", "${QueryRecord.Route}", at=(0, 2))
    deliver = f.deliver(at=(0, 3))
    f.connect(receive, query)
    f.connect(query, lane, ["as-json", "reshaped", "summary", "big-orders", "failure"])
    f.connect(lane, deliver)
    f.label("QueryRecord reads CSV with the 'CSV reader' service and writes JSON with the 'JSON writer' service.\n"
            "Each SQL property becomes a relationship; one input can produce several outputs.",
            at=(1, 1), width=420, height=100)
    return f


def split_merge():
    f = Flow("split-merge", "Split and merge")
    receive = f.listen("Receive text", 9104, "lines")
    split = f.processor("Split into lines", "SplitText", {
        "Line Split Count": "1", "Header Line Count": "0", "Remove Trailing Newlines": "true"},
        at=(0, 1), auto_terminate=["original", "failure"])
    process = f.processor("Process line", "ReplaceText", {
        "Replacement Strategy": "Regex Replace", "Evaluation Mode": "Entire text",
        "Search Value": "(?s)(^.*$)",
        "Replacement Value": "#${fragment.index}/${fragment.count}: ${'$1':toUpper()}"},
        at=(0, 2), auto_terminate=["failure"])
    tag_line = f.set_lane("Lane = line", "line", at=(0, 3))
    merge = f.processor("Reassemble", "MergeContent", {
        "Merge Strategy": "Defragment", "Merge Format": "Binary Concatenation",
        "Delimiter Strategy": "Text", "Demarcator": "\n", "Max Bin Age": "30 sec"},
        at=(1, 3), auto_terminate=["original", "failure"])
    tag_merged = f.set_lane("Lane = merged", "merged", at=(1, 4))
    deliver = f.deliver(at=(0, 5))
    f.connect(receive, split)
    f.connect(split, process, ["splits"])
    f.connect(process, tag_line)
    f.connect(tag_line, deliver)
    f.connect(tag_line, merge)
    f.connect(merge, tag_merged, ["merged"])
    f.connect(tag_merged, deliver)
    f.label("SplitText gives every line fragment.identifier / fragment.index / fragment.count.\n"
            "MergeContent 'Defragment' waits for all fragments and puts them back in order.",
            at=(1, 1), width=420, height=100)
    return f


def errors_retry():
    f = Flow("errors-retry", "Error handling and retry")
    receive = f.listen("Receive payment", 9106, "payments")
    call = f.processor("Call payment service", "InvokeHTTP", {
        "HTTP Method": "POST", "HTTP URL": MOCK_SERVICE,
        "Request Header Attributes Pattern": "lab\\.trace\\.id", "Socket Read Timeout": "3 secs"},
        at=(0, 1), auto_terminate=["Original"], penalty="2 sec",
        retry={"relationships": ["Retry", "Failure"], "count": 3, "max_backoff": "20 sec"})
    paid = f.set_lane("Lane = paid", "paid", at=(0, 2))
    rejected = f.set_lane("Lane = rejected", "rejected", at=(1, 2))
    dead = f.set_lane("Lane = dead-letter", "dead-letter", at=(2, 2))
    deliver = f.deliver(at=(1, 3))
    f.connect(receive, call)
    f.connect(call, paid, ["Response"])
    f.connect(call, rejected, ["No Retry"])
    f.connect(call, dead, ["Retry", "Failure"])
    for lane in (paid, rejected, dead):
        f.connect(lane, deliver)
    f.label("Retry (5xx) and Failure (timeout, connection refused) are retried 3 times,\n"
            "with a growing penalty between attempts. After that they go to the dead-letter lane.\n"
            "No Retry (4xx) means the request itself is wrong - retrying will not help.",
            at=(1, 1), width=520, height=100)
    return f


def back_pressure():
    f = Flow("back-pressure", "Back pressure and flow control")
    receive = f.listen("Receive events", 9107, "events")
    tag = f.processor("Tag event", UPDATE, {"received.at": "${now():toNumber()}"}, at=(0, 1),
                      artifact=UPDATE_ATTRIBUTE, dynamic=["received.at"])
    consume = f.processor("Slow consumer", "InvokeHTTP", {
        "HTTP Method": "POST", "HTTP URL": MOCK_SERVICE,
        "Request Header Attributes Pattern": "lab\\.trace\\.id", "Socket Read Timeout": "30 secs"},
        at=(0, 2), auto_terminate=["Original", "Retry", "No Retry", "Failure"])
    lane = f.set_lane("Lane = processed", "processed", at=(0, 3))
    deliver = f.deliver(at=(0, 4))
    f.connect(receive, tag, back_pressure=20)
    f.connect(tag, consume, back_pressure=20)
    f.connect(consume, lane, ["Response"])
    f.connect(lane, deliver)
    f.label("Both queues hold at most 20 FlowFiles (back pressure threshold).\n"
            "When 'Tag event' -> 'Slow consumer' is full, 'Tag event' stops; then its input fills,\n"
            "and ListenHTTP answers 503 - the producer is pushed back.", at=(1, 1), width=520, height=100)
    return f


def provenance():
    f = Flow("provenance", "Data provenance and lineage")
    receive = f.listen("Receive batch", 9108, "batches")
    split = f.processor("Split orders", "SplitJson", {"JsonPath Expression": "$.*"}, at=(0, 1),
                        auto_terminate=["original", "failure"])
    extract = f.processor("Extract country", "EvaluateJsonPath", {
        "Destination": "flowfile-attribute", "Return Type": "scalar", "Path Not Found Behavior": "ignore",
        "order.country": "$.country"}, at=(0, 2), dynamic=["order.country"], auto_terminate=["failure", "unmatched"])
    merge = f.processor("Group by country", "MergeContent", {
        "Merge Strategy": "Bin-Packing Algorithm", "Merge Format": "Binary Concatenation",
        "Correlation Attribute Name": "order.country", "Minimum Number of Entries": "1000",
        "Maximum Number of Entries": "1000", "Max Bin Age": "3 sec",
        "Delimiter Strategy": "Text", "Header": "[", "Footer": "]", "Demarcator": ","},
        at=(0, 3), auto_terminate=["original", "failure"])
    lane = f.set_lane("Lane = by-country", "by-country", at=(0, 4))
    deliver = f.deliver(at=(0, 5))
    f.connect(receive, split)
    f.connect(split, extract, ["split"])
    f.connect(extract, merge, ["matched"])
    f.connect(merge, lane, ["merged"])
    f.connect(lane, deliver)
    f.label("Every output combines orders from one input batch (JOIN),\n"
            "and every order was split from that batch (FORK).\nProvenance records both.",
            at=(1, 2), width=420, height=90)
    return f


FLOWS = [hello_flowfile, routing, transform, split_merge, errors_retry, back_pressure, provenance]

if __name__ == "__main__":
    for build in FLOWS:
        flow = build()
        path = os.path.join(OUT, flow.scenario_id, "flow.json")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        flow.write(path)
        print("wrote", os.path.relpath(path))
