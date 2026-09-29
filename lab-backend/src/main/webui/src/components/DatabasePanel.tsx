import type { TableView } from '../types';

/** Rows in the lab database, as the backend reads them - the "target system" view. */
export function DatabasePanel({ title, view }: { title: string; view: TableView | null }) {
  return (
    <div className="panel scroll">
      <div className="panel-head">
        <h2>{view?.title ?? title}</h2>
        <span className="muted small">{view && !view.error ? `${view.rows.length} rows (newest first)` : ''}</span>
      </div>
      {!view && <div className="empty small">Waiting for the first read…</div>}
      {view?.error && <div className="alert warn small">{view.error}</div>}
      {view && !view.error && view.rows.length === 0 && <div className="empty small">The table is empty.</div>}
      {view && !view.error && view.rows.length > 0 && (
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                {view.columns.map((c) => (
                  <th key={c}>{c}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {view.rows.map((row, i) => (
                <tr key={i}>
                  {row.map((cell, j) => (
                    <td key={j}>{cell ?? <em className="muted">null</em>}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
