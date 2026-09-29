# NiFi Lab

An interactive lab that shows what Apache NiFi does. Each scenario is a real NiFi flow: you send data in from
the lab UI, watch it move through the processors, and jump into the NiFi UI to see what is happening inside.

See `requirements/functional_requirements.txt` for the scenarios and `requirements/technical_design.md` for how
the lab is built.

## Run the whole lab

```bash
docker compose up --build        # or: podman compose up --build
```

- Lab: http://localhost:8080
- NiFi: https://localhost:8443/nifi (self-signed certificate; log in as `nifilab` / `nifilabpassword`)

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

1. Build the flow in the NiFi UI inside a process group. It should:
   - start with a `ListenHTTP` processor on a port no other scenario uses, with
     *HTTP Headers for Attributes* = `lab\..*`;
   - end with `AttributesToJSON` followed by `InvokeHTTP` that POSTs to `${lab.sink.url}/<lane>` with a dynamic
     property `lab-attributes` = `${JSONAttributes:base64Encode()}`.
2. Right-click the group → *Download flow definition* and save it as
   `lab-backend/src/main/resources/scenarios/<id>/flow.json`.
3. Write `scenario.yaml` next to it (copy `hello-flowfile/scenario.yaml`), and add `<id>` to `scenarios/index.yaml`.
4. Publish the ListenHTTP port in `docker-compose.yml` (needed for dev mode).
5. Run `./mvnw test`: `ScenarioRegistryTest` checks that the YAML and the flow match (ports, component names).

When `flow.json` changes, the backend replaces the scenario's process group in NiFi on its next start. Set
`lab.force-redeploy=true` to replace the groups even when `flow.json` has not changed.
