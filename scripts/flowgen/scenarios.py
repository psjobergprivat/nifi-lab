#!/usr/bin/env python3
"""Generates every scenario's flow.json.  Run:  python3 scripts/flowgen/scenarios.py

Each function builds one scenario flow. Component names are what scenario.yaml refers to (guided steps).
"""
import os

from flowgen import COL, RECORD_SERIALIZATION, SERVICES_API, UPDATE_ATTRIBUTE, Flow

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


def json_reader(f, name="JSON reader"):
    return f.service(name, "org.apache.nifi.json.JsonTreeReader", "org.apache.nifi.serialization.RecordReaderFactory",
                     {"Schema Access Strategy": "infer-schema"})


def json_writer(f, name="JSON writer"):
    return f.service(name, "org.apache.nifi.json.JsonRecordSetWriter",
                     "org.apache.nifi.serialization.RecordSetWriterFactory",
                     {"Pretty Print JSON": "true", "Output Grouping": "output-array"})


def enrichment():
    f = Flow("enrichment", "Enrichment and lookup")
    reader = json_reader(f)
    writer = json_writer(f)
    lookup = f.service("Customer lookup", "org.apache.nifi.lookup.RestLookupService", [
        ("org.apache.nifi.lookup.LookupService", SERVICES_API),
        ("org.apache.nifi.lookup.RecordLookupService", SERVICES_API)], {
        # Coordinates (customerId) and FlowFile attributes (lab.*) can both be used in the URL.
        "URL": "${lab.backend.url}/mock/${lab.scenario}/lookup/${customerId}",
        "Record Reader": reader["identifier"]}, artifact="nifi-lookup-services-nar", services=["Record Reader"])
    receive = f.listen("Receive orders", 9105, "orders")
    enrich = f.processor("Enrich with customer", "LookupRecord", {
        "Record Reader": reader["identifier"], "Record Writer": writer["identifier"],
        "Lookup Service": lookup["identifier"], "Routing Strategy": "route-to-matched-unmatched",
        "Record Result Contents": "insert-entire-record", "Record Update Strategy": "use-property",
        "Result RecordPath": "/customer", "customerId": "/customerId"},
        at=(0, 1), services=["Record Reader", "Record Writer", "Lookup Service"], dynamic=["customerId"])
    enriched = f.set_lane("Lane = enriched", "enriched", at=(0, 2))
    unknown = f.set_lane("Lane = unknown-customer", "unknown-customer", at=(1, 2))
    failed = f.set_lane("Lane = failed", "failed", at=(2, 2))
    deliver = f.deliver(at=(1, 3))
    f.connect(receive, enrich)
    f.connect(enrich, enriched, ["matched"])
    f.connect(enrich, unknown, ["unmatched"])
    f.connect(enrich, failed, ["failure"])
    for lane in (enriched, unknown, failed):
        f.connect(lane, deliver)
    f.label("LookupRecord asks the 'Customer lookup' service (RestLookupService) for every record's customerId,\n"
            "and writes the answer into the record's /customer field. Edit the lookup table in the lab and\n"
            "send the same order again - the next lookup sees the change.", at=(1, 1), width=560, height=100)
    return f


def scheduling():
    f = Flow("scheduling", "Scheduling and triggers")
    # Event-driven: data arrives when someone sends it.
    receive = f.listen("Receive request", 9109, "trigger")
    route = f.processor("File or event?", "RouteOnAttribute", {
        "Routing Strategy": "Route to Property name", "to-folder": "${lab.target:equals('folder')}"},
        at=(0, 1), dynamic=["to-folder"])
    name = f.processor("Name the file", UPDATE, {"filename": "dropped-${now():format('HHmmss')}-${uuid:substring(0,4)}.txt"},
                       at=(0, 2), artifact=UPDATE_ATTRIBUTE, dynamic=["filename"])
    put = f.processor("Write to folder", "PutFile", {
        "Directory": "/opt/nifi/lab-inbox", "Conflict Resolution Strategy": "replace"},
        at=(0, 3), auto_terminate=["success", "failure"])
    event = f.set_lane("Lane = event", "event", at=(1, 2))
    # Polling: a timer lists the folder every 10 seconds and picks up new files.
    listing = f.processor("Poll folder (every 10 s)", "ListFile", {
        "Input Directory": "/opt/nifi/lab-inbox", "Listing Strategy": "timestamps"}, at=(2, 0), schedule="10 sec")
    # Files stay in the folder, so ListFile's state (what it has already listed) is what prevents duplicates.
    fetch = f.processor("Fetch file", "FetchFile", {"Completion Strategy": "None"}, at=(2, 1),
                        auto_terminate=["failure", "not.found", "permission.denied"])
    folder_attrs = f.lab_attributes("Add lab attributes (folder)", at=(2, 2))
    folder = f.set_lane("Lane = folder", "folder", at=(2, 3))
    # Timer-driven and cron-driven: NiFi creates data on a schedule.
    timer = f.processor("Every 5 seconds", "GenerateFlowFile", {
        "Custom Text": "Timer fired at ${now():format('HH:mm:ss')}", "Data Format": "Text"},
        at=(3, 0), schedule="5 sec")
    timer_attrs = f.lab_attributes("Add lab attributes (timer)", at=(3, 1))
    timer_lane = f.set_lane("Lane = timer", "timer", at=(3, 2))
    cron = f.processor("Cron: every 15 s on the clock", "GenerateFlowFile", {
        "Custom Text": "Cron fired at ${now():format('HH:mm:ss')}", "Data Format": "Text"},
        at=(4, 0), schedule="0/15 * * * * ?", strategy="CRON_DRIVEN")
    cron_attrs = f.lab_attributes("Add lab attributes (cron)", at=(4, 1))
    cron_lane = f.set_lane("Lane = cron", "cron", at=(4, 2))
    deliver = f.deliver(at=(2, 4))
    f.connect(receive, route)
    f.connect(route, name, ["to-folder"])
    f.connect(name, put)
    f.connect(route, event, ["unmatched"])
    f.connect(listing, fetch)
    f.connect(fetch, folder_attrs)
    f.connect(folder_attrs, folder)
    f.connect(timer, timer_attrs)
    f.connect(timer_attrs, timer_lane)
    f.connect(cron, cron_attrs)
    f.connect(cron_attrs, cron_lane)
    for lane in (event, folder, timer_lane, cron_lane):
        f.connect(lane, deliver)
    return f


def external_systems():
    f = Flow("external-systems", "Kafka and PostgreSQL")
    reader = json_reader(f)
    writer = json_writer(f)
    kafka = f.service("Kafka connection", "org.apache.nifi.kafka.service.Kafka3ConnectionService",
                      [("org.apache.nifi.kafka.service.api.KafkaConnectionService", "nifi-kafka-service-api-nar")],
                      {"bootstrap.servers": "kafka:9092", "security.protocol": "PLAINTEXT"},
                      artifact="nifi-kafka-3-service-nar")
    db = f.service("PostgreSQL connection", "org.apache.nifi.dbcp.DBCPConnectionPool", "org.apache.nifi.dbcp.DBCPService", {
        "Database Connection URL": "jdbc:postgresql://postgres:5432/lab",
        "Database Driver Class Name": "org.postgresql.Driver",
        "Database Driver Locations": "/opt/nifi/drivers/postgresql.jar",
        "Database User": "lab"}, artifact="nifi-dbcp-service-nar")
    receive = f.listen("Receive order", 9110, "orders")
    publish = f.processor("Publish to Kafka", "org.apache.nifi.kafka.processors.PublishKafka", {
        "Kafka Connection Service": kafka["identifier"], "Topic Name": "lab-orders",
        "Transactions Enabled": "false", "FlowFile Attribute Header Pattern": "lab\\..*"},
        at=(0, 1), artifact="nifi-kafka-nar", services=["Kafka Connection Service"])
    published = f.set_lane("Lane = published", "published", at=(0, 2))
    consume = f.processor("Consume from Kafka", "org.apache.nifi.kafka.processors.ConsumeKafka", {
        "Kafka Connection Service": kafka["identifier"], "Group ID": "nifi-lab", "Topics": "lab-orders",
        "auto.offset.reset": "earliest", "Header Name Pattern": "lab\\..*"},
        at=(1, 0), artifact="nifi-kafka-nar", services=["Kafka Connection Service"])
    store = f.processor("Insert into PostgreSQL", "PutDatabaseRecord", {
        "Record Reader": reader["identifier"], "Database Type": "PostgreSQL", "Statement Type": "INSERT",
        "Database Connection Pooling Service": db["identifier"], "Table Name": "orders",
        "Unmatched Column Behavior": "Ignore Unmatched Columns"},
        at=(1, 1), services=["Record Reader", "Database Connection Pooling Service"], auto_terminate=["retry"])
    stored = f.set_lane("Lane = stored", "stored", at=(1, 2))
    failed = f.set_lane("Lane = failed", "failed", at=(2, 2))
    poll = f.processor("Poll new rows (every 5 s)", "QueryDatabaseTableRecord", {
        "Database Connection Pooling Service": db["identifier"], "Database Type": "PostgreSQL",
        "Table Name": "orders", "Maximum-value Columns": "id", "Record Writer": writer["identifier"]},
        at=(3, 0), schedule="5 sec", services=["Database Connection Pooling Service", "Record Writer"])
    poll_attrs = f.lab_attributes("Add lab attributes", at=(3, 1))
    polled = f.set_lane("Lane = polled", "polled", at=(3, 2))
    deliver = f.deliver(at=(1, 3))
    f.connect(receive, publish)
    f.connect(publish, published)
    f.connect(publish, failed, ["failure"])
    f.connect(consume, store)
    f.connect(store, stored)
    f.connect(store, failed, ["failure"])
    f.connect(poll, poll_attrs)
    f.connect(poll_attrs, polled)
    for lane in (published, stored, failed, polled):
        f.connect(lane, deliver)
    f.label("Kafka decouples producer and consumer: 'Consume from Kafka' reads topic lab-orders\n"
            "(lab.* attributes travel as Kafka headers). QueryDatabaseTableRecord remembers the highest id\n"
            "it has seen (processor state) and only returns newer rows.", at=(2, 0), width=560, height=100)
    return f


def deduplication():
    f = Flow("deduplication", "Stateful processing and deduplication")
    server = f.service("Dedup cache server", "org.apache.nifi.distributed.cache.server.map.MapCacheServer", [],
                       {"Port": "4557", "Maximum Cache Entries": "1000"},
                       artifact="nifi-distributed-cache-services-nar")
    client = f.service("Dedup cache client", "org.apache.nifi.distributed.cache.client.MapCacheClientService", [
        ("org.apache.nifi.distributed.cache.client.AtomicDistributedMapCacheClient", SERVICES_API),
        ("org.apache.nifi.distributed.cache.client.DistributedMapCacheClient", SERVICES_API)],
        {"Server Hostname": "localhost", "Server Port": "4557"}, artifact="nifi-distributed-cache-services-nar")
    receive = f.listen("Receive event", 9111, "events")
    extract = f.processor("Extract event id", "EvaluateJsonPath", {
        "Destination": "flowfile-attribute", "Return Type": "scalar", "Path Not Found Behavior": "ignore",
        "event.id": "$.eventId"}, at=(0, 1), dynamic=["event.id"])
    has_id = f.processor("Has an event id?", "RouteOnAttribute", {
        "Routing Strategy": "Route to Property name", "has-id": "${event.id:isEmpty():not()}"},
        at=(0, 2), dynamic=["has-id"])
    count = f.processor("Number events", UPDATE, {
        "Store State": "Store state locally", "Stateful Variables Initial Value": "0",
        "event.number": "${getStateValue('event.number'):plus(1)}"},
        at=(0, 3), artifact=UPDATE_ATTRIBUTE, dynamic=["event.number"], auto_terminate=["set state fail"])
    detect = f.processor("Detect duplicate", "DetectDuplicate", {
        "Cache Entry Identifier": "${event.id}", "Age Off Duration": "5 min",
        "FlowFile Description": "event #${event.number}, received ${now():format('HH:mm:ss')}",
        "Distributed Cache Service": client["identifier"]},
        at=(0, 4), services=["Distributed Cache Service"])
    unique = f.set_lane("Lane = unique", "unique", at=(0, 5))
    duplicate = f.set_lane("Lane = duplicates", "duplicates", at=(1, 5))
    invalid = f.set_lane("Lane = invalid", "invalid", at=(2, 5))
    deliver = f.deliver(at=(1, 6))
    f.connect(receive, extract)
    f.connect(extract, has_id, ["matched"])
    f.connect(extract, invalid, ["failure", "unmatched"])
    f.connect(has_id, count, ["has-id"])
    f.connect(has_id, invalid, ["unmatched"])
    f.connect(count, detect)
    f.connect(detect, unique, ["non-duplicate"])
    f.connect(detect, duplicate, ["duplicate"])
    f.connect(detect, invalid, ["failure"])
    for lane in (unique, duplicate, invalid):
        f.connect(lane, deliver)
    f.label("DetectDuplicate remembers every event.id in a distributed map cache for 5 minutes.\n"
            "A duplicate gets the attribute original.flowfile.description: when the first copy was seen.\n"
            "'Number events' keeps a counter in NiFi's state manager.", at=(1, 3), width=560, height=100)
    return f


def parameters():
    f = Flow("parameters", "Parameters and environments")
    f.parameter_context("NiFi Lab - dev", [
        ("environment", "development", False), ("threshold", "100", False), ("api.token", "", True)])
    receive = f.listen("Receive order", 9112, "orders")
    extract = f.processor("Extract amount", "EvaluateJsonPath", {
        "Destination": "flowfile-attribute", "Return Type": "scalar", "Path Not Found Behavior": "ignore",
        "order.amount": "$.amount"}, at=(0, 1), dynamic=["order.amount"], auto_terminate=["failure", "unmatched"])
    apply = f.processor("Apply environment", UPDATE, {
        "env.name": "#{environment}", "env.threshold": "#{threshold}"},
        at=(0, 2), artifact=UPDATE_ATTRIBUTE, dynamic=["env.name", "env.threshold"])
    call = f.processor("Call pricing service", "InvokeHTTP", {
        "HTTP Method": "POST", "HTTP URL": MOCK_SERVICE,
        "Request Header Attributes Pattern": "lab\\.trace\\.id", "X-Api-Token": "#{api.token}"},
        at=(0, 3), sensitive_dynamic=["X-Api-Token"], auto_terminate=["Original", "Retry", "No Retry", "Failure"])
    route = f.processor("Above threshold?", "RouteOnAttribute", {
        "Routing Strategy": "Route to Property name",
        "above": "${order.amount:toDecimal():gt(#{threshold})}"}, at=(0, 4), dynamic=["above"])
    above = f.set_lane("Lane = above-threshold", "above-threshold", at=(0, 5))
    below = f.set_lane("Lane = below-threshold", "below-threshold", at=(1, 5))
    deliver = f.deliver(at=(0, 6))
    f.connect(receive, extract)
    f.connect(extract, apply, ["matched"])
    f.connect(apply, call)
    f.connect(call, route, ["Response"])
    f.connect(route, above, ["above"])
    f.connect(route, below, ["unmatched"])
    for lane in (above, below):
        f.connect(lane, deliver)
    f.label("#{environment}, #{threshold} and #{api.token} are parameters. The flow never changes - switch the\n"
            "group between the 'dev' and 'prod' parameter contexts, or change a value, and the same flow\n"
            "behaves differently. api.token is sensitive: NiFi never shows or exports its value.",
            at=(1, 2), width=560, height=100)
    return f


FLOWS = [hello_flowfile, routing, transform, split_merge, enrichment, errors_retry, back_pressure, provenance,
         scheduling, external_systems, deduplication, parameters]

if __name__ == "__main__":
    for build in FLOWS:
        flow = build()
        path = os.path.join(OUT, flow.scenario_id, "flow.json")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        flow.write(path)
        print("wrote", os.path.relpath(path))
