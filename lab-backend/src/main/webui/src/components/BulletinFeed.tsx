import type { Bulletin, ExpectedBulletin } from '../types';

interface Props {
  bulletins: Bulletin[];
  expected: ExpectedBulletin[];
  bulletinsLink: string;
}

function explain(bulletin: Bulletin, expected: ExpectedBulletin[]): ExpectedBulletin | undefined {
  return expected.find((e) => e.component === bulletin.sourceName && bulletin.message.includes(e.contains));
}

export function BulletinFeed({ bulletins, expected, bulletinsLink }: Props) {
  const unexpected = bulletins.filter((b) => !explain(b, expected)).length;
  return (
    <div className="panel">
      <div className="panel-head">
        <h2>Bulletins</h2>
        <a className="small" href={bulletinsLink} target="_blank" rel="noreferrer">
          Bulletin board ↗
        </a>
      </div>
      {bulletins.length === 0 ? (
        <div className="empty small">No warnings or errors. NiFi posts a bulletin when a processor fails.</div>
      ) : (
        <>
          {unexpected === 0 && (
            <p className="muted small">All bulletins below are caused on purpose by this scenario - nothing is wrong.</p>
          )}
          <ul className="bulletins">
            {bulletins.map((b) => {
              const expectation = explain(b, expected);
              return (
                <li key={b.id} className={`bulletin ${expectation ? 'expected' : b.level.toLowerCase()}`}>
                  <div>
                    <strong>{b.sourceName}</strong> <span className="muted small" title={b.timestamp}>
                      {b.time ? new Date(b.time).toLocaleTimeString() : b.timestamp}
                    </span>
                    {expectation ? (
                      <span className="badge small info">Expected</span>
                    ) : (
                      <span className={`badge small ${b.level === 'ERROR' ? 'error' : 'warn'}`}>{b.level}</span>
                    )}
                  </div>
                  {expectation && <p className="bulletin-explanation">{expectation.explanation}</p>}
                  <p className={expectation ? 'muted' : undefined}>{b.message}</p>
                </li>
              );
            })}
          </ul>
        </>
      )}
    </div>
  );
}
