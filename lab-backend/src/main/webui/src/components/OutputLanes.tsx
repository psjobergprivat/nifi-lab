import { useState } from 'react';
import type { Lane, SinkItem } from '../types';

interface Props {
  lanes: Lane[];
  items: SinkItem[];
  onTrace: (flowFileUuid: string) => void;
  onResend: (content: string) => void;
}

export function OutputLanes({ lanes, items, onTrace, onResend }: Props) {
  // Items for lanes the scenario did not declare still show up, in their own lane.
  const known = new Set(lanes.map((l) => l.id));
  const extra = [...new Set(items.map((i) => i.lane).filter((l) => !known.has(l)))].map((id) => ({
    id,
    title: id,
    description: 'Not declared in scenario.yaml',
  }));

  return (
    <>
      {[...lanes, ...extra].map((lane) => {
        const laneItems = items.filter((i) => i.lane === lane.id);
        return (
          <div className="panel lane" key={lane.id}>
            <div className="panel-head">
              <h2>{lane.title}</h2>
              <span className="count">{laneItems.length}</span>
            </div>
            {lane.description && <p className="muted small">{lane.description}</p>}
            {laneItems.length === 0 ? (
              <div className="empty small">Nothing has arrived yet.</div>
            ) : (
              laneItems
                .slice(0, 50)
                .map((item, index) => (
                  <ItemCard key={`${item.traceId}-${item.receivedAt}-${index}`} item={item} onTrace={onTrace} onResend={onResend} />
                ))
            )}
          </div>
        );
      })}
    </>
  );
}

function ItemCard({ item, onTrace, onResend }: { item: SinkItem } & Pick<Props, 'onTrace' | 'onResend'>) {
  const [open, setOpen] = useState(false);
  const attributes = Object.entries(item.attributes);
  return (
    <div className="item">
      <div className="item-meta">
        <span className="mono">{item.traceId?.slice(0, 8) ?? 'no trace id'}</span>
        <span className="muted small">{new Date(item.receivedAt).toLocaleTimeString()}</span>
      </div>
      <pre className="item-content">{item.content}</pre>
      <div className="item-actions">
        <button className="link" onClick={() => setOpen(!open)}>
          {open ? '▾' : '▸'} Attributes ({attributes.length})
        </button>
        <span className="spacer" />
        {item.attributes.uuid && (
          <button className="link" title="Show this FlowFile's provenance lineage" onClick={() => onTrace(item.attributes.uuid)}>
            Trace
          </button>
        )}
        <button className="link" title="Send this content into the flow again" onClick={() => onResend(item.content)}>
          Send again
        </button>
      </div>
      {open && (
        <table className="attributes">
          <tbody>
            {attributes.map(([key, value]) => (
              <tr key={key} className={key.startsWith('lab.') ? 'lab-attr' : undefined}>
                <th>{key}</th>
                <td>{value}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
