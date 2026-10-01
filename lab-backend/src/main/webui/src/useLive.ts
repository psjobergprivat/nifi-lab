import { useEffect, useReducer } from 'react';
import type { Bulletin, ComponentState, FlowStatus, LiveEvent, LookupTable, ProducerStats, ServiceCall, SinkItem, TableView } from './types';

/** Something the user sent, and (once it comes out the other end) how long it took. */
export interface SentInput {
  traceId: string;
  content: string;
  sentAt: number;
  deliveredAt?: number;
}

export interface LiveState {
  connected: boolean;
  status: FlowStatus | null;
  items: SinkItem[];
  inputs: SentInput[];
  bulletins: Bulletin[];
  nifiError: string | null;
  /** Latest control values pushed by the backend (null until one changes). */
  controlValues: Record<string, string> | null;
  serviceCalls: (ServiceCall & { at: number })[];
  producer: ProducerStats | null;
  lookup: LookupTable | null;
  database: TableView | null;
  state: ComponentState[] | null;
  /** Bumped on every deployment event so the view can refetch the scenario. */
  deploymentVersion: number;
}

const MAX_ITEMS = 200;
const MAX_BULLETINS = 50;

const initial: LiveState = {
  connected: false,
  status: null,
  items: [],
  inputs: [],
  bulletins: [],
  nifiError: null,
  controlValues: null,
  serviceCalls: [],
  producer: null,
  lookup: null,
  database: null,
  state: null,
  deploymentVersion: 0,
};

type Action = { kind: 'event'; event: LiveEvent } | { kind: 'connected'; value: boolean } | { kind: 'clear' };

function reducer(state: LiveState, action: Action): LiveState {
  if (action.kind === 'connected') return { ...state, connected: action.value };
  if (action.kind === 'clear') return { ...initial, connected: state.connected, status: state.status };

  const { event } = action;
  switch (event.type) {
    case 'status':
      return { ...state, status: event.data as FlowStatus, nifiError: null };
    case 'item': {
      const item = event.data as SinkItem;
      const inputs = state.inputs.map((i) =>
        i.traceId === item.traceId && !i.deliveredAt ? { ...i, deliveredAt: item.receivedAt } : i,
      );
      return { ...state, inputs, items: [item, ...state.items].slice(0, MAX_ITEMS) };
    }
    case 'input': {
      const { traceId, content } = event.data as { traceId: string; content: string };
      if (state.inputs.some((i) => i.traceId === traceId)) return state;
      // A fast flow can deliver the result before the "input" event arrives.
      const deliveredAt = state.items.find((i) => i.traceId === traceId || i.traceIds?.includes(traceId))?.receivedAt;
      const input: SentInput = { traceId, content, sentAt: event.timestamp, deliveredAt };
      return { ...state, inputs: [input, ...state.inputs].slice(0, MAX_ITEMS) };
    }
    case 'traces': {
      // A merged output has no single trace id; the backend found its inputs in the provenance lineage.
      const { flowFileUuid, traceIds } = event.data as { flowFileUuid: string; traceIds: string[] };
      const merged = state.items.find((i) => i.attributes.uuid === flowFileUuid);
      const deliveredAt = merged?.receivedAt ?? event.timestamp;
      return {
        ...state,
        items: state.items.map((i) => (i.attributes.uuid === flowFileUuid ? { ...i, traceIds } : i)),
        inputs: state.inputs.map((i) =>
          traceIds.includes(i.traceId) && !i.deliveredAt ? { ...i, deliveredAt } : i,
        ),
      };
    }
    case 'bulletin': {
      const bulletin = event.data as Bulletin;
      if (state.bulletins.some((b) => b.id === bulletin.id)) return state;
      return { ...state, bulletins: [bulletin, ...state.bulletins].slice(0, MAX_BULLETINS) };
    }
    case 'reset':
      return {
        ...initial,
        connected: state.connected,
        controlValues: state.controlValues,
        lookup: state.lookup,
        deploymentVersion: state.deploymentVersion + 1,
      };
    case 'deployment':
      return { ...state, deploymentVersion: state.deploymentVersion + 1 };
    case 'controls':
      return { ...state, controlValues: event.data as Record<string, string> };
    case 'service': {
      const call = { ...(event.data as ServiceCall), at: event.timestamp };
      return { ...state, serviceCalls: [call, ...state.serviceCalls].slice(0, MAX_ITEMS) };
    }
    case 'producer':
      return { ...state, producer: event.data as ProducerStats };
    case 'lookup':
      return { ...state, lookup: event.data as LookupTable };
    case 'database':
      return { ...state, database: event.data as TableView };
    case 'state':
      return { ...state, state: event.data as ComponentState[] };
    case 'nifi':
      return { ...state, nifiError: (event.data as { message: string }).message };
    default:
      return state;
  }
}

/** Subscribes to the backend's SSE stream for one scenario. */
export function useLive(scenarioId: string): [LiveState, () => void] {
  const [state, dispatch] = useReducer(reducer, initial);

  useEffect(() => {
    const source = new EventSource(`/api/live/${encodeURIComponent(scenarioId)}`);
    source.onopen = () => dispatch({ kind: 'connected', value: true });
    source.onerror = () => dispatch({ kind: 'connected', value: false }); // EventSource reconnects by itself
    source.onmessage = (message) => dispatch({ kind: 'event', event: JSON.parse(message.data) as LiveEvent });
    return () => source.close();
  }, [scenarioId]);

  return [state, () => dispatch({ kind: 'clear' })];
}
