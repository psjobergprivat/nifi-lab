import type { Bulletin } from '../types';

export function BulletinFeed({ bulletins, bulletinsLink }: { bulletins: Bulletin[]; bulletinsLink: string }) {
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
        <ul className="bulletins">
          {bulletins.map((b) => (
            <li key={b.id} className={`bulletin ${b.level.toLowerCase()}`}>
              <div>
                <strong>{b.sourceName}</strong> <span className="muted small">{b.timestamp}</span>
                <span className={`badge small ${b.level === 'ERROR' ? 'error' : 'warn'}`}>{b.level}</span>
              </div>
              <p>{b.message}</p>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
