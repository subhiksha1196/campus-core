import Autocomplete from '../components/Autocomplete.jsx';
import { useCallback, useEffect, useState } from 'react';
import { api } from '../api.js';

export default function CrawlerPage() {
  const [mode, setMode] = useState('parallel');
  const [threads, setThreads] = useState(8);
  const [notify, setNotify] = useState('');
  const [busy, setBusy] = useState(false);
  const [last, setLast] = useState(null);
  const [runs, setRuns] = useState([]);
  const [source, setSource] = useState('');
  const [listings, setListings] = useState({ total: 0, items: [] });
  const [error, setError] = useState('');

  const refresh = useCallback(async () => {
    try {
      const q = source ? `&source=${source}` : '';
      const [r, l] = await Promise.all([api.get('/api/crawl/runs'), api.get(`/api/listings?size=20${q}`)]);
      setRuns(r);
      setListings(l);
    } catch (e) {
      setError(e.message);
    }
  }, [source]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const run = async () => {
    setBusy(true);
    setError('');
    try {
      const ids = notify.split(',').map((s) => s.trim()).filter(Boolean);
      setLast(await api.post('/api/crawl', { mode, threads: Number(threads), notifyStudentIds: ids }));
      await refresh();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <section className="card">
        <h2>Opportunity crawler</h2><p className="muted">Find notices, scholarships and internships from simulated university sources. Each listing gets a short link for sharing.</p>
        <div className="aligned-form crawler-form">
          <label>Mode<select value={mode} onChange={(e) => setMode(e.target.value)}>
            <option value="parallel">parallel</option>
            <option value="sequential">sequential (baseline)</option>
          </select></label>
          <label>Workers
            <input type="number" min="1" max="64" value={threads} disabled={mode === 'sequential'}
                   onChange={(e) => setThreads(e.target.value)} />
          </label>
          <label>Notify students (optional)<Autocomplete type="student" label="Find notification recipients" value={notify} onText={setNotify} multiple selectValue={r=>r.id} placeholder="Search by name or enter IDs" /></label>
          <button onClick={run} disabled={busy}>{busy ? 'Crawling...' : 'Start crawl'}</button>
        </div>
        {error && <p className="error">{error}</p>}
        {last && (
          <p>
            {last.pagesFetched} pages, {last.listingsFound} listings ({last.newListings} new) in {last.fetchMs} ms -
            <strong> {last.pagesPerSec} pages/s</strong>
          </p>
        )}
      </section>

      <section className="card">
        <h2>Recent crawl runs</h2>
        <table>
          <thead><tr><th>Mode</th><th>Threads</th><th>Pages</th><th>Errors</th><th>Listings</th><th>ms</th><th>pages/s</th></tr></thead>
          <tbody>
            {runs.map((r) => (
              <tr key={r.id}>
                <td>{r.mode}</td><td>{r.threads}</td><td>{r.pagesFetched}</td><td>{r.pageErrors}</td>
                <td>{r.listingsFound}</td><td>{r.fetchMs}</td><td>{r.pagesPerSec}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <section className="card">
        <h2>Listings ({listings.total})
          <select value={source} onChange={(e) => setSource(e.target.value)}>
            <option value="">all sources</option>
            <option value="portal">portal</option>
            <option value="scholarships">scholarships</option>
            <option value="internships">internships</option>
          </select>
        </h2>
        <table>
          <thead><tr><th>Source</th><th>Title</th><th>Organization</th><th>Deadline</th><th>Link</th></tr></thead>
          <tbody>
            {listings.items.map((l) => (
              <tr key={l.id}>
                <td>{l.source}</td><td>{l.title}</td><td>{l.organization}</td><td>{l.deadline}</td>
                <td><a href={l.shortUrl} target="_blank" rel="noreferrer">Open listing ↗</a></td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </div>
  );
}
