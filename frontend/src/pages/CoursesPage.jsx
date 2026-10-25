import Autocomplete from '../components/Autocomplete.jsx';
import { useEffect, useRef, useState } from 'react';
import { api } from '../api.js';
import Gate from '../components/Gate.jsx';
import PersonDetail from '../components/PersonDetail.jsx';
import { Avatar, Alert, Empty, Loading, firstName, titleCase } from '../components/ui.jsx';

const enc = encodeURIComponent;
// Home-page search types. Students only search courses; faculty and admins can search all three.
const PEOPLE_SEARCH = [['student', 'Students'], ['faculty', 'Faculty'], ['course', 'Courses']];
const DAYS = ['Sunday','Monday','Tuesday','Wednesday','Thursday','Friday','Saturday'];

function CourseDialog({ course, busy, onClose, onAct, isStudent, onOpenCourse }) {
  const ref = useRef(null);
  const [confirm, setConfirm] = useState(false);
  useEffect(() => { ref.current.showModal(); }, []);
  return <dialog ref={ref} className="course-dialog" onCancel={onClose} onClick={e => { if(e.target === ref.current) onClose(); }} aria-labelledby="course-title">
    <div className="split"><span className="eyebrow">{course.code} · {course.department}</span><button className="icon-btn" aria-label="Close course details" onClick={onClose}><i className="fa-solid fa-xmark" /></button></div>
    <h2 id="course-title">{course.title}</h2><p className="muted">{course.instructorName || 'Instructor to be assigned'}</p>
    <dl className="course-facts"><div><dt>Credits</dt><dd>{course.credits}</dd></div><div><dt>Semester</dt><dd>{course.semester}</dd></div><div><dt>Available seats</dt><dd>{course.seatsLeft} / {course.capacity}</dd></div></dl>
    <h3>Class schedule</h3><p className="muted">{course.scheduleText || 'Schedule not yet published.'}</p>
    {confirm && <Alert kind="warning">Drop this course? It will be removed from your current registrations and timetable.</Alert>}
    <div className="dialog-actions"><button className="secondary" onClick={onClose}>Close</button>{isStudent ? <button disabled={busy || (!course.registered && course.seatsLeft <= 0)} onClick={() => { if(course.registered && !confirm) setConfirm(true); else onAct(course); }}>{busy ? 'Saving…' : course.registered ? confirm ? 'Confirm drop' : 'Drop course' : course.seatsLeft <= 0 ? 'Course full' : 'Register for course'}</button> : <button onClick={() => onOpenCourse(course.id)}>Manage course</button>}</div>
  </dialog>;
}

function TodayPanel({ user, profile, refresh, onNavigate }) {
  const [slots, setSlots] = useState([]), [notice, setNotice] = useState(null), [error, setError] = useState('');
  const [loading,setLoading] = useState(true), [noticeError,setNoticeError] = useState('');
  const [date,setDate] = useState(() => new Date());
  useEffect(() => {
    let active = true; setLoading(true);setError('');
    const key = user.role === 'STUDENT' ? 'studentId' : 'instructorId';
    const schedule = user.role === 'ADMIN' ? Promise.resolve({slots:[]}) : api.get(`/api/academic/timetable?${key}=${enc(user.id)}`);
    schedule.then(d => { if(active) setSlots(d.slots); }).catch(e => { if(active)setError(e.message); }).finally(() => {if(active)setLoading(false);});
    api.get(`/api/notifications?studentId=${enc(user.id)}&size=1`).then(d => {if(active){setNotice(d[0] || null);setNoticeError('');}}).catch(e => {if(active)setNoticeError(e.message);});
    return () => {active=false;};
  },[user.id,user.role,refresh]);
  const daySlots = slots.filter(s => String(s.day).slice(0,3).toUpperCase() === DAYS[date.getDay()].slice(0,3).toUpperCase()).sort((a,b) => a.startTime.localeCompare(b.startTime));
  const start = new Date(date); start.setDate(date.getDate() - ((date.getDay()+6)%7));
  const dates = Array.from({length:7},(_,i) => {const d=new Date(start);d.setDate(start.getDate()+i);return d;});
  return <aside className="today-panel" aria-label="Your day">
    <div className="rail-account"><Avatar name={user.name} size={52}/><div><strong>{user.name}</strong><p>{user.department} · {user.role==='STUDENT' ? `Year ${user.year}` : titleCase(user.role)}</p></div><button className="icon-btn" aria-label="Open notifications" onClick={()=>onNavigate('notifications')}><i className="fa-regular fa-bell"/></button></div>
    {profile && <section className="credit-card"><div className="split"><span>Semester credits</span><strong>{profile.credits} / {profile.maxCredits}</strong></div><div className="meter" role="progressbar" aria-label="Registered credits" aria-valuenow={profile.credits} aria-valuemin={0} aria-valuemax={profile.maxCredits}><i style={{width:`${Math.min(100,profile.credits/profile.maxCredits*100)}%`}}/></div></section>}
    {user.role!=='ADMIN' && <section className="day-agenda"><p className="muted">{date.toLocaleDateString('en-IN',{weekday:'long',day:'numeric',month:'long'})}</p><div className="split"><h2>{date.toDateString()===new Date().toDateString()?'Today':DAYS[date.getDay()]}</h2><button className="text-action" onClick={()=>onNavigate('timetable')}>Timetable <span>↗</span></button></div>
      <div className="week-strip">{dates.map(d=><button key={d.toDateString()} aria-pressed={d.toDateString()===date.toDateString()} aria-label={d.toLocaleDateString('en-IN',{weekday:'long',day:'numeric',month:'long'})} className={d.toDateString()===date.toDateString()?'chosen':''} onClick={()=>setDate(d)}><small>{d.toLocaleDateString('en',{weekday:'short'})}</small><b>{d.getDate()}</b></button>)}</div>
      {loading ? <Loading text="Loading schedule…"/> : error ? <Alert>{error}</Alert> : daySlots.length ? <div className="agenda-list">{daySlots.map((s,i)=><div className="agenda-row" key={`${s.courseId}-${s.startTime}`}><time>{s.startTime}</time><div className={`agenda-card ${i===0?'featured':''}`}><strong>{s.title}</strong><p>Room {s.room || 'TBA'} · {s.startTime}–{s.endTime}</p></div></div>)}</div> : <div className="quiet-empty"><i className="fa-regular fa-calendar-check"/><p>No classes scheduled.</p><small>A little room in your day.</small></div>}
    </section>}
    <section className="notice-preview"><h3>Latest notice</h3>{noticeError ? <p className="error">{noticeError}</p> : notice ? <button className="notice-link" onClick={()=>onNavigate('notifications')}><i className="fa-regular fa-file-lines"/><span>{notice.title}</span><span>›</span></button> : <p className="muted">You’re all caught up. New notices will appear here.</p>}<button className="text-action" onClick={()=>onNavigate('notifications')}>View notifications <span>→</span></button></section>
  </aside>;
}

function Courses({ user, onOpenCourse, onNavigate }) {
  const isStudent=user.role==='STUDENT',isFaculty=user.role==='FACULTY';
  const [q,setQ]=useState(''),[dept,setDept]=useState(''),[tab,setTab]=useState(isStudent?'registered':'catalog');
  const [data,setData]=useState({courses:[],departments:[]}),[profile,setProfile]=useState(null),[loading,setLoading]=useState(true);
  const [msg,setMsg]=useState(null),[busy,setBusy]=useState(false),[selected,setSelected]=useState(null),[revision,setRevision]=useState(0),[page,setPage]=useState(1);
  const [stype,setStype]=useState('course'),[pq,setPq]=useState(''),[person,setPerson]=useState(null);
  const changeType=k=>{setStype(k);setPq('');setPerson(null);};
  // Faculty see only the courses they teach in the list, but their Courses search still covers the whole campus.
  const openCampusCourse=id=>api.get(`/api/academic/courses/${enc(id)}`).then(setSelected).catch(e=>setMsg({bad:true,text:e.message}));
  useEffect(()=>{
    let active=true;setLoading(true);
    Promise.all([api.get(`/api/academic/courses${isStudent?`?studentId=${enc(user.id)}`:isFaculty?`?instructorId=${enc(user.id)}`:''}`),isStudent?api.get(`/api/academic/people/${enc(user.id)}`):Promise.resolve(null)])
      .then(([d,p])=>{if(active){setData(d);setProfile(p);}}).catch(e=>{if(active)setMsg({bad:true,text:e.message});}).finally(()=>{if(active)setLoading(false);});
    return()=>{active=false;};
  },[user.id,isStudent,isFaculty,revision]);
  useEffect(()=>setPage(1),[q,dept,tab]);
  async function act(c) {
    if(busy)return;setBusy(true);setMsg(null);
    try {
      if(c.registered) await api.del(`/api/academic/registrations/${enc(user.id)}/${enc(c.id)}`);
      else await api.post('/api/academic/registrations',{studentId:user.id,courseId:c.id});
      setMsg({bad:false,text:`${c.registered?'Dropped':'Registered for'} ${c.code}. Your timetable has been updated.`});setSelected(null);setRevision(x=>x+1);
    }catch(e){setMsg({bad:true,text:e.message});setSelected(null);}finally{setBusy(false);}
  }
  const mine=data.courses.filter(c=>c.registered);
  const matches=data.courses.filter(c=>(tab!=='registered'||c.registered)&&(!dept||c.department===dept)&&`${c.title} ${c.code} ${c.instructorName}`.toLowerCase().includes(q.trim().toLowerCase()));
  const pages=Math.max(1,Math.ceil(matches.length/4));const safePage=Math.min(page,pages);const visible=matches.slice((safePage-1)*4,safePage*4);
  return <div className="courses-layout"><div className="courses-main">
    <header className="welcome"><h1>Hello, {isStudent?firstName(user.name):user.name}</h1><p className="welcome-sub">{isStudent?'Your courses, all in one place.':'Manage your academic day with confidence.'}</p></header>
    {!isStudent&&<div className="tabs" aria-label="Search type" style={{marginBottom:12}}>{PEOPLE_SEARCH.map(([k,label])=><button key={k} aria-pressed={stype===k} className={stype===k?'active':''} onClick={()=>changeType(k)}>{label}</button>)}</div>}
    <div className="course-search"><i className="fa-solid fa-magnifying-glass"/>{stype==='course'&&!isFaculty
      ? <Autocomplete label="Search courses" value={q} onText={setQ} placeholder="Search courses by name or code" options={data.courses.filter(c=>(tab!=='registered'||c.registered)&&(!dept||c.department===dept)).map(c=>({id:c.id,label:c.title,sub:`${c.code} · ${c.instructorName}`}))} onPick={r=>setSelected(data.courses.find(c=>c.id===r.id))}/>
      : <Autocomplete key={stype} type={stype} label={`Search ${stype==='student'?'students':stype==='faculty'?'faculty':'courses'}`} value={pq} onText={t=>{setPq(t);setPerson(null);}} placeholder={stype==='student'?'Search students by name or ID':stype==='faculty'?'Search faculty by name or ID':'Search courses by name or code'} limit={12} onPick={r=>stype==='course'?openCampusCourse(r.id):setPerson({type:stype,id:r.id})}/>}
    </div>
    {stype!=='course'&&person&&<PersonDetail type={person.type} id={person.id} user={user} onClose={()=>setPerson(null)}/>}
    <div className="course-section-head"><h2>{isStudent||isFaculty?'My courses':'Courses'}</h2><p className="muted">{isStudent?`${mine.length} courses${profile?` · ${profile.credits} credits`:''}`:isFaculty?`${data.courses.length} ${data.courses.length===1?'course':'courses'} you teach`:`${data.courses.length} courses across campus`}</p></div>
    <div className="course-controls"><div className="tabs" aria-label="Course views">{isStudent&&<button aria-pressed={tab==='registered'} className={tab==='registered'?'active':''} onClick={()=>setTab('registered')}>Registered</button>}<button aria-pressed={tab==='catalog'} className={tab==='catalog'?'active':''} onClick={()=>setTab('catalog')}>{isFaculty?'Courses I teach':'Course catalog'}</button></div><select aria-label="Filter by department" value={dept} onChange={e=>setDept(e.target.value)}><option value="">All departments</option>{data.departments.map(d=><option key={d}>{d}</option>)}</select></div>
    {msg&&<Alert kind={msg.bad?'error':'success'}>{msg.text}{msg.bad&&<button className="link retry" onClick={()=>{setMsg(null);setRevision(x=>x+1);}}>Retry</button>}</Alert>}
    {loading?<Loading text="Loading courses…"/>:visible.length?<div className="course-list">{visible.map(c=><article className="course-tile" key={c.id}><div className="split"><span className="eyebrow">{c.code} · {c.credits} credits</span><span className="course-status">{c.registered?'Registered':c.seatsLeft<=0?'Full':`${c.seatsLeft} seats available`}</span></div><h3>{c.title}</h3><p>{c.instructorName || 'Instructor to be assigned'}</p><div className="course-tile-foot"><span><i className="fa-regular fa-calendar"/> {c.scheduleText || 'Schedule to be announced'}</span><button className="text-action" onClick={()=>setSelected(c)}>View course <span>↗</span></button></div>{tab==='catalog'&&isStudent&&!c.registered&&<button className="register-action" disabled={busy||c.seatsLeft<=0} onClick={()=>act(c)}>{busy?'Saving…':c.seatsLeft<=0?'Course full':'Register'}</button>}</article>)}</div>:<Empty icon="fa-book-open" title={tab==='registered'&&!q&&!dept?'Your semester starts here':'No matching courses'}>{tab==='registered'?'Explore the course catalog to find your next class.':isFaculty?'No courses are assigned to you yet.':'Try a different search or department.'}</Empty>}
    {!loading&&matches.length>0&&<div className="pagination"><span>{matches.length} {matches.length===1?'course':'courses'}</span><div><button className="secondary" disabled={safePage===1} onClick={()=>setPage(safePage-1)} aria-label="Previous courses">←</button><span>{safePage} / {pages}</span><button className="secondary" disabled={safePage===pages} onClick={()=>setPage(safePage+1)} aria-label="Next courses">→</button></div></div>}
    {selected&&<CourseDialog key={selected.id} course={selected} busy={busy} onClose={()=>setSelected(null)} onAct={act} isStudent={isStudent} onOpenCourse={onOpenCourse}/>}
  </div><TodayPanel user={user} profile={profile} refresh={revision} onNavigate={onNavigate}/></div>;
}
export default function CoursesPage(props){return <Gate>{user=><Courses key={user.id} user={user} {...props}/>}</Gate>;}
