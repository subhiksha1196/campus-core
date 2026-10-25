import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { pctText } from '../lib.js';

const enc = encodeURIComponent;

/**
 * Detail card for a student or faculty member picked from the home-page search.
 * Admins (and a student looking at themselves) also see attendance and grades.
 */
export default function PersonDetail({ type, id, user, onClose }) {
  const [d, setD] = useState(null);

  useEffect(() => {
    let active = true;
    setD(null);
    const fullView = type === 'student' && (user.role === 'ADMIN' || user.id === id);
    const load = fullView
      ? Promise.all([
          api.get(`/api/academic/people/${enc(id)}`),
          api.get(`/api/academic/students/${enc(id)}/attendance`),
          api.get(`/api/academic/students/${enc(id)}/grades`),
        ]).then(([p, a, g]) => ({ p, a, g }))
      : api.get(`/api/academic/people/${enc(id)}`).then((p) => ({ p }));
    load.then((x) => { if (active) setD(x); }).catch((e) => { if (active) setD({ error: e.message }); });
    return () => { active = false; };
  }, [type, id, user.id, user.role]);

  const close = onClose && (
    <button type="button" className="icon-btn" aria-label="Close details" onClick={onClose}><i className="fa-solid fa-xmark" /></button>
  );

  if (!d) return null;
  if (d.error) {
    return <section className="card"><div className="split"><p className="error">{d.error}</p>{close}</div></section>;
  }

  if (!d.a) {
    return (
      <section className="card">
        <div className="split"><h2>{d.p.person.name}</h2>{close}</div>
        <p className="muted">{d.p.person.role}, {d.p.person.department}</p>
      </section>
    );
  }

  const grade = Object.fromEntries(d.g.courses.map((x) => [x.courseId, x]));
  return (
    <section className="card">
      <div className="split"><h2>{d.p.person.name} <span className="badge">{d.p.credits} credits</span></h2>{close}</div>
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
