import type { DeploymentView } from '../types';

/** One badge that summarises deployment + run state. */
export function StatusBadge({ deployment }: { deployment: DeploymentView }) {
  const { state, runState, message } = deployment;
  if (state === 'DEPLOYED') {
    return runState === 'RUNNING' ? (
      <span className="badge ok">● Running</span>
    ) : (
      <span className="badge muted">■ Stopped</span>
    );
  }
  if (state === 'FAILED')
    return (
      <span className="badge error" title={message ?? ''}>
        Deploy failed
      </span>
    );
  if (state === 'DEPLOYING') return <span className="badge warn">Deploying…</span>;
  return (
    <span className="badge muted" title={message ?? ''}>
      Waiting for NiFi
    </span>
  );
}
