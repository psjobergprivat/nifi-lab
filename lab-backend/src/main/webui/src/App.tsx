import { useEffect, useState } from 'react';
import { api } from './api';
import type { NifiInfo } from './types';
import { Catalogue } from './components/Catalogue';
import { ScenarioView } from './components/ScenarioView';

/** Minimal hash routing: #/ is the catalogue, #/scenarios/<id> a scenario. */
function useHashRoute(): string {
  const [hash, setHash] = useState(window.location.hash);
  useEffect(() => {
    const onChange = () => setHash(window.location.hash);
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);
  return hash.replace(/^#/, '') || '/';
}

export function App() {
  const route = useHashRoute();
  const scenarioId = route.match(/^\/scenarios\/([^/]+)/)?.[1];
  const [nifi, setNifi] = useState<NifiInfo | null>(null);

  useEffect(() => {
    const load = () => api.nifi().then(setNifi).catch(() => setNifi(null));
    load();
    const timer = window.setInterval(load, 10_000);
    return () => window.clearInterval(timer);
  }, []);

  return (
    <div className="app">
      <header className="topbar">
        <a className="brand" href="#/">
          <span className="brand-mark" aria-hidden>◆</span> NiFi Lab
        </a>
        <div className="topbar-right">
          <NifiBadge nifi={nifi} />
          {nifi && (
            <a className="button ghost" href={nifi.uiUrl} target="_blank" rel="noreferrer">
              Open NiFi ↗
            </a>
          )}
        </div>
      </header>
      <main>{scenarioId ? <ScenarioView key={scenarioId} scenarioId={scenarioId} /> : <Catalogue />}</main>
    </div>
  );
}

function NifiBadge({ nifi }: { nifi: NifiInfo | null }) {
  if (!nifi) return <span className="badge muted">Backend unreachable</span>;
  if (!nifi.reachable)
    return (
      <span className="badge warn" title={nifi.message ?? ''}>
        NiFi starting / unreachable
      </span>
    );
  return <span className="badge ok">NiFi {nifi.version}</span>;
}
