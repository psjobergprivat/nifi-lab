import type { Step } from '../types';

export function StepsPanel({ steps, learn }: { steps: Step[]; learn: string[] }) {
  return (
    <div className="panel">
      <div className="panel-head">
        <h2>What happens</h2>
      </div>
      <ol className="steps">
        {steps.map((step) => (
          <li key={step.title}>
            <div className="step-title">
              <strong>{step.title}</strong>
              {step.nifiLink && (
                <a href={step.nifiLink} target="_blank" rel="noreferrer" className="small">
                  {step.component} in NiFi ↗
                </a>
              )}
            </div>
            <p>{step.text}</p>
          </li>
        ))}
      </ol>
      {learn.length > 0 && (
        <>
          <h3>Key ideas</h3>
          <ul className="learn">
            {learn.map((l) => (
              <li key={l}>{l}</li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}
