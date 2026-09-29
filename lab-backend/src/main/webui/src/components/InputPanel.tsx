import { useState } from 'react';
import { api } from '../api';
import type { ScenarioDetail } from '../types';
import type { SentInput } from '../useLive';

interface Props {
  scenario: ScenarioDetail;
  running: boolean;
  inputs: SentInput[];
  onError: (message: string) => void;
}

export function InputPanel({ scenario, running, inputs, onError }: Props) {
  const [text, setText] = useState(scenario.samples[0]?.content ?? '');
  const [sending, setSending] = useState(false);

  const targets = scenario.input.targets ?? [];

  const send = async (contents: string[], target?: string) => {
    setSending(true);
    try {
      for (const content of contents) {
        await api.sendInput(scenario.id, content, target);
      }
    } catch (e) {
      onError((e as Error).message);
    } finally {
      setSending(false);
    }
  };

  return (
    <div className="panel">
      <div className="panel-head">
        <h2>Input</h2>
        <span className="muted small">
          HTTP POST → port {scenario.input.port}/{scenario.input.path}
        </span>
      </div>
      {scenario.samples.length > 0 && (
        <div className="samples">
          {scenario.samples.map((s) => (
            <button key={s.name} className="chip" onClick={() => setText(s.content)}>
              {s.name}
            </button>
          ))}
        </div>
      )}
      <textarea
        value={text}
        rows={5}
        placeholder={scenario.input.placeholder ?? ''}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) send([text]);
        }}
      />
      <div className="row">
        {targets.length === 0 ? (
          <button className="primary" disabled={sending || !running} onClick={() => send([text])}>
            Send to NiFi
          </button>
        ) : (
          targets.map((t, i) => (
            <button
              key={t.id}
              className={i === 0 ? 'primary' : undefined}
              title={t.description ?? ''}
              disabled={sending || !running}
              onClick={() => send([text], t.id)}
            >
              {t.label}
            </button>
          ))
        )}
        {targets.length === 0 && scenario.samples.length > 1 && (
          <button disabled={sending || !running} onClick={() => send(scenario.samples.map((s) => s.content))}>
            Send all samples
          </button>
        )}
        {!running && <span className="muted small">Start the scenario to send data.</span>}
      </div>
      {inputs.length > 0 && (
        <ul className="sent-list">
          {inputs.slice(0, 8).map((i) => (
            <li key={i.traceId}>
              <span className="mono">{i.traceId.slice(0, 8)}</span>
              <span className="sent-content">{i.content}</span>
              {i.deliveredAt ? (
                <span className="badge ok small">{Math.max(0, i.deliveredAt - i.sentAt)} ms</span>
              ) : (
                <span className="badge warn small">in flow…</span>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
