import { useEffect, useState } from 'react';
import { api } from '../api';
import type { LookupTable } from '../types';

/** The data a lookup service answers from. Edits are saved to the lab and used by NiFi's next lookup. */
export function LookupPanel({ scenarioId, table, onError }: { scenarioId: string; table: LookupTable; onError: (m: string) => void }) {
  const [rows, setRows] = useState(table.rows);
  const [dirty, setDirty] = useState(false);

  useEffect(() => {
    setRows(table.rows);
    setDirty(false);
  }, [table]);

  const edit = (index: number, column: string, value: string) => {
    setRows(rows.map((r, i) => (i === index ? { ...r, [column]: value } : r)));
    setDirty(true);
  };
  const remove = (index: number) => {
    setRows(rows.filter((_, i) => i !== index));
    setDirty(true);
  };
  const add = () => {
    setRows([...rows, Object.fromEntries(table.columns.map((c) => [c, '']))]);
    setDirty(true);
  };
  const save = () =>
    api
      .updateLookup(scenarioId, rows)
      .then(() => setDirty(false))
      .catch((e: Error) => onError(e.message));

  return (
    <div className="panel">
      <div className="panel-head">
        <h2>Lookup table</h2>
        <span className="muted small">key: {table.key}</span>
      </div>
      <div className="table-scroll">
        <table className="data-table editable">
          <thead>
            <tr>
              {table.columns.map((c) => (
                <th key={c}>{c}</th>
              ))}
              <th />
            </tr>
          </thead>
          <tbody>
            {rows.map((row, i) => (
              <tr key={i}>
                {table.columns.map((c) => (
                  <td key={c}>
                    <input value={row[c] ?? ''} onChange={(e) => edit(i, c, e.target.value)} aria-label={`${c} of row ${i + 1}`} />
                  </td>
                ))}
                <td>
                  <button className="link" onClick={() => remove(i)} title="Remove row">
                    ✕
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="row">
        <button onClick={add}>+ Row</button>
        <button className="primary" disabled={!dirty} onClick={save}>
          Save
        </button>
        {dirty && <span className="muted small">Unsaved changes</span>}
      </div>
    </div>
  );
}
