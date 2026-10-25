import { useEffect, useState } from 'react';
import { api } from '../api.js';
import Gate from '../components/Gate.jsx';
import { num, pctText } from '../lib.js';

function Grades({ user }) {
  const [d, setD] = useState(null);
  const [error, setError] = useState('');
  useEffect(() => {
    api.get(`/api/academic/students/${encodeURIComponent(user.id)}/grades`).then(setD).catch((e) => setError(e.message));
  }, [user.id]);

  if (error) return <p className="error">{error}</p>;
  if (!d) return <p>Loading...</p>;
  return (
    <div>
      <section className="card">
        <h2>Grades</h2>
        <div className="stats">
          <div><b>{d.gpa == null ? '-' : d.gpa.toFixed(2)}</b>GPA over {d.gradedCourses} graded course{d.gradedCourses === 1 ? '' : 's'}</div>
        </div>
        <p className="muted">Course result = total marks over total maximum of the exams graded so far. Scale: 90 O, 80 A+, 70 A, 60 B+, 50 B, 40 C.</p>
        {d.courses.length === 0 && <p>Register for a course to see grades here.</p>}
      </section>
      {d.courses.map((c) => (
        <section className="card" key={c.courseId}>
          <h2>{c.code} {c.title} {c.letter ? <span className="badge">{c.letter}, {pctText(c.percent)}</span> : <span className="badge">No marks yet</span>}</h2>
          {c.exams.length > 0 && (
            <table>
              <thead><tr><th>Exam</th><th>Marks</th><th>Out of</th><th>Grade</th></tr></thead>
              <tbody>{c.exams.map((e) => <tr key={e.exam}><td>{e.exam}</td><td>{num(e.marks)}</td><td>{num(e.maxMarks)}</td><td>{e.grade}</td></tr>)}</tbody>
            </table>
          )}
        </section>
      ))}
    </div>
  );
}

export default function GradesPage() {
  return <Gate roles={['STUDENT']} only="Students see their grades here. Faculty enter marks in the Faculty tab.">{(user) => <Grades user={user} />}</Gate>;
}
