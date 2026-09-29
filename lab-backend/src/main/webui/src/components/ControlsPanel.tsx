import { useEffect, useRef, useState } from 'react';
import { api } from '../api';
import type { Control } from '../types';

interface Props {
  scenarioId: string;
  controls: Control[];
  values: Record<string, string>;
  onError: (message: string) => void;
}

/** Knobs declared in scenario.yaml. Changes are sent to the backend (debounced) and take effect immediately. */
export function ControlsPanel({ scenarioId, controls, values, onError }: Props) {
  const [local, setLocal] = useState(values);
  const pending = useRef<Record<string, string>>({});
  const timer = useRef<number>();

  // Values changed elsewhere (reset, another browser) win over local edits that were already sent.
  useEffect(() => setLocal(values), [values]);

  const change = (id: string, value: string) => {
    setLocal((current) => ({ ...current, [id]: value }));
    pending.current[id] = value;
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => {
      const changes = pending.current;
      pending.current = {};
      api.updateControls(scenarioId, changes).catch((e: Error) => onError(e.message));
    }, 250);
  };

  return (
    <div className="panel">
      <div className="panel-head">
        <h2>Controls</h2>
      </div>
      <div className="controls">
        {controls.map((c) => (
          <label key={c.id} className="control">
            <span className="control-label">
              {c.label}
              <strong>
                {local[c.id] ?? c.defaultValue}
                {c.type === 'slider' ? ` ${c.unit ?? ''}` : ''}
              </strong>
            </span>
            {c.type === 'select' ? (
              <select value={local[c.id] ?? ''} onChange={(e) => change(c.id, e.target.value)}>
                {c.options?.map((o) => (
                  <option key={o}>{o}</option>
                ))}
              </select>
            ) : (
              <input
                type="range"
                min={c.min ?? 0}
                max={c.max ?? 100}
                step={c.step ?? 1}
                value={local[c.id] ?? c.defaultValue ?? 0}
                onChange={(e) => change(c.id, e.target.value)}
              />
            )}
            {c.description && <span className="muted small">{c.description}</span>}
          </label>
        ))}
      </div>
    </div>
  );
}
