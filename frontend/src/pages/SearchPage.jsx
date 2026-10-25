import Autocomplete from '../components/Autocomplete.jsx';
import { useEffect, useState } from 'react';
import { api } from '../api.js';
import Gate from '../components/Gate.jsx';
import { ms, pctText, plural } from '../lib.js';

const enc = encodeURIComponent;

function Detail({ type, id, user, onOpenCourse }) {
  const [d, setD] = useState(null);
  useEffect(() => {
    setD(null);
    if (type === 'course') {
      api.get(`/api/academic/courses/${enc(id)}`).then((c) => setD({ course: c })).catch((e) => setD({ error: e.message }));
    } else if (type === 'student' && (user.role === 'ADMIN' || user.id === id)) {
      Promise.all([
        api.get(`/api/academic/people/${enc(id)}`),
        api.get(`/api/academic/students/${enc(id)}/attendance`),
        api.get(`/api/academic/students/${enc(id)}/grades`),
      ]).then(([p, a, g]) => setD({ p, a, g })).catch((e) => setD({ error: e.message }));
    } else {
      api.get(`/api/academic/people/${enc(id)}`).then((p) => setD({ p })).catch((e) => setD({ error: e.message }));
    }
  }, [type, id]);

  if (!d) return null;
  if (d.error) return <section className="card"><p className="error">{d.error}</p></section>;
  if (d.course) {
    const c = d.course;
    return (
      <section className="card">
        <h2>{c.code} {c.title}
          {user.role !== 'STUDENT' && <button className="small" onClick={() => onOpenCourse(c.id)}>Open course</button>}
        </h2>
        <p>{c.instructorName}, {plural(c.credits, 'credit')}, {c.scheduleText}</p>
        <p className={c.seatsLeft ? 'muted' : 'bad'}>{c.seatsLeft ? `${c.seatsLeft} of ${c.capacity} seats left` : 'Full'}</p>
      </section>
    );
  }
  if (!d.a) {
    return <section className="card"><h2>{d.p.person.name}</h2><p className="muted">{d.p.person.role}, {d.p.person.department}</p></section>;
  }
  const grade = Object.fromEntries(d.g.courses.map((x) => [x.courseId, x]));
  return (
    <section className="card">
      <h2>{d.p.person.name} <span className="badge">{d.p.credits} credits</span></h2>
      <p className="muted">{d.p.person.id}, {d.p.person.department}, year {d.p.person.year}</p>
      <table>
        <thead><tr><th>Course</th><th>Attendance</th><th>Grade</th></tr></thead>
        <tbody>
          {d.a.courses.map((a) => (
            <tr key={a.courseId}>
              <td><strong>{a.code}</strong> {a.title}</td>
              <td className={a.stats.status === 'low' ? 'bad' : ''}>{pctText(a.stats.pct)}</td>
              <td>{grade[a.courseId] && grade[a.courseId].letter ? grade[a.courseId].letter : '-'}</td>
            </tr>
          ))}
          {d.a.courses.length === 0 && <tr><td colSpan="3">Not registered for any course.</td></tr>}
        </tbody>
      </table>
    </section>
  );
}

function Metrics() {
  const [m, setM] = useState(null);
  const [busy, setBusy] = useState(false);
  const load = () => api.get('/api/academic/metrics').then(setM).catch(() => {});
  useEffect(() => { load(); }, []);
  const run = async () => {
    setBusy(true);
    try { await api.post('/api/academic/metrics/benchmark', { n: 400 }); } finally { setBusy(false); load(); }
  };
  const reset = async () => { await api.post('/api/academic/metrics/reset'); load(); };
  if (!m) return null;
  const L = m.searchLatency;
  const B = m.benchmark;
  const F = m.selectionFeedback;
  return (
    <section className="card">
      <h2>Search metrics</h2>
      <div className="row">
        <button onClick={run} disabled={busy}>{busy ? 'Running...' : 'Run accuracy benchmark'}</button>
        <button className="secondary" onClick={load}>Refresh</button>
        <button className="secondary" onClick={reset}>Clear live counters</button>
      </div>
      <h3>Response time (server side, ms)</h3>
      <table>
        <thead><tr><th>Searching for</th><th>Searches</th><th>Mean</th><th>p50</th><th>p95</th><th>p99</th><th>Max</th></tr></thead>
        <tbody>
          <tr><td><strong>All</strong></td><td>{L.count}</td><td>{ms(L.meanMs)}</td><td>{ms(L.p50Ms)}</td><td>{ms(L.p95Ms)}</td><td>{ms(L.p99Ms)}</td><td>{ms(L.maxMs)}</td></tr>
          {Object.entries(m.searchLatencyByType).map(([k, v]) => (
            <tr key={k}><td>{k}</td><td>{v.count}</td><td>{ms(v.meanMs)}</td><td>{ms(v.p50Ms)}</td><td>{ms(v.p95Ms)}</td><td>{ms(v.p99Ms)}</td><td>{ms(v.maxMs)}</td></tr>
          ))}
        </tbody>
      </table>
      <h3>Suggestion accuracy</h3>
      {B ? (
        <>
          <div className="stats">
            <div><b>{B.overall.first}%</b>right suggestion first</div>
            <div><b>{B.overall.top5}%</b>right suggestion in top 5</div>
            <div><b>{B.overall.mrr}</b>mean reciprocal rank</div>
            <div><b>{B.queries}</b>test queries</div>
          </div>
          <table>
            <thead><tr><th>Query style</th><th>Queries</th><th>First</th><th>Top 5</th></tr></thead>
            <tbody>{Object.entries(B.categories).map(([k, v]) => <tr key={k}><td>{k}</td><td>{v.n}</td><td>{v.first}%</td><td>{v.top5}%</td></tr>)}</tbody>
          </table>
          <p className="muted">Engine time during the benchmark: mean {ms(B.latencyMs.avg)} ms, p95 {ms(B.latencyMs.p95)} ms. Run at {B.ranAt}.</p>
        </>
      ) : <p className="muted">No benchmark yet. Press the button above.</p>}
      <h3>Real use</h3>
      {F.count ? (
        <p>{F.count} suggestions picked. {F.firstPct}% picked the first one, {F.top3Pct}% from the first three, average position {F.avgRank}.</p>
      ) : <p className="muted">When someone clicks a suggestion its position is recorded here.</p>}
      <p className="muted">Index: {Object.entries(m.index).filter(([, v]) => typeof v === 'object').map(([k, v]) => `${v.items} ${k}`).join(', ')}.</p>
    </section>
  );
}

function Search({ user, onOpenCourse }) {
  const types = user.role === 'STUDENT' ? [['course', 'Courses']] : [['student', 'Students'], ['faculty', 'Faculty'], ['course', 'Courses']];
  const [type, setType] = useState(types[types.length - 1][0] === 'course' && user.role === 'STUDENT' ? 'course' : 'student');
  const [q, setQ] = useState('');
  const [res, setRes] = useState(null);
  const [picked, setPicked] = useState(null);
  const choose = (r, rank) => {
    api.post('/api/academic/search/feedback', { rank }).catch(() => {});
    setPicked({ type, id: r.id });
  };

  return (
    <div>
      <section className="card">
        <h2>Search</h2>
        <div className="subtabs">
          {types.map(([k, label]) => (
            <button key={k} className={k === type ? 'active' : ''} onClick={() => { setType(k); setRes(null); setQ(''); setPicked(null); }}>{label}</button>
          ))}
        </div>
        <Autocomplete key={type} type={type} value={q} label={`Search ${type}s`} onText={text=>{setQ(text);setPicked(null);}} onResults={setRes} limit={12}
          placeholder={type==='course'?'Search course names or codes':'Search names or IDs'} onPick={r=>setPicked({type,id:r.id})}/>
        {res && <p className="muted">{plural(res.results.length, 'result')}, {res.tookMs.toFixed(2)} ms on the server, {res.rtt.toFixed(0)} ms round trip</p>}
        {res && (
          <table>
            <thead><tr><th>Name</th><th>ID</th><th>Details</th></tr></thead>
            <tbody>
              {res.results.map((r, i) => (
                <tr key={r.id} className="click" tabIndex={0} onKeyDown={e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();choose(r,i+1);}}} onClick={() => choose(r, i + 1)}><td><strong>{r.label}</strong></td><td>{r.id}</td><td className="muted">{r.sub}</td></tr>
              ))}
              {res.results.length === 0 && <tr><td colSpan="3">Nothing found. Check the spelling or try the ID.</td></tr>}
            </tbody>
          </table>
        )}
      </section>
      {picked && <Detail type={picked.type} id={picked.id} user={user} onOpenCourse={onOpenCourse} />}
      {user.role === 'ADMIN' && <Metrics />}
    </div>
  );
}

export default function SearchPage({ onOpenCourse }) {
  return <Gate>{(user) => <Search key={user.id} user={user} onOpenCourse={onOpenCourse} />}</Gate>;
}
