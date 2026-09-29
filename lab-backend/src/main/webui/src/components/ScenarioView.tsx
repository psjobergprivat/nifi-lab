import { useCallback, useEffect, useState } from 'react';
import { api } from '../api';
import type { FlowView, ScenarioDetail } from '../types';
import { useLive } from '../useLive';
import { BulletinFeed } from './BulletinFeed';
import { ControlsPanel } from './ControlsPanel';
import { LineageModal } from './LineageModal';
import { ProducerPanel } from './ProducerPanel';
import { ServiceCallsPanel } from './ServiceCallsPanel';
import { FlowDiagram } from './FlowDiagram';
import { InputPanel } from './InputPanel';
import { OutputLanes } from './OutputLanes';
import { StatusBadge } from './StatusBadge';
import { StepsPanel } from './StepsPanel';

export function ScenarioView({ scenarioId }: { scenarioId: string }) {
  const [scenario, setScenario] = useState<ScenarioDetail | null>(null);
  const [flow, setFlow] = useState<FlowView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [live, clearLive] = useLive(scenarioId);
  const [tracing, setTracing] = useState<string | null>(null);

  const deployed = scenario?.deployment.state === 'DEPLOYED';
  const running = deployed && scenario?.deployment.runState === 'RUNNING';

  const load = useCallback(() => {
    api
      .scenario(scenarioId)
      .then((s) => {
        setScenario(s);
        if (s.deployment.state === 'DEPLOYED') {
          api.flow(scenarioId).then(setFlow).catch((e: Error) => setError(e.message));
        }
      })
      .catch((e: Error) => setError(e.message));
  }, [scenarioId]);

  // Reload whenever the backend reports a deployment change (deployed, started, stopped, reset...).
  useEffect(load, [load, live.deploymentVersion]);

  // Until deployed, poll: NiFi may still be starting.
  useEffect(() => {
    if (deployed) return;
    const timer = window.setInterval(load, 5_000);
    return () => window.clearInterval(timer);
  }, [deployed, load]);

  const run = async (action: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try {
      await action();
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const resend = (content: string) => {
    setError(null);
    api.sendInput(scenarioId, content).catch((e: Error) => setError(e.message));
  };

  if (!scenario) {
    return <div className="page">{error ? <div className="alert error">{error}</div> : <p className="muted">Loading…</p>}</div>;
  }

  return (
    <div className="page scenario">
      <a className="back" href="#/">
        ← All scenarios
      </a>
      <div className="scenario-header">
        <div>
          <div className="eyebrow">
            Scenario {String(scenario.order).padStart(2, '0')} <StatusBadge deployment={scenario.deployment} />
            <span className={`live-dot ${live.connected ? 'on' : ''}`} title={live.connected ? 'Live updates connected' : 'Live updates disconnected'} />
          </div>
          <h1>{scenario.title}</h1>
          <p className="summary">{scenario.summary}</p>
        </div>
        <div className="actions">
          {running ? (
            <button disabled={busy} onClick={() => run(() => api.stop(scenarioId))}>
              ■ Stop
            </button>
          ) : (
            <button className="primary" disabled={busy || !deployed} onClick={() => run(() => api.start(scenarioId))}>
              ▶ Start
            </button>
          )}
          <button
            disabled={busy || !deployed}
            title="Stop the flow, empty all queues and clear processor state"
            onClick={() =>
              run(async () => {
                await api.reset(scenarioId);
                clearLive();
              })
            }
          >
            ↺ Reset
          </button>
          {scenario.deployment.state === 'FAILED' && (
            <button disabled={busy} onClick={() => run(() => api.deploy(scenarioId))}>
              Retry deploy
            </button>
          )}
          {scenario.deployment.nifiLink && (
            <a className="button ghost" href={scenario.deployment.nifiLink} target="_blank" rel="noreferrer">
              Flow in NiFi ↗
            </a>
          )}
          <a className="button ghost" href={scenario.links.provenance} target="_blank" rel="noreferrer">
            Provenance ↗
          </a>
        </div>
      </div>

      {error && <div className="alert error">{error}</div>}
      {live.nifiError && <div className="alert warn">{live.nifiError}</div>}
      {!deployed && <div className="alert info">{scenario.deployment.message ?? 'Waiting for NiFi…'}</div>}

      <div className="scenario-grid">
        <aside className="col">
          <InputPanel scenario={scenario} running={!!running} inputs={live.inputs} onError={setError} />
          {scenario.controls.length > 0 && (
            <ControlsPanel
              scenarioId={scenarioId}
              controls={scenario.controls}
              values={live.controlValues ?? scenario.controlValues}
              onError={setError}
            />
          )}
          {scenario.controls.some((c) => c.id === 'producer.rate') && <ProducerPanel stats={live.producer} />}
          <StepsPanel steps={scenario.steps} learn={scenario.learn} />
        </aside>
        <section className="col">
          <div className="panel">
            <div className="panel-head">
              <h2>Flow</h2>
              <span className="muted small">
                {live.status ? `${live.status.queuedCount} queued · ${live.status.activeThreads} active threads` : 'no live data yet'}
              </span>
            </div>
            {flow ? <FlowDiagram flow={flow} status={live.status} /> : <div className="empty">The flow appears here once it is deployed.</div>}
            <p className="hint">Click a processor or connection to open it in NiFi. Numbers are NiFi's 5-minute counters.</p>
          </div>
          {scenario.controls.some((c) => c.id.startsWith('service.')) && <ServiceCallsPanel calls={live.serviceCalls} />}
          <BulletinFeed bulletins={live.bulletins} expected={scenario.expectedBulletins} bulletinsLink={scenario.links.bulletins} />
        </section>
        <section className="col">
          <OutputLanes lanes={scenario.lanes} items={live.items} onTrace={setTracing} onResend={resend} />
        </section>
      </div>
      {tracing && <LineageModal flowFileUuid={tracing} onClose={() => setTracing(null)} />}
    </div>
  );
}
