import { useCallback, useEffect, useState } from 'react';
import { api } from '../api.js';

export default function ShortenerPage() {
  const [url, setUrl] = useState('');
  const [alias, setAlias] = useState('');
  const [created, setCreated] = useState(null);
  const [recent, setRecent] = useState([]);
  const [error, setError] = useState('');

  const load = useCallback(() => api.get('/api/urls').then(setRecent).catch((e) => setError(e.message)), []);
  useEffect(() => {
    load();
  }, [load]);

  const submit = async (e) => {
    e.preventDefault();
    try {
      setCreated(await api.post('/api/urls', { url, alias: alias || null }));
      setError('');
      setUrl('');
      setAlias('');
      load();
    } catch (err) {
      setError(err.message);
    }
  };

  return (
    <div className="grid">
      <section className="card">
        <h2>Campus links</h2><p className="muted">Notifications automatically shorten message links, and the opportunity crawler creates a short link for each listing. Use this tool to create a link manually for a campus announcement.</p>
        <form onSubmit={submit}>
          <label>Long URL
            <input value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://..." required />
          </label>
          <label>Custom alias (optional)
            <input value={alias} onChange={(e) => setAlias(e.target.value)} placeholder="exam-2026" />
          </label>
          <button type="submit">Shorten</button>
        </form>
        {error && <p className="error">{error}</p>}
        {created && (
          <>
            {created.message && <p className="muted">{created.message}</p>}
            <p>Short link: <a href={created.shortUrl} target="_blank" rel="noreferrer">{created.shortUrl}</a></p>
          </>
        )}
      </section>

      <section className="card">
        <h2>Recent links <button onClick={load}>Refresh</button></h2>
        <table>
          <thead><tr><th>Short</th><th>Target</th><th>Clicks</th></tr></thead>
          <tbody>
            {recent.map((r) => (
              <tr key={r.code}>
                <td><a href={r.shortUrl} target="_blank" rel="noreferrer">{r.code}</a></td>
                <td className="ellipsis">{r.url}</td>
                <td>{r.clicks}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </div>
  );
}
