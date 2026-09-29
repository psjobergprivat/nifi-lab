import { useMemo } from 'react';
import {
  Background,
  Controls,
  Handle,
  MarkerType,
  Position,
  ReactFlow,
  type Edge,
  type Node,
  type NodeProps,
} from '@xyflow/react';
import type { FlowStatus, FlowView, NodeKind, ProcessorStatus } from '../types';

/** NiFi canvas coordinates are large (a processor is ~350px wide); scale them down to fit our node size. */
const SCALE = 0.6;

type NifiNodeData = {
  name: string;
  type: string;
  kind: NodeKind;
  link: string;
  status?: ProcessorStatus;
};
type NifiNode = Node<NifiNodeData, 'nifi'>;

function NifiNodeCard({ data }: NodeProps<NifiNode>) {
  const status = data.status;
  const runStatus = status?.runStatus ?? (data.kind === 'PROCESSOR' ? 'Unknown' : '');
  const active = (status?.activeThreads ?? 0) > 0;
  return (
    <div className={`nifi-node run-${runStatus.toLowerCase()} ${active ? 'active' : ''}`}>
      <Handle type="target" position={Position.Top} />
      <div className="nifi-node-head">
        <span className="run-dot" title={runStatus} />
        <span className="nifi-node-name">{data.name}</span>
      </div>
      <div className="nifi-node-type">{data.type}</div>
      {status && (
        <div className="nifi-node-stats">
          <span title="FlowFiles in (5 min)">in {status.flowFilesIn}</span>
          <span title="FlowFiles out (5 min)">out {status.flowFilesOut}</span>
          <span title="Tasks run (5 min)">tasks {status.taskCount}</span>
        </div>
      )}
      <Handle type="source" position={Position.Bottom} />
    </div>
  );
}

const nodeTypes = { nifi: NifiNodeCard };

function queueColor(percent: number): string {
  if (percent >= 80) return 'var(--danger)';
  if (percent >= 50) return 'var(--warning)';
  return 'var(--edge)';
}

export function FlowDiagram({ flow, status }: { flow: FlowView; status: FlowStatus | null }) {
  const processorStatus = useMemo(() => new Map(status?.processors.map((p) => [p.id, p]) ?? []), [status]);
  const connectionStatus = useMemo(() => new Map(status?.connections.map((c) => [c.id, c]) ?? []), [status]);

  const nodes: NifiNode[] = flow.nodes.map((n) => ({
    id: n.id,
    type: 'nifi',
    position: { x: n.x * SCALE, y: n.y * SCALE },
    data: { name: n.name, type: n.type, kind: n.kind, link: n.nifiLink, status: processorStatus.get(n.id) },
    draggable: false,
  }));

  const edges: Edge[] = flow.edges.map((e) => {
    const c = connectionStatus.get(e.id);
    const queued = c?.queuedCount ?? 0;
    const percent = Math.max(c?.percentUseCount ?? 0, c?.percentUseBytes ?? 0);
    const sourceRunning = processorStatus.get(e.sourceId)?.runStatus === 'Running';
    const color = queueColor(percent);
    return {
      id: e.id,
      source: e.sourceId,
      target: e.destinationId,
      type: 'smoothstep',
      animated: sourceRunning,
      label: `${e.relationships.join(', ')}${queued > 0 ? ` · ${queued} queued` : ''}`,
      labelStyle: { fontWeight: queued > 0 ? 700 : 500, fill: queued > 0 ? color : undefined },
      labelBgPadding: [6, 3] as [number, number],
      labelBgBorderRadius: 4,
      style: { stroke: color, strokeWidth: 2 + Math.min(queued, 20) / 4 },
      markerEnd: { type: MarkerType.ArrowClosed, color },
      data: { link: e.nifiLink },
    };
  });

  return (
    <div className="flow-canvas">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        fitView
        fitViewOptions={{ padding: 0.15 }}
        nodesConnectable={false}
        elementsSelectable={false}
        proOptions={{ hideAttribution: true }}
        onNodeClick={(_, node) => window.open((node.data as NifiNodeData).link, '_blank', 'noreferrer')}
        onEdgeClick={(_, edge) => window.open((edge.data as { link: string }).link, '_blank', 'noreferrer')}
      >
        <Background gap={20} size={1} />
        <Controls showInteractive={false} />
      </ReactFlow>
    </div>
  );
}
