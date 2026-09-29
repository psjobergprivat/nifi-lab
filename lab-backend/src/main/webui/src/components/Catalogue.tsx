import { useEffect, useState } from 'react';
import { api } from '../api';
import type { ScenarioSummary } from '../types';
import { StatusBadge } from './StatusBadge';

export function Catalogue() {
  const [scenarios, setScenarios] = useState<ScenarioSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const load = () =>
      api
        .scenarios()
        .then((s) => {
          setScenarios(s);
          setError(null);
        })
        .catch((e: Error) => setError(e.message));
    load();
    const timer = window.setInterval(load, 5_000);
    return () => window.clearInterval(timer);
  }, []);

  return (
    <div className="page">
      <section className="intro">
        <h1>Learn Apache NiFi by watching it work</h1>
        <p>
          Each scenario is a real NiFi flow. Send data in, watch it move through the processors, and jump into the
          NiFi UI to see what happens behind the scenes.
        </p>
      </section>
      {error && <div className="alert error">{error}</div>}
      {!scenarios && !error && <p className="muted">Loading scenarios…</p>}
      <div className="card-grid">
        {scenarios?.map((s) => (
          <a key={s.id} className="card scenario-card" href={`#/scenarios/${s.id}`}>
            <div className="card-head">
              <span className="scenario-number">{String(s.order).padStart(2, '0')}</span>
              <StatusBadge deployment={s.deployment} />
            </div>
            <h2>{s.title}</h2>
            <p>{s.summary}</p>
            <h3>You will learn</h3>
            <ul>
              {s.learn.map((l) => (
                <li key={l}>{l}</li>
              ))}
            </ul>
            <span className="card-cta">Open scenario →</span>
          </a>
        ))}
      </div>
    </div>
  );
}
