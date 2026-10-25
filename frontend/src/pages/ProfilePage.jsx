import {useState} from 'react';
import {api} from '../api.js';
import {useUser,setUser} from '../session.js';
import {Alert,Avatar,Field} from '../components/ui.jsx';
export default function ProfilePage(){
 const user=useUser(),[form,setForm]=useState({...user}),[message,setMessage]=useState(null),[busy,setBusy]=useState(false);
 async function save(e){e.preventDefault();setBusy(true);setMessage(null);try{setUser(await api.put('/api/auth/me',form));setMessage({text:'Profile saved. Search updates within 60 seconds.',kind:'success'});}catch(e){setMessage({text:e.message,kind:'error'});}finally{setBusy(false);}}
 return <section className="card profile-form"><div className="profile-head"><Avatar name={user.name} size={64}/><div><h2>My profile</h2><p className="muted">A few details that make this space yours.</p></div></div><dl className="kv"><div><dt>Student / staff ID</dt><dd>{user.id}</dd></div><div><dt>Account role</dt><dd>{user.role}</dd></div></dl><p className="muted">{user.email}</p>{message&&<Alert kind={message.kind}>{message.text}</Alert>}<form onSubmit={save}><Field label="Full name"><input required maxLength={100} autoComplete="name" value={form.name} onChange={e=>setForm({...form,name:e.target.value})}/></Field><Field label="Department"><input required maxLength={60} value={form.department} onChange={e=>setForm({...form,department:e.target.value})}/></Field><Field label="Year"><input required type="number" min={user.role==='STUDENT'?1:0} max={8} value={form.year} onChange={e=>setForm({...form,year:Number(e.target.value)})}/></Field><button className="btn" disabled={busy}>{busy?'Saving…':'Save changes'}</button></form></section>;
}
