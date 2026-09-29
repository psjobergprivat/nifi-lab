import { useEffect, useMemo, useState } from 'react';
import { Background, Handle, MarkerType, Position, ReactFlow, type Edge, type Node, type NodeProps } from '@xyflow/react';
import { api } from '../api';
import type { LineageEvent, LineageView } from '../types';

type EventNodeData = { event: LineageEvent; offset: number; selected: boolean; focus: boolean };
type EventNode = Node<EventNodeData, 'event'>;

function EventCard({ data }: NodeProps<EventNode>) {
  const { event, offset, selected, focus } = data;
  return (
    <div className={`lineage-node type-${event.eventType.toLowerCase()} ${selected ? 'selected' : ''} ${focus ? 'focus' : ''}`}>
      <Handle type="target" position={Position.Top} />
      <div className="lineage-type">{event.eventType.replace(/_/g, ' ')}</div>
      <div className="lineage-component">{event.componentName}</div>
      <div className="lineage-meta">
        +{offset} ms · {event.flowFileUuid.slice(0, 8)}
      </div>
      <Handle type="source" position={Position.Bottom} />
    </div>
  );
}

const nodeTypes = { event: EventCard };

/** Layered layout: an event sits one row below the latest event that leads to it. */
function layout(view: LineageView, selectedId: string | null): { nodes: EventNode[]; edges: Edge[] } {
  const { events, links } = view.lineage;
  const depth = new Map<string, number>();
  const incoming = new Map<string, string[]>();
  links.forEach((l) => incoming.set(l.targetId, [...(incoming.get(l.targetId) ?? []), l.sourceId]));
  const depthOf = (id: string, seen = new Set<string>()): number => {
    if (depth.has(id)) return depth.get(id)!;
    if (seen.has(id)) return 0;
    seen.add(id);
    const d = Math.max(-1, ...(incoming.get(id) ?? []).map((p) => depthOf(p, seen))) + 1;
    depth.set(id, d);
    return d;
  };
  events.forEach((e) => depthOf(e.id));

  const rows = new Map<number, LineageEvent[]>();
  events.forEach((e) => rows.set(depth.get(e.id)!, [...(rows.get(depth.get(e.id)!) ?? []), e]));
  const start = Math.min(...events.map((e) => e.millis));
  const nodes: EventNode[] = [];
  rows.forEach((row, d) =>
    row.forEach((e, i) =>
      nodes.push({
        id: e.id,
        type: 'event',
        position: { x: (i - (row.length - 1) / 2) * 230, y: d * 110 },
        data: {
          event: e,
          offset: e.millis - start,
          selected: e.id === selectedId,
          focus: e.flowFileUuid === view.lineage.flowFileUuid,
        },
        draggable: false,
      }),
    ),
  );
  const edges: Edge[] = links.map((l, i) => ({
    id: `${l.sourceId}-${l.targetId}-${i}`,
    source: l.sourceId,
    target: l.targetId,
    type: 'smoothstep',
    markerEnd: { type: MarkerType.ArrowClosed },
  }));
  return { nodes, edges };
}

export function LineageModal({ flowFileUuid, onClose }: { flowFileUuid: string; onClose: () => void }) {
  const [view, setView] = useState<LineageView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const load = () => {
    setError(null);
    setView(null);
    api
      .lineage(flowFileUuid)
      .then(setView)
      .catch((e: Error) => setError(e.message));
  };
  useEffect(load, [flowFileUuid]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const graph = useMemo(() => (view && view.lineage.events.length > 0 ? layout(view, selectedId) : null), [view, selectedId]);
  const selected = view?.lineage.events.find((e) => e.id === selectedId);

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-label="Lineage">
        <div className="panel-head">
          <h2>
            Lineage of <span className="mono">{flowFileUuid.slice(0, 8)}</span>
          </h2>
          <div className="actions">
            {view && (
              <a className="button ghost" href={view.provenanceLink} target="_blank" rel="noreferrer">
                Provenance in NiFi ↗
              </a>
            )}
            <button onClick={load}>↻ Reload</button>
            <button onClick={onClose}>✕ Close</button>
          </div>
        </div>
        <p className="muted small">
          Every box is a provenance event NiFi recorded; arrows show how FlowFiles moved from one event to the next. Highlighted boxes belong to
          this FlowFile. Click an event to see what it changed.
        </p>
        {error && <div className="alert error">{error}</div>}
        {!view && !error && <div className="empty">Asking NiFi for the lineage…</div>}
        {view && !graph && (
          <div className="empty">NiFi has no provenance events for this FlowFile yet - it indexes them within a few seconds. Try Reload.</div>
        )}
        {graph && (
          <div className="lineage-layout">
            <div className="lineage-canvas">
              <ReactFlow
                nodes={graph.nodes}
                edges={graph.edges}
                nodeTypes={nodeTypes}
                fitView
                nodesConnectable={false}
                elementsSelectable={false}
                proOptions={{ hideAttribution: true }}
                onNodeClick={(_, n) => setSelectedId(n.id)}
              >
                <Background gap={20} size={1} />
              </ReactFlow>
            </div>
            <div className="lineage-details">
              {selected ? (
                <>
                  <h3>
                    {selected.eventType} · {selected.componentName}
                  </h3>
                  <p className="small muted">
                    {selected.componentType} · event {selected.id} · FlowFile <span className="mono">{selected.flowFileUuid}</span>
                  </p>
                  {selected.details && <p className="small">{selected.details}</p>}
                  {selected.attributeChanges.length === 0 ? (
                    <p className="small muted">No attribute changes.</p>
                  ) : (
                    <table className="attributes">
                      <tbody>
                        {selected.attributeChanges.map((a) => (
                          <tr key={a.name}>
                            <th>{a.name}</th>
                            <td>
                              {a.previousValue !== null && <del className="muted">{a.previousValue}</del>} {a.value ?? <em>removed</em>}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                </>
              ) : (
                <p className="muted small">Select an event.</p>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
