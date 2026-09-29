# NiFi Lab

An interactive lab that shows what Apache NiFi does. Each scenario is a real NiFi flow: you send data in from
the lab UI, watch it move through the processors, and jump into the NiFi UI to see what is happening inside.

See `requirements/functional_requirements.txt` for the scenarios and `requirements/technical_design.md` for how
the lab is built.

| # | Scenario | Shows |
|---|---|---|
| 01 | Hello FlowFile | content + attributes, processors, connections |
| 02 | Content-based routing | EvaluateJsonPath, RouteOnAttribute, cloning to several relationships |
| 03 | Format conversion | record readers/writers, controller services, QueryRecord SQL |
| 04 | Split and merge | SplitText, fragment attributes, MergeContent Defragment |
| 06 | Error handling and retry | InvokeHTTP outcomes, built-in retry with penalties, dead letter + replay |
| 07 | Back pressure | queue thresholds, back pressure travelling upstream, 503 to the producer |
| 08 | Provenance and lineage | FORK/JOIN events, bin-packing merge, lineage graph of any output |

Every output item has **Trace** (its provenance lineage) and **Send again**. Scenarios can have **controls**
(sliders/selects in `scenario.yaml`) that drive the lab's mock service and producer.

## Run the whole lab

```bash
docker compose up --build        # or: podman compose up --build
```

- Lab: http://localhost:8080
- NiFi: https://localhost:8443/nifi (self-signed certificate; log in as `nifilab` / `nifilabpassword`)

With podman-compose 1.0.x, rebuild with `podman compose down && podman compose up -d --build`, because
`up --build` builds the new image but keeps running the old container. NiFi's data is kept either way (named
volumes).

NiFi takes about 15–60 seconds to start. The lab shows "Waiting for NiFi" until then, and deploys the scenario
flows by itself once NiFi is up.

## Develop

Requirements: Java 17+, Node 18+, and Docker or Podman.

```bash
docker compose up -d nifi                # NiFi only
./mvnw -pl lab-backend quarkus:dev       # backend + UI with live reload on http://localhost:8080
./mvnw test                              # unit and API tests (no NiFi needed)
python3 scripts/smoke_test.py            # end-to-end checks against a running lab (24 checks)
```

In dev mode the backend runs on the host, so NiFi calls back to it through `host.docker.internal`, and the
backend reaches the scenarios' ListenHTTP ports through `localhost` (compose publishes them).

## Adding a scenario

1. Write the flow in `scripts/flowgen/scenarios.py` and run `python3 scripts/flowgen/scenarios.py`. The helpers
   follow the lab's conventions:
   - `listen(...)`: a `ListenHTTP` on a port no other scenario uses, turning `lab.*` request headers into
     attributes;
   - `set_lane(...)`: sets `lab.lane`, the output lane;
   - `deliver(...)`: `AttributesToJSON` + `InvokeHTTP` posting to `${lab.sink.url}/${lab.lane}`;
   - flows can call the mock service at `${lab.backend.url}/mock/${lab.scenario}/service`.

   (You can also build the flow in the NiFi UI and use *Download flow definition*. Property names must be the
   NiFi 2.x names; `GET /nifi-api/flow/processor-definition/org.apache.nifi/<nar>/2.12.0/<type>` lists them.)
2. The generated file is `lab-backend/src/main/resources/scenarios/<id>/flow.json`.
3. Write `scenario.yaml` next to it (copy `hello-flowfile/scenario.yaml`), and add `<id>` to `scenarios/index.yaml`.
4. Publish the ListenHTTP port in `docker-compose.yml` (needed for dev mode).
5. Run `./mvnw test`: `ScenarioRegistryTest` checks that the YAML and the flow match (ports, component names,
   lanes).

When `flow.json` changes, the backend replaces the scenario's process group in NiFi on its next start. Set
`lab.force-redeploy=true` to replace the groups even when `flow.json` has not changed.
