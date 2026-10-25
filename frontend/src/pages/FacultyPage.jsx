import { useCallback, useEffect, useState } from 'react';
import { api } from '../api.js';
import Gate from '../components/Gate.jsx';
import { num, pctText, todayIso } from '../lib.js';

const enc = encodeURIComponent;

/* ------------------------------------------------------------ roster */
function RosterTab({ id }) {
  const [rows, setRows] = useState(null);
  useEffect(() => { api.get(`/api/academic/courses/${enc(id)}/roster`).then(setRows).catch(() => setRows([])); }, [id]);
  if (!rows) return <p>Loading...</p>;
  return (
    <table>
      <thead><tr><th>ID</th><th>Name</th><th>Department</th><th>Year</th><th>Attendance</th><th /></tr></thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.id}>
            <td>{r.id}</td><td>{r.name}</td><td>{r.department}</td><td>{r.year}</td>
            <td className={r.attendance.status === 'low' ? 'bad' : ''}>
              {r.attendance.total ? `${pctText(r.attendance.pct)} (${r.attendance.present} of ${r.attendance.total})` : '-'}
            </td>
            <td>{r.attendance.status === 'low' && <span className="badge bad-badge">Below minimum</span>}</td>
          </tr>
        ))}
        {rows.length === 0 && <tr><td colSpan="6">No students have registered yet.</td></tr>}
      </tbody>
    </table>
  );
}

/* ------------------------------------------------------------ attendance */
function AttendanceTab({ id, canManage, say }) {
  const [sheet, setSheet] = useState(null);
  const [status, setStatus] = useState({});

  const load = useCallback(async (date) => {
    try {
      const s = await api.get(`/api/academic/courses/${enc(id)}/attendance${date ? `?date=${date}` : ''}`);
      setSheet(s);
      setStatus(Object.fromEntries(s.students.map((x) => [x.id, x.status])));
    } catch (e) { say(e.message, true); }
  }, [id, say]);
  useEffect(() => { load(); }, [load]);

  if (!sheet) return <p>Loading...</p>;
  const values = Object.values(status);
  const count = (s) => values.filter((v) => v === s).length;
  const setAll = (v) => setStatus(Object.fromEntries(Object.keys(status).map((k) => [k, v])));
  const weekday = new Date(`${sheet.date}T00:00`).toLocaleDateString('en', { weekday: 'long' });

  const save = async () => {
    try {
      const body = {
        date: sheet.date,
        absent: Object.keys(status).filter((k) => status[k] === 'ABSENT'),
        onDuty: Object.keys(status).filter((k) => status[k] === 'OD'),
      };
      const r = await api.post(`/api/academic/courses/${enc(id)}/attendance`, body);
      say(`Attendance saved for ${r.date}: ${r.present} of ${r.total} present`);
      load(sheet.date);
    } catch (e) { say(e.message, true); }
  };

  return (
    <div>
      <div className="row">
        <label className="inline">Class date
          <input type="date" value={sheet.date} max={todayIso()} style={{ width: 170 }} onChange={(e) => e.target.value && load(e.target.value)} />
        </label>
        <span className="muted">{weekday}. {sheet.scheduled ? '' : 'This course does not normally meet on this day. '}
          {sheet.exists ? 'Already recorded. Saving replaces it.' : 'Nothing recorded yet. Everyone starts as present.'}</span>
      </div>
      <div className="row">
        <span className="badge">{count('PRESENT')} present, {count('ABSENT')} absent, {count('OD')} on duty</span>
        {canManage && <>
          <button className="secondary small" onClick={() => setAll('PRESENT')}>Mark all present</button>
          <button className="secondary small" onClick={() => setAll('ABSENT')}>Mark all absent</button>
          <button className="small" onClick={save}>Save attendance</button>
        </>}
      </div>
      <table>
        <thead><tr><th>ID</th><th>Name</th><th>Status</th></tr></thead>
        <tbody>
          {sheet.students.map((s) => (
            <tr key={s.id}>
              <td>{s.id}</td><td>{s.name}</td>
              <td>
                <select value={status[s.id]} disabled={!canManage} style={{ width: 130 }} onChange={(e) => setStatus({ ...status, [s.id]: e.target.value })}>
                  <option value="PRESENT">Present</option><option value="ABSENT">Absent</option><option value="OD">On duty</option>
                </select>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <h3>Earlier classes</h3>
      <table>
        <thead><tr><th>Date</th><th>Attended</th><th>Rate</th><th /></tr></thead>
        <tbody>
          {sheet.sessions.map((x) => (
            <tr key={x.date}>
              <td>{x.date}</td><td>{x.present} of {x.total}</td><td>{x.total ? pctText(100 * x.present / x.total) : '-'}</td>
              <td><button className="secondary small" onClick={() => load(x.date)}>Open</button></td>
            </tr>
          ))}
          {sheet.sessions.length === 0 && <tr><td colSpan="4">No classes recorded yet.</td></tr>}
        </tbody>
      </table>
    </div>
  );
}

/* ------------------------------------------------------------ exams and marks */
function MarksTab({ id, canManage, say }) {
  const [exams, setExams] = useState([]);
  const [current, setCurrent] = useState(null);       // { exam, maxMarks, students }
  const [values, setValues] = useState({});
  const [draft, setDraft] = useState({ name: '', max: '50' });

  const open = useCallback(async (name, max) => {
    try {
      const s = await api.get(`/api/academic/courses/${enc(id)}/exams/${enc(name)}/marks`);
      setCurrent({ ...s, maxMarks: s.maxMarks || Number(max) });
      setValues(Object.fromEntries(s.students.map((r) => [r.id, r.marks == null ? '' : String(r.marks)])));
    } catch (e) { say(e.message, true); }
  }, [id, say]);

  const loadExams = useCallback(async (keep) => {
    const list = await api.get(`/api/academic/courses/${enc(id)}/exams`);
    setExams(list);
    if (keep) return;
    if (list.length) open(list[0].exam);
  }, [id, open]);
  useEffect(() => { loadExams().catch(e=>say(e.message,true)); }, [loadExams, say]);

  const start = (e) => {
    e.preventDefault();
    if (!draft.name.trim()) return;
    open(draft.name.trim(), draft.max);
  };
  const save = async () => {
    try {
      const marks = Object.fromEntries(Object.entries(values).map(([k, v]) => [k, v === '' ? null : v]));
      const r = await api.post(`/api/academic/courses/${enc(id)}/marks`, { exam: current.exam, maxMarks: current.maxMarks, marks });
      say(`${r.exam}: ${r.saved} marks saved, ${r.cleared} cleared`);
      await loadExams(true);
      open(current.exam);
    } catch (e) { say(e.message, true); }
  };
  const remove = async () => {
    if (!window.confirm(`Delete ${current.exam} and all its marks?`)) return;
    try {
      await api.del(`/api/academic/courses/${enc(id)}/exams/${enc(current.exam)}`);
      say(`${current.exam} deleted`);
      setCurrent(null);
      loadExams();
    } catch (e) { say(e.message, true); }
  };

  const entered = Object.values(values).filter((v) => v !== '').length;
  return (
    <div>
      <div className="subtabs">
        {exams.map((e) => (
          <button key={e.exam} className={current && current.exam === e.exam ? 'active' : ''} onClick={() => open(e.exam)}>
            {e.exam}
          </button>
        ))}
        {exams.length === 0 && <span className="muted">No exams yet. Add one below.</span>}
      </div>
      {canManage && (
        <form className="aligned-form exam-form" onSubmit={start}>
          <label>Exam name<input required maxLength={40} placeholder="For example, QUIZ1" value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} /></label>
          <label>Maximum marks<input required type="number" min="1" max="1000" step="0.5" value={draft.max} onChange={(e) => setDraft({ ...draft, max: e.target.value })} /></label>
          <button type="submit" className="secondary">Add</button>
        </form>
      )}
      {current && (
        <>
          {!exams.some(e=>e.exam===current.exam)&&<p className="muted">Enter marks and select Save marks to create this assessment.</p>}
          <div className="row">
            <strong>{current.exam}</strong>
            
            <span className="muted">{entered} of {current.students.length} marks entered</span>
            {canManage && <><button className="small" onClick={save}>Save marks</button><button className="danger small" onClick={remove}>Delete exam</button></>}
          </div>
          <table>
            <thead><tr><th>ID</th><th>Name</th><th>Marks / {num(current.maxMarks)}</th></tr></thead>
            <tbody>
              {current.students.map((s) => (
                <tr key={s.id}>
                  <td>{s.id}</td><td>{s.name}</td>
                  <td><input type="number" step="0.5" min="0" max={current.maxMarks} value={values[s.id] ?? ''} disabled={!canManage}
                             style={{ width: 90 }} onChange={(e) => setValues({ ...values, [s.id]: e.target.value })} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </div>
  );
}

/* ------------------------------------------------------------ grades */
function GradesTab({ id }) {
  const [d, setD] = useState(null);
  useEffect(() => { api.get(`/api/academic/courses/${enc(id)}/grades`).then(setD).catch(() => setD({ exams: [], students: [], distribution: {} })); }, [id]);
  if (!d) return <p>Loading...</p>;
  return (
    <div>
      <table>
        <thead><tr><th>ID</th><th>Name</th>{d.exams.map((e) => <th key={e.exam}>{e.exam} ({num(e.maxMarks)})</th>)}<th>Overall</th><th>Grade</th></tr></thead>
        <tbody>
          {d.students.map((s) => (
            <tr key={s.id}>
              <td>{s.id}</td><td>{s.name}</td>
              {d.exams.map((e) => <td key={e.exam}>{s.marks[e.exam] == null ? '-' : num(s.marks[e.exam])}</td>)}
              <td>{pctText(s.percent)}</td><td><strong>{s.letter || '-'}</strong></td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="muted">Distribution: {Object.entries(d.distribution).filter(([, n]) => n > 0).map(([l, n]) => `${l} ${n}`).join(', ') || 'no grades yet'}</p>
    </div>
  );
}

/* ------------------------------------------------------------ one course */
function CourseManager({ id, user, back }) {
  const [course, setCourse] = useState(null);
  const [tab, setTab] = useState('Students');
  const [msg, setMsg] = useState({ text: '', bad: false });
  const say = useCallback((text, bad = false) => setMsg({ text, bad }), []);
  useEffect(() => { api.get(`/api/academic/courses/${enc(id)}`).then(setCourse).catch((e) => say(e.message, true)); }, [id, say]);

  if (!course) return <section className="card">{msg.text ? <p className="error">{msg.text}</p> : <p>Loading...</p>}</section>;
  const canManage = user.role === 'ADMIN' || (user.role === 'FACULTY' && user.id === course.instructorId);
  return (
    <section className="card">
      <h2>
        <button className="secondary small" onClick={back}>All courses</button>
        {course.code} {course.title}
        <span className="badge">{course.enrolled} of {course.capacity} seats taken</span>
      </h2>
      <p className="muted">{course.instructorName}, {course.credits} credits, {course.scheduleText}.
        {!canManage && ' You can view this course but only its instructor or an admin can change it.'}</p>
      <div className="subtabs">
        {['Students', 'Attendance', 'Exams and marks', 'Grades'].map((t) => (
          <button key={t} className={t === tab ? 'active' : ''} onClick={() => { setTab(t); setMsg({ text: '', bad: false }); }}>{t}</button>
        ))}
      </div>
      {msg.text && <p className={msg.bad ? 'error' : 'ok'}>{msg.text}</p>}
      {tab === 'Students' && <RosterTab id={id} />}
      {tab === 'Attendance' && <AttendanceTab id={id} canManage={canManage} say={say} />}
      {tab === 'Exams and marks' && <MarksTab id={id} canManage={canManage} say={say} />}
      {tab === 'Grades' && <GradesTab id={id} />}
    </section>
  );
}

/* ------------------------------------------------------------ course list */
function Overview() {
  const [o, setO] = useState(null);
  useEffect(() => { api.get('/api/academic/overview').then(setO).catch(() => {}); }, []);
  if (!o) return null;
  return (
    <div className="stats" style={{ marginBottom: 12 }}>
      {Object.entries(o.counts).map(([k, v]) => <div key={k}><b>{v}</b>{k}</div>)}
    </div>
  );
}

function FacultyHome({ user, courseId, onOpenCourse }) {
  const [list, setList] = useState(null);
  const [error, setError] = useState('');
  useEffect(() => {
    const q = user.role === 'FACULTY' ? `?instructorId=${enc(user.id)}` : '';
    api.get(`/api/academic/courses${q}`).then((d) => setList(d.courses)).catch((e) => setError(e.message));
  }, [user.id, user.role]);

  if (courseId) return <CourseManager id={courseId} user={user} back={() => onOpenCourse(null)} />;
  if (error) return <p className="error">{error}</p>;
  if (!list) return <p>Loading...</p>;
  return (
    <section className="card">
      <h2>{user.role === 'ADMIN' ? 'All courses' : 'My courses'}</h2>
      {user.role === 'ADMIN' && <Overview />}
      <p className="muted">Open a course to mark attendance and enter marks.</p>
      <table>
        <thead><tr><th>Code</th><th>Course</th><th>Faculty</th><th>Schedule</th><th>Registered</th><th /></tr></thead>
        <tbody>
          {list.map((c) => (
            <tr key={c.id}>
              <td><strong>{c.code}</strong></td><td>{c.title}</td><td>{c.instructorName}</td><td>{c.scheduleText}</td>
              <td>{c.enrolled} of {c.capacity}</td>
              <td><button className="small" onClick={() => onOpenCourse(c.id)}>Open</button></td>
            </tr>
          ))}
          {list.length === 0 && <tr><td colSpan="6">No courses are assigned to you.</td></tr>}
        </tbody>
      </table>
    </section>
  );
}

export default function FacultyPage({ courseId, onOpenCourse }) {
  return (
    <Gate roles={['FACULTY', 'ADMIN']} only="This page is for faculty and administrators.">
      {(user) => <FacultyHome user={user} courseId={courseId} onOpenCourse={onOpenCourse} />}
    </Gate>
  );
}
