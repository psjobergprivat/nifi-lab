import type { ComponentState } from '../types';

/** What processors remember between FlowFiles (NiFi's state manager) - cleared by Reset. */
export function StatePanel({ names, states }: { names: string[]; states: ComponentState[] | null }) {
  return (
    <div className="panel">
      <div className="panel-head">
        <h2>Processor state</h2>
      </div>
      {names.map((name) => {
        const state = states?.find((s) => s.component === name);
        return (
          <div key={name} className="state-block">
            <strong>{name}</strong>
            {!state || state.entries.length === 0 ? (
              <p className="muted small">No state stored (yet).</p>
            ) : (
              <table className="attributes">
                <tbody>
                  {state.entries.map((e) => (
                    <tr key={`${e.scope}-${e.key}`}>
                      <th>{e.key}</th>
                      <td>{e.value}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        );
      })}
    </div>
  );
}
