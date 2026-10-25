import { useEffect, useState } from 'react';
import { api } from '../api.js';
import Gate from '../components/Gate.jsx';
import { pctText, plural } from '../lib.js';

function Attendance({ user }) {
  const [d, setD] = useState(null);
  const [error, setError] = useState('');
  useEffect(() => {
    api.get(`/api/academic/students/${encodeURIComponent(user.id)}/attendance`).then(setD).catch((e) => setError(e.message));
  }, [user.id]);

  if (error) return <p className="error">{error}</p>;
  if (!d) return <p>Loading...</p>;
  return (
    <section className="card">
      <h2>Attendance</h2>
      <p className="muted">The minimum is {d.minimum}% in every course (on duty counts as present). The dark tick on each bar marks it.</p>
      {d.courses.length === 0 && <p>Register for a course to see attendance here.</p>}
      {d.courses.map((c) => {
        const s = c.stats;
        const low = s.status === 'low';
        const note = s.status === 'none' ? 'No classes held yet.'
          : low ? `Attend the next ${plural(s.need, 'class', 'classes')} in a row to get back to ${d.minimum}%.`
            : s.canMiss > 0 ? `You can miss ${plural(s.canMiss, 'more class', 'more classes')} and stay above ${d.minimum}%.`
              : `You are just above ${d.minimum}%. Another absence would take you below it.`;
        return (
          <div key={c.courseId} className="attendance-course">
            <div className="split">
              <span><strong>{c.code}</strong> {c.title}</span>
              <span className={low ? 'bad' : ''}>{s.total ? `${s.present} of ${s.total} classes, ${pctText(s.pct)}` : '-'}</span>
            </div>
            <div className={`bar ${low ? 'low' : ''}`}><i style={{ width: `${s.pct || 0}%` }} /><u style={{ left: `${d.minimum}%` }} /></div>
            <small className={low ? 'bad' : 'muted'}>{note}</small>
          </div>
        );
      })}
    </section>
  );
}

export default function AttendancePage() {
  return <Gate roles={['STUDENT']} only="Students see their attendance here. Faculty mark attendance in the Faculty tab.">{(user) => <Attendance user={user} />}</Gate>;
}
