/** Collapsible primer on what Apache NiFi is (and is not), shown above the scenario catalogue. */
export function NifiPrimer() {
  return (
    <details className="primer">
      <summary>What is Apache NiFi — and what is it not?</summary>
      <div className="primer-body">
        <p>
          Apache NiFi is a platform for <strong>moving data between systems</strong>. You build a flow on a visual
          canvas by connecting <em>processors</em> — small components that fetch, route, transform or deliver data —
          and NiFi runs that flow continuously. Every piece of data travels as a <em>FlowFile</em>: content plus a
          set of key/value attributes describing it.
        </p>
        <div className="primer-columns">
          <div>
            <h3>What NiFi is</h3>
            <ul>
              <li>
                <strong>A dataflow engine.</strong> It pulls or receives data from sources (HTTP, files, Kafka,
                databases, …) and pushes it to destinations.
              </li>
              <li>
                <strong>Visual and live.</strong> Flows are built and changed in a browser UI while they run — no
                redeploy needed.
              </li>
              <li>
                <strong>Reliable by design.</strong> Queues between processors buffer data, back pressure slows
                producers down, and failures can be routed to retries instead of being lost.
              </li>
              <li>
                <strong>Traceable.</strong> Data provenance records what happened to every FlowFile, so you can
                follow its lineage and replay it.
              </li>
              <li>
                <strong>Good at routing and light transformation</strong> — format conversion, enrichment,
                filtering, splitting and merging records.
              </li>
            </ul>
          </div>
          <div>
            <h3>What NiFi is not</h3>
            <ul>
              <li>
                <strong>Not a message broker.</strong> It does not replace Kafka or RabbitMQ as a durable,
                replayable log for many consumers — it often reads from or writes to them.
              </li>
              <li>
                <strong>Not a big-data compute engine.</strong> Heavy joins, aggregations and analytics over large
                datasets belong in Spark, Flink or a database.
              </li>
              <li>
                <strong>Not a database or data warehouse.</strong> Data passes through NiFi; it is not meant to be
                stored or queried there.
              </li>
              <li>
                <strong>Not a general application platform.</strong> Complex business logic is better written as a
                service that NiFi calls, rather than as a sprawling flow.
              </li>
              <li>
                <strong>Not a batch job scheduler.</strong> It can run on a schedule, but orchestrating dependent
                jobs is the domain of tools like Airflow.
              </li>
            </ul>
          </div>
        </div>
      </div>
    </details>
  );
}
