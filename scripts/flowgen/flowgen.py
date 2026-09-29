"""Tiny builder for NiFi 2.x flow definitions (the JSON NiFi exports with "Download flow definition").

The scenario flows are generated from Python (see scenarios.py) so they stay readable and consistent.
Property names must be NiFi 2.x names; check them with
GET /nifi-api/flow/processor-definition/org.apache.nifi/<nar>/<version>/<type>.
"""
import json
import uuid

NIFI_VERSION = "2.12.0"
STANDARD = "nifi-standard-nar"
UPDATE_ATTRIBUTE = "nifi-update-attribute-nar"
RECORD_SERIALIZATION = "nifi-record-serialization-services-nar"
SERVICES_API = "nifi-standard-services-api-nar"

# Grid used for positions: one column is 450px wide, one row 200px high.
COL = 450
ROW = 200


def bundle(artifact):
    return {"group": "org.apache.nifi", "artifact": artifact, "version": NIFI_VERSION}


class Flow:
    def __init__(self, scenario_id, name):
        self.scenario_id = scenario_id
        self.name = name
        self.group_id = self._id("group")
        self.processors = []
        self.connections = []
        self.services = []
        self.labels = []
        self.parameter_context_name = None
        self.parameter_contexts = {}

    def _id(self, *parts):
        # Stable ids, so regenerating an unchanged flow gives identical JSON.
        return str(uuid.uuid5(uuid.NAMESPACE_URL, "nifi-lab/" + self.scenario_id + "/" + "/".join(parts)))

    # --- components -------------------------------------------------------------------------------------

    def processor(self, name, type_, props=None, at=(0, 0), artifact=STANDARD, auto_terminate=(),
                  dynamic=(), sensitive_dynamic=(), services=(), concurrency=1, schedule="0 sec",
                  strategy="TIMER_DRIVEN", retry=None, penalty="30 sec"):
        """retry: dict(relationships=[...], count=3, max_backoff='10 mins') for NiFi's built-in retry.
        strategy: TIMER_DRIVEN (schedule = period, e.g. '5 sec') or CRON_DRIVEN (schedule = Quartz cron)."""
        props = dict(props or {})
        descriptors = {n: {"name": n, "displayName": n, "identifiesControllerService": False, "sensitive": False,
                           "dynamic": True} for n in dynamic}
        for n in sensitive_dynamic:
            descriptors[n] = {"name": n, "displayName": n, "identifiesControllerService": False, "sensitive": True,
                              "dynamic": True}
        for prop in services:
            descriptors[prop] = {"name": prop, "displayName": prop, "identifiesControllerService": True,
                                 "sensitive": False, "dynamic": False}
        p = {
            "identifier": self._id("processor", name), "name": name, "comments": "",
            "position": {"x": float(at[0] * COL), "y": float(at[1] * ROW)},
            "type": type_ if "." in type_ else "org.apache.nifi.processors.standard." + type_,
            "bundle": bundle(artifact), "properties": props, "propertyDescriptors": descriptors, "style": {},
            "schedulingPeriod": schedule, "schedulingStrategy": strategy, "executionNode": "ALL",
            "penaltyDuration": penalty, "yieldDuration": "1 sec", "bulletinLevel": "WARN", "runDurationMillis": 0,
            "concurrentlySchedulableTaskCount": concurrency, "autoTerminatedRelationships": list(auto_terminate),
            "scheduledState": "ENABLED", "retryCount": (retry or {}).get("count", 10),
            "retriedRelationships": list((retry or {}).get("relationships", [])),
            "backoffMechanism": "PENALIZE_FLOWFILE", "maxBackoffPeriod": (retry or {}).get("max_backoff", "10 mins"),
            "componentType": "PROCESSOR", "groupIdentifier": self.group_id,
        }
        self.processors.append(p)
        return p

    def service(self, name, type_, api, props=None, artifact=RECORD_SERIALIZATION, services=()):
        """api: one API type, or a list of (type, api_artifact) tuples, as GET /flow/controller-service-types
        reports them. services: properties that reference other controller services."""
        apis = [(api, SERVICES_API)] if isinstance(api, str) else list(api)
        descriptors = {prop: {"name": prop, "displayName": prop, "identifiesControllerService": True,
                              "sensitive": False, "dynamic": False} for prop in services}
        s = {
            "identifier": self._id("service", name), "name": name, "comments": "", "type": type_,
            "bundle": bundle(artifact), "properties": dict(props or {}), "propertyDescriptors": descriptors,
            "controllerServiceApis": [{"type": t, "bundle": bundle(a)} for t, a in apis],
            "scheduledState": "DISABLED", "bulletinLevel": "WARN",
            "componentType": "CONTROLLER_SERVICE", "groupIdentifier": self.group_id,
        }
        self.services.append(s)
        return s

    def connect(self, source, destination, relationships=("success",), back_pressure=10000, name=""):
        ref = lambda c: {"id": c["identifier"], "type": "PROCESSOR", "groupId": self.group_id, "name": c["name"]}
        c = {
            "identifier": self._id("connection", source["name"], destination["name"], *relationships),
            "name": name, "comments": "", "source": ref(source), "destination": ref(destination),
            "labelIndex": 1, "zIndex": 0, "selectedRelationships": list(relationships),
            "backPressureObjectThreshold": back_pressure, "backPressureDataSizeThreshold": "1 GB",
            "flowFileExpiration": "0 sec", "prioritizers": [], "bends": [],
            "loadBalanceStrategy": "DO_NOT_LOAD_BALANCE", "partitioningAttribute": "",
            "loadBalanceCompression": "DO_NOT_COMPRESS", "componentType": "CONNECTION",
            "groupIdentifier": self.group_id,
        }
        self.connections.append(c)
        return c

    def label(self, text, at, width=380, height=90):
        self.labels.append({
            "identifier": self._id("label", text[:40]), "label": text,
            "position": {"x": float(at[0] * COL), "y": float(at[1] * ROW)},
            "width": float(width), "height": float(height), "style": {"font-size": "13px"}, "zIndex": 0,
            "componentType": "LABEL", "groupIdentifier": self.group_id,
        })

    def parameter_context(self, name, parameters):
        """Binds the flow to a parameter context. parameters: list of (name, value, sensitive). Sensitive values
        are not exported by NiFi either, so they are left empty here and set by the lab backend."""
        self.parameter_context_name = name
        self.parameter_contexts[name] = {
            "name": name, "description": "",
            "parameters": [{"name": n, "description": "", "sensitive": sens, "value": None if sens else v,
                            "provided": False} for n, v, sens in parameters],
            "inheritedParameterContexts": [], "componentType": "PARAMETER_CONTEXT",
        }

    # --- lab conventions --------------------------------------------------------------------------------

    def listen(self, name, port, path, at=(0, 0)):
        """Entry point: the lab POSTs input here; lab.* request headers become attributes."""
        return self.processor(name, "ListenHTTP", {
            "Base Path": path, "Listening Port": str(port),
            "HTTP Headers for Attributes": "lab\\..*", "Return Code": "200"}, at=at)

    def set_lane(self, name, lane_expression, at):
        """Tags FlowFiles with the output lane (lab.lane) that deliver() sends them to."""
        return self.processor(name, "org.apache.nifi.processors.attributes.UpdateAttribute",
                              {"lab.lane": lane_expression}, at=at, artifact=UPDATE_ATTRIBUTE, dynamic=["lab.lane"])

    def lab_attributes(self, name, at):
        """For FlowFiles NiFi creates itself (timers, polling, Kafka): adds the lab.* attributes that input from
        the lab carries as headers. ${LAB_BACKEND_URL} is an environment variable of the NiFi container."""
        return self.processor(name, "org.apache.nifi.processors.attributes.UpdateAttribute", {
            "lab.scenario": self.scenario_id,
            "lab.backend.url": "${LAB_BACKEND_URL}",
            "lab.sink.url": "${LAB_BACKEND_URL}/api/sink/" + self.scenario_id},
            at=at, artifact=UPDATE_ATTRIBUTE, dynamic=["lab.scenario", "lab.backend.url", "lab.sink.url"])

    def deliver(self, at, url="${lab.sink.url}/${lab.lane}"):
        """Exit point: AttributesToJSON + InvokeHTTP to the lab sink. Returns the first processor to connect to."""
        collect = self.processor("Collect attributes", "AttributesToJSON", {
            "Destination": "flowfile-attribute", "Include Core Attributes": "true"},
            at=at, auto_terminate=["failure"])
        send = self.processor("Deliver to lab", "InvokeHTTP", {
            "HTTP Method": "POST", "HTTP URL": url, "lab-attributes": "${JSONAttributes:base64Encode()}"},
            at=(at[0], at[1] + 1), dynamic=["lab-attributes"],
            auto_terminate=["Original", "Response", "Retry", "No Retry", "Failure"])
        self.connect(collect, send)
        return collect

    def to_json(self):
        return {
            "flowContents": {
                "identifier": self.group_id, "name": self.name, "comments": "", "position": {"x": 0.0, "y": 0.0},
                "processGroups": [], "remoteProcessGroups": [], "processors": self.processors,
                "inputPorts": [], "outputPorts": [], "connections": self.connections, "labels": self.labels,
                "funnels": [], "controllerServices": self.services,
                **({"parameterContextName": self.parameter_context_name} if self.parameter_context_name else {}),
                "defaultFlowFileExpiration": "0 sec", "defaultBackPressureObjectThreshold": 10000,
                "defaultBackPressureDataSizeThreshold": "1 GB", "flowFileConcurrency": "UNBOUNDED",
                "flowFileOutboundPolicy": "STREAM_WHEN_AVAILABLE", "scheduledState": "ENABLED",
                "executionEngine": "INHERITED", "maxConcurrentTasks": 1, "statelessFlowTimeout": "1 min",
                "componentType": "PROCESS_GROUP",
            },
            "externalControllerServices": {}, "parameterContexts": self.parameter_contexts, "parameterProviders": {},
            "flowEncodingVersion": "1.0", "latest": False,
        }

    def write(self, path):
        with open(path, "w") as f:
            json.dump(self.to_json(), f, indent=2)
            f.write("\n")
