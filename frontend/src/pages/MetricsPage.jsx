import { useEffect, useState } from 'react';
import { api } from '../api.js';

function Timer({ name, t }) {
  if (!t) return null;
  return (
    <tr>
      <td>{name}</td><td>{t.count}</td><td>{t.meanMs}</td><td>{t.p50Ms}</td><td>{t.p95Ms}</td><td>{t.p99Ms}</td><td>{t.maxMs}</td>
    </tr>
  );
}

export default function MetricsPage() {
  const [m, setM] = useState(null);
  const [error, setError] = useState('');

  useEffect(() => {
    const load = () => api.get('/api/metrics/summary').then((d) => { setM(d); setError(''); }).catch((e) => setError(e.message));
    load();
    const id = setInterval(load, 3000);
    return () => clearInterval(id);
  }, []);

  if (error) return <p className="error">{error}</p>;
  if (!m) return <p>Loading...</p>;

  return (
    <div>
      <section className="card">
        <h2>Server-side latency (ms)</h2>
        <table>
          <thead><tr><th>Operation</th><th>Count</th><th>Mean</th><th>p50</th><th>p95</th><th>p99</th><th>Max</th></tr></thead>
          <tbody>
            <Timer name="Redirect (Redis cached)" t={m.redirectLatency.cached} />
            <Timer name="Redirect (DB-only baseline)" t={m.redirectLatency.baseline} />
            <Timer name="Crawler page fetch" t={m.crawlFetchLatency} />
            <Timer name="Notification write" t={m.notifications.writeLatency} />
          </tbody>
        </table>
      </section>
      {m.person1 && <section className="card"><h2>IDs and rate limiting</h2><p>IDs generated: {m.person1.idsGenerated} · Mean generation rate since startup: {m.person1.idGenerationRate.toFixed(2)}/s · Collisions: {m.person1.collisionCount}</p><p>Allowed: {m.person1.requestsAllowed} · Rejected: {m.person1.requestsRejected} · Rejection rate: {(100*m.person1.rejectionRate).toFixed(2)}%</p></section>}
      <div className="grid">
        <section className="card">
          <h2>Short-link cache</h2>
          <p>Hits: {m.cache.hits} | Misses: {m.cache.misses} | Hit ratio: {(m.cache.hitRatio * 100).toFixed(1)}%</p>
        </section>
        <section className="card">
          <h2>Resources</h2>
          <p>Process CPU: {m.resources.processCpuPercent}% | JVM heap: {m.resources.jvmHeapUsedMb} MB</p>
          <p>Notifications created: {m.notifications.created}</p>
        </section>
      </div>
    </div>
  );
}
