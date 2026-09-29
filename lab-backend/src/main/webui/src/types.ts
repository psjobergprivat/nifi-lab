// Mirrors the backend's JSON (org.mylabs.nifilab.*).

export type DeploymentState = 'PENDING' | 'DEPLOYING' | 'DEPLOYED' | 'FAILED';
export type RunState = 'UNKNOWN' | 'STOPPED' | 'RUNNING';

export interface DeploymentView {
  state: DeploymentState;
  runState: RunState;
  message: string | null;
  groupId: string | null;
  nifiLink: string | null;
}

export interface ScenarioSummary {
  id: string;
  order: number;
  title: string;
  summary: string;
  learn: string[];
  deployment: DeploymentView;
}

export interface Input {
  type: string;
  port: number;
  path: string;
  contentType: string | null;
  placeholder: string | null;
}

export interface Sample {
  name: string;
  content: string;
}

export interface Lane {
  id: string;
  title: string;
  description: string | null;
}

export interface Step {
  title: string;
  text: string;
  component: string | null;
  nifiLink: string | null;
}

export interface ScenarioDetail extends ScenarioSummary {
  input: Input;
  samples: Sample[];
  lanes: Lane[];
  steps: Step[];
  links: { provenance: string; bulletins: string };
}

export type NodeKind = 'PROCESSOR' | 'INPUT_PORT' | 'OUTPUT_PORT' | 'FUNNEL' | 'PROCESS_GROUP';

export interface FlowNodeView {
  id: string;
  kind: NodeKind;
  name: string;
  type: string;
  x: number;
  y: number;
  nifiLink: string;
}

export interface FlowEdgeView {
  id: string;
  sourceId: string;
  destinationId: string;
  relationships: string[];
  nifiLink: string;
}

export interface FlowView {
  groupId: string;
  nodes: FlowNodeView[];
  edges: FlowEdgeView[];
}

export interface ProcessorStatus {
  id: string;
  name: string;
  runStatus: string;
  flowFilesIn: number;
  flowFilesOut: number;
  taskCount: number;
  activeThreads: number;
}

export interface ConnectionStatus {
  id: string;
  sourceId: string;
  destinationId: string;
  queuedCount: number;
  queuedBytes: number;
  percentUseCount: number;
  percentUseBytes: number;
}

export interface FlowStatus {
  groupId: string;
  processors: ProcessorStatus[];
  connections: ConnectionStatus[];
  queuedCount: number;
  activeThreads: number;
}

export interface SinkItem {
  traceId: string | null;
  lane: string;
  content: string;
  attributes: Record<string, string>;
  receivedAt: number;
}

export interface Bulletin {
  id: number;
  sourceId: string;
  sourceName: string;
  level: string;
  message: string;
  timestamp: string;
}

export interface NifiInfo {
  reachable: boolean;
  version: string | null;
  uiUrl: string;
  message: string | null;
}

export interface LiveEvent {
  type: 'status' | 'bulletin' | 'item' | 'input' | 'reset' | 'deployment' | 'nifi' | 'heartbeat';
  scenarioId: string;
  timestamp: number;
  data: unknown;
}
