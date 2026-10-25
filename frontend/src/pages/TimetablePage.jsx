import { useEffect, useState } from 'react';
import { api } from '../api.js';
import Gate from '../components/Gate.jsx';
import { plural } from '../lib.js';

function Timetable({ user }) {
  const [d, setD] = useState(null);
  const [error, setError] = useState('');
  useEffect(() => {
    const key = user.role === 'STUDENT' ? 'studentId' : 'instructorId';
    api.get(`/api/academic/timetable?${key}=${encodeURIComponent(user.id)}`).then(setD).catch((e) => setError(e.message));
  }, [user.id, user.role]);

  if (error) return <p className="error">{error}</p>;
  if (!d) return <p>Loading...</p>;
  return (
    <section className="card">
      <h2>Timetable <span className="badge">{plural(d.slots.length, 'class', 'classes')} a week</span></h2>
      {d.slots.length === 0 && <p className="muted">{user.role === 'STUDENT' ? 'You have not registered for any course yet. Open Courses to start.' : 'No classes assigned.'}</p>}
      <div style={{ overflowX: 'auto' }}>
        <table className="tt">
          <thead><tr><th>Time</th>{d.days.map((x) => <th key={x}>{x}</th>)}</tr></thead>
          <tbody>
            {d.periods.map((p) => (
              <tr key={p[0]}>
                <td>{p[0]} to {p[1]}</td>
                {d.days.map((day) => (
                  <td key={day}>
                    {d.slots.filter((s) => s.day === day && s.startTime === p[0]).map((s) => (
                      <div className="slot" key={s.courseId}><strong>{s.code}</strong><br />{s.title}<br /><small>Room {s.room}</small></div>
                    ))}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

export default function TimetablePage() {
  return <Gate roles={['STUDENT', 'FACULTY']} only="Timetables are shown for students and faculty.">{(user) => <Timetable user={user} />}</Gate>;
}
