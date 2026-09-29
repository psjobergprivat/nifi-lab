import type { DeploymentView, FlowView, LineageView, NifiInfo, ScenarioDetail, ScenarioSummary } from './types';

async function request<T>(method: string, url: string, body?: string, contentType = 'text/plain; charset=UTF-8'): Promise<T> {
  const response = await fetch(url, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': contentType },
    body,
  });
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      message = (await response.json()).message ?? message;
    } catch {
      // not JSON - keep the status line
    }
    throw new Error(message);
  }
  return response.status === 204 ? (undefined as T) : response.json();
}

export const api = {
  nifi: () => request<NifiInfo>('GET', '/api/nifi'),
  scenarios: () => request<ScenarioSummary[]>('GET', '/api/scenarios'),
  scenario: (id: string) => request<ScenarioDetail>('GET', `/api/scenarios/${id}`),
  flow: (id: string) => request<FlowView>('GET', `/api/scenarios/${id}/flow`),
  start: (id: string) => request<DeploymentView>('POST', `/api/scenarios/${id}/start`),
  stop: (id: string) => request<DeploymentView>('POST', `/api/scenarios/${id}/stop`),
  reset: (id: string) => request<DeploymentView>('POST', `/api/scenarios/${id}/reset`),
  deploy: (id: string) => request<DeploymentView>('POST', `/api/scenarios/${id}/deploy`),
  sendInput: (id: string, content: string) =>
    request<{ traceId: string }>('POST', `/api/scenarios/${id}/input`, content),
  updateControls: (id: string, values: Record<string, string>) =>
    request<Record<string, string>>('PUT', `/api/scenarios/${id}/controls`, JSON.stringify(values), 'application/json'),
  lineage: (flowFileUuid: string) => request<LineageView>('GET', `/api/lineage/${flowFileUuid}`),
};
