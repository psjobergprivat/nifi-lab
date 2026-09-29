import type { ProducerStats } from '../types';

/** What the lab's producer sent and how NiFi answered - 503s are back pressure reaching the source. */
export function ProducerPanel({ stats }: { stats: ProducerStats | null }) {
  const s = stats ?? { sent: 0, accepted: 0, refused: 0, failed: 0, lastAccepted: 0, lastRefused: 0, lastFailed: 0, rate: 0, lastError: null };
  const pushedBack = s.lastRefused > 0;
  return (
    <div className="panel">
      <div className="panel-head">
        <h2>Producer</h2>
        {pushedBack ? <span className="badge error">Pushed back (503)</span> : s.lastAccepted > 0 ? <span className="badge ok">Flowing</span> : <span className="badge muted">Idle</span>}
      </div>
      <div className="stats">
        <Stat label="sent" value={s.sent} />
        <Stat label="accepted" value={s.accepted} tone="ok" />
        <Stat label="refused (503)" value={s.refused} tone={s.refused > 0 ? 'error' : undefined} />
        {s.failed > 0 && <Stat label="failed" value={s.failed} tone="error" />}
      </div>
      <p className="muted small">
        Last second: {s.lastAccepted} accepted, {s.lastRefused} refused
        {s.lastError ? ` - ${s.lastError}` : ''}
      </p>
    </div>
  );
}

function Stat({ label, value, tone }: { label: string; value: number; tone?: 'ok' | 'error' }) {
  return (
    <div className={`stat ${tone ?? ''}`}>
      <strong>{value}</strong>
      <span>{label}</span>
    </div>
  );
}
