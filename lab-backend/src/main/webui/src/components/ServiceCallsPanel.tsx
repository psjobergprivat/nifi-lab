import type { ServiceCall } from '../types';

/** Every call the flow made to the mock service - retries show up as attempt 2, 3, ... for the same trace id. */
export function ServiceCallsPanel({ calls }: { calls: (ServiceCall & { at: number })[] }) {
  return (
    <div className="panel scroll">
      <div className="panel-head">
        <h2>Service calls</h2>
        <span className="muted small">{calls.length} calls</span>
      </div>
      {calls.length === 0 ? (
        <div className="empty small">No calls yet. The flow calls the service when data passes through it.</div>
      ) : (
        <table className="calls">
          <thead>
            <tr>
              <th>time</th>
              <th>trace</th>
              <th>attempt</th>
              <th>answer</th>
            </tr>
          </thead>
          <tbody>
            {calls.slice(0, 25).map((c, i) => (
              <tr key={`${c.at}-${i}`}>
                <td className="muted">{new Date(c.at).toLocaleTimeString()}</td>
                <td className="mono">{c.traceId?.slice(0, 8)}</td>
                <td>{c.attempt}</td>
                <td>
                  <span className={`badge small ${c.status < 300 ? 'ok' : c.status < 500 ? 'warn' : 'error'}`}>{c.status}</span>{' '}
                  <span className="small">{c.outcome}</span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
