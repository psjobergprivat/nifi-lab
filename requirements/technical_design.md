# NiFi Lab – Technical Design

## Context
`functional_requirements.txt` describes a lab that makes Apache NiFi understandable to people:
an interactive web UI with a set of scenarios, each linked to the NiFi admin UI for behind-the-scenes insight.
This document describes the technical stack and architecture used to implement it.

## Architecture overview

```
 Browser (Lab UI, React)  ──REST/SSE──►  lab-backend (Quarkus)  ──REST──►  NiFi 2.x  (admin UI :8443)
        ▲                                   │   ▲                              │
        └──────── deep links ───────────────┼───┼──── NiFi admin UI ───────────┘
                                            │   └── sink callbacks (InvokeHTTP)┘
                                            └── Postgres / Kafka / mock APIs (Docker Compose)
```

- **NiFi runs the real flows.** Each scenario is its own process group, imported from a versioned flow
  definition JSON checked into the repo.
- **The backend is the orchestrator and the "eyes".** It deploys, starts, stops and resets scenarios through the
  NiFi REST API, feeds input in, receives output back, and streams live status to the browser.
- **The browser never talks to NiFi directly.** This avoids CORS and auth problems. The one exception is deep links
  that open the NiFi UI in a new tab.

## Technical stack

| Layer | Choice | Why |
|---|---|---|
| Orchestration | Docker Compose (one `docker compose up`) | "Self-contained" requirement |
| Data flow engine | `apache/nifi:2.x` (single node) | Current major version, and the REST API has what we need |
| Backend | Java 17, Quarkus 3.39, Maven 3.9 via `./mvnw` (existing pom becomes the parent) | Fast startup, live reload in dev mode (`quarkus dev`), Dev Services |
| REST endpoints | Quarkus REST (`quarkus-rest`, `quarkus-rest-jackson`) | JAX-RS style, reactive under the hood |
| NiFi client | Quarkus REST Client (`quarkus-rest-client-jackson`), a typed `@RegisterRestClient` interface + small DTOs | Declarative; we only use about 15 NiFi endpoints |
| Live updates | Server-Sent Events via Quarkus REST + Mutiny (`Multi<StatusEvent>`, `@RestStreamElementType`) | One-way push is enough and is simpler than WebSockets |
| Polling / scheduling | `quarkus-scheduler` (`@Scheduled(every = "1s")`) | Polls NiFi status for the active scenario |
| Configuration | `application.properties` + `@ConfigMapping` interfaces | NiFi URL, credentials and ports come from env vars in compose |
| Scenario metadata | YAML parsed with `jackson-dataformat-yaml` | Human-editable scenario descriptors |
| Frontend | React + TypeScript + Vite, integrated via the **Quarkus Quinoa** extension | Quinoa builds the UI during the Maven build, serves it from Quarkus and proxies the Vite dev server in `quarkus dev` – one artifact, one port |
| Flow visuals | React Flow (lanes, queues, lineage graph) + a simple chart lib | Makes "items moving through the flow" visible |
| Supporting systems | Postgres, Kafka (KRaft, no ZooKeeper), NiFi Registry (optional, scenario 13) | Needed for scenarios 10–13 |
| DB / Kafka access from backend | `quarkus-jdbc-postgresql` + Agroal, `quarkus-messaging-kafka` | Lets the backend show what arrived in the target systems |
| Mock external APIs | Endpoints in the backend itself (`/mock/**`) whose failures and latency can be controlled | Scenarios 5–6 need a service that can be made to fail or slow down on demand |
| Container image | Multi-stage `lab-backend/Dockerfile`; Quinoa downloads Node.js inside the build | `docker compose up --build` needs no Java or Node on the host |
| Tests | `@QuarkusTest` + JUnit 5 and REST Assured; static checks that scenario.yaml matches flow.json. Later: Dev Services (Postgres, Kafka), a NiFi Testcontainer, a Playwright smoke suite | |

## Repository layout

```
nifi-lab/
  pom.xml                      parent (modules below), Quarkus BOM
  docker-compose.yml           nifi, lab-backend, postgres, kafka, (nifi-registry)
  lab-backend/                 Quarkus app
    src/main/java/org/mylabs/nifilab/
      nifi/                    NifiApi (REST client interface), NifiAuth (token filter), DTOs
      scenario/                ScenarioRegistry, ScenarioService (deploy/start/stop/reset), ScenarioResource
      live/                    StatusPoller (@Scheduled) → BroadcastProcessor → SSE resource
      sink/                    SinkResource: /api/sink/{scenario}/{lane}
      mock/                    controllable mock APIs (latency, error rate, lookup data)
    src/main/resources/
      application.properties
      scenarios/<id>/
        scenario.yaml          metadata, learning goals, guided steps, input form spec
        flow.json              NiFi flow definition (exported from NiFi UI)
        samples/               sample data files
    src/main/webui/            React/Vite app (Quinoa's default location)
  requirements/
```

## Key design decisions

**1. Scenario as data (plugin-like).**
Each scenario is a folder containing `scenario.yaml` and `flow.json`. Adding a scenario means building the flow in
the NiFi UI, exporting it ("Download flow definition"), and writing the YAML. No new Java code should normally be
needed. Example YAML:
```yaml
id: routing
title: Content-based routing
learn: [RouteOnAttribute, relationships, unmatched]
input: { type: form, fields: [country, priority, amount] }
lanes: [eu, us, high-value, unmatched]
steps:
  - text: "Orders enter via ListenHTTP"
    component: "Receive orders"       # resolved by name → NiFi id → deep link
```

**2. Deploying flows.** On `StartupEvent`, the backend authenticates (`POST /nifi-api/access/token`, single-user
credentials from env). The bearer token is attached by a `ClientRequestFilter` and refreshed when it expires.
For each scenario, the backend uploads `flow.json` into a process group named after the scenario
(`POST /process-groups/root/process-groups/upload`), replacing any existing one. Component ids are looked up
by name, because ids change on import. Startup waits and retries until NiFi is reachable (it starts slowly).

**3. Getting data in and out.**
- *In:* flows start with `ListenHTTP` (a separate port per scenario inside the compose network). The backend
  forwards user input there and adds a `lab.trace.id` attribute (sent as an HTTP header).
- *Out:* flows end with `InvokeHTTP` → `http://lab-backend:8080/api/sink/{scenario}/{lane}`. The backend sends
  results to the browser over SSE, so the page can show "this item landed in lane X".
- Scenarios that use Kafka or Postgres also have the backend read the target system and show what arrived.

**4. Live view.** A `@Scheduled` poller checks `GET /flow/process-groups/{id}/status?recursive=true` about once
per second for the active scenario, plus `/flow/bulletin-board`. It keeps only queue counts, back-pressure %,
processor in/out counts and bulletins, and publishes them to a Mutiny `BroadcastProcessor`. Browsers subscribe
via an SSE endpoint (`GET /api/live/{scenario}`, `@Produces(SERVER_SENT_EVENTS)`).

**5. Provenance / lineage (scenario 8).** The backend first runs `POST /provenance` with a search on
`lab.trace.id`, then `POST /provenance/lineage`. It polls until finished, then converts the result into
React Flow nodes and edges. The NiFi provenance deep link is shown next to it.

**6. Reset.** Stop the process group (`PUT /flow/process-groups/{id}` with state STOPPED), empty all queues
(`POST /process-groups/{id}/empty-all-connections-requests`), clear processor state
(`POST /processors/{id}/state/clear-requests`), reset the mock/DB/Kafka test data, and optionally re-import the flow.

**7. Controls from the lab UI.** Sliders and toggles, such as input rate, slow consumer, and "make service fail",
change either mock-service settings in the backend or NiFi parameter-context values
(`POST /parameter-contexts/{id}/update-requests`). This also covers scenario 12 for free.

**8. Deep links.** `https://localhost:8443/nifi/#/process-groups/{pgId}/{type}/{componentId}` is built from the
resolved ids.

**9. Security / dev comfort.** NiFi 2 requires HTTPS. The lab uses fixed single-user credentials
(`nifilab` / `nifilabpassword`; NiFi requires a username of at least 4 characters and a password of at least 12),
overridable with `NIFI_USERNAME` / `NIFI_PASSWORD`. The REST client trusts NiFi's self-signed certificate through a named TLS configuration
(`quarkus.tls.nifi.trust-all=true`, lab only, clearly marked). Scenario 14 (multi-tenancy) would need a
file-based authorizer setup and stays optional.

## Lab UI pages
- **Catalogue:** scenario cards with a "what you'll learn" list and Start/Reset buttons.
- **Scenario view:** input panel (form, sample picker, file upload), a lane/bucket visual of outputs, a mini flow diagram
  with live queue gauges, a guided-steps side panel with "Open in NiFi" buttons, and a bulletin/error feed.
- **Item inspector:** attributes and content before/after each step, plus the lineage graph.

## Developer workflow
- `docker compose up nifi postgres kafka` → start the infrastructure.
- `mvn -pl lab-backend quarkus:dev` → backend with live reload; Quinoa runs the Vite dev server and proxies it,
  so UI and backend changes both reload instantly. Dev UI is at `/q/dev-ui`.
- `docker compose up --build` builds the backend image (UI included) and runs the full lab.

## Delivery phases
1. **Foundation:** multi-module Maven with the Quarkus BOM, compose with NiFi, backend `NifiApi` + scenario
   registry, deploy/start/stop/reset, SSE live view, catalogue UI, **Scenario 1 (Hello FlowFile)** end-to-end.
2. **Core scenarios:** 2 routing, 3 transformation, 4 split/merge, 6 errors/retry, 7 back pressure,
   8 provenance.
3. **Integration:** 5 enrichment, 9 scheduling, 10 Kafka/Postgres, 11 dedup, 12 parameters.
4. **Optional:** 13 NiFi Registry versioning, 14 multi-tenancy.

## Verification
- `mvn verify` runs unit tests for YAML parsing, DTO mapping and lineage→graph conversion.
- `@QuarkusTest` integration tests start a NiFi Testcontainer (Postgres and Kafka come from Dev Services):
  deploy scenario 1, POST input, assert that the sink receives the transformed item, and assert that reset empties
  the queues.
- `docker compose up` → open `http://localhost:8080`, run each scenario with its sample data, and click every
  "Open in NiFi" link to check that it lands on the right component.
- Playwright smoke test: catalogue loads, scenario 1 starts, and a submitted message appears in the output lane.

## Implementation notes (Phase 1)
- **Attributes to the sink.** InvokeHTTP can only send attributes as ASCII headers. So flows first run
  AttributesToJSON, then send `${JSONAttributes:base64Encode()}` as a single `lab-attributes` header.
- **Where to send results.** The backend passes the sink URL to the flow as a `lab.sink.url` request header,
  which becomes an attribute. The same flow therefore works whether the backend runs in Compose or on the host.
- **Recognising deployed flows.** The backend marks each scenario's process group in its comments
  (`nifi-lab:scenario=<id>;sha256=<hash of flow.json>`). A matching group is reused. If the hash differs, the
  group is stopped, drained, deleted and imported again.
- **Deploy loop.** A scheduled job tries to deploy every 10 seconds until everything is deployed, so the
  backend can start before NiFi.
- **NiFi 2 property names.** Some properties were renamed in NiFi 2 (e.g. ListenHTTP's
  *HTTP Headers for Attributes*). Hand-written flow.json files must use the 2.x names.
