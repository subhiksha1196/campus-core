import Autocomplete from '../components/Autocomplete.jsx';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from '../api.js';
import { useUser } from '../session.js';
import { Alert, Badge, Card, Empty, Field, IconInput, Loading, PageHead, Tabs } from '../components/ui.jsx';

// type -> [icon, tone]. Tones map to the .tone-* colours in styles.css.
const TYPES = {
  GENERAL: ['fa-bullhorn', 'info'],
  EXAM: ['fa-file-pen', 'warning'],
  GRADE: ['fa-award', 'success'],
  ATTENDANCE: ['fa-clipboard-user', 'danger'],
  COURSE: ['fa-book-open', 'primary'],
  SCHOLARSHIP: ['fa-graduation-cap', 'accent'],
  INTERNSHIP: ['fa-briefcase', 'accent'],
};
const typeOf = (t) => TYPES[t] || TYPES.GENERAL;
const label = (t = '') => t.charAt(0) + t.slice(1).toLowerCase();

function ago(iso) {
  const s = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 60) return 'Just now';
  if (s < 3600) return `${Math.floor(s / 60)} min ago`;
  if (s < 86400) return `${Math.floor(s / 3600)} h ago`;
  if (s < 7 * 86400) return `${Math.floor(s / 86400)} d ago`;
  return new Date(iso).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}

/** Turn URLs in a message (including shortened links) into clickable links. */
function Linkified({ text = '' }) {
  return text.split(/(https?:\/\/[^\s]+)/g).map((part, i) =>
    /^https?:\/\//.test(part)
      ? <a key={i} href={part} target="_blank" rel="noreferrer">{part}</a>
      : part);
}

function NotificationItem({ n, onRead }) {
  const [icon, tone] = typeOf(n.type);
  return (
    <li className={n.read ? 'read' : 'unread'}>
      <span className={`feed-ico tone-${tone}`}><i className={`fa-solid ${icon}`} /></span>
      <div className="feed-body">
        <div className="feed-title">
          {n.title}
          <Badge tone={tone}>{label(n.type)}</Badge>
        </div>
        <div className="feed-msg"><Linkified text={n.message} /></div>
        <div className="feed-meta" title={new Date(n.createdAt).toLocaleString()}>
          <span><i className="fa-regular fa-clock" /> {ago(n.createdAt)}</span>
          {n.read && <span><i className="fa-solid fa-check" /> Read</span>}
        </div>
      </div>
      {!n.read && (
        <button className="btn btn-ghost btn-sm feed-action" onClick={() => onRead(n.id)} title="Mark as read">
          <i className="fa-solid fa-check" /> Mark read
        </button>
      )}
    </li>
  );
}

export default function NotificationsPage() {
  const user = useUser();
  const isAdmin = user.role === 'ADMIN';
  const canSend = user.role !== 'STUDENT';

  const [studentId, setStudentId] = useState(user.id);
  const [draftId, setDraftId] = useState(user.id);
  const [items, setItems] = useState(null);
  const [unread, setUnread] = useState(0);
  const [tab, setTab] = useState('all');
  const [type, setType] = useState('ALL');
  const [form, setForm] = useState({ to: user.id, type: 'GENERAL', title: '', message: '' });
  const [sending, setSending] = useState(false);
  const [error, setError] = useState('');
  const [sent, setSent] = useState('');

  const load = useCallback(async () => {
    try {
      const q = encodeURIComponent(studentId);
      const [list, count] = await Promise.all([
        api.get(`/api/notifications?studentId=${q}`),
        api.get(`/api/notifications/unread-count?studentId=${q}`),
      ]);
      setItems(list);
      setUnread(count.unread);
      setError('');
    } catch (e) {
      setItems((cur) => cur || []);
      setError(e.message);
    }
  }, [studentId]);

  useEffect(() => { load(); }, [load]);

  const send = async (e) => {
    e.preventDefault();
    const ids = form.to.split(',').map((s) => s.trim()).filter(Boolean);
    if (!ids.length) return;
    setSending(true);
    setSent('');
    try {
      const body = { type: form.type, title: form.title, message: form.message };
      if (ids.length > 1) await api.post('/api/notifications/broadcast', { ...body, studentIds: ids });
      else await api.post('/api/notifications', { ...body, studentId: ids[0] });
      setForm({ ...form, title: '', message: '' });
      setSent(ids.length > 1 ? `Sent to ${ids.length} students.` : 'Notification sent.');
      await load();
    } catch (err) {
      setError(err.message);
    } finally {
      setSending(false);
    }
  };

  const markRead = async (id) => {
    try { await api.patch(`/api/notifications/${id}/read`); await load(); } catch (e) { setError(e.message); }
  };
  const markAll = async () => {
    try { await api.post(`/api/notifications/read-all?studentId=${encodeURIComponent(studentId)}`); await load(); } catch (e) { setError(e.message); }
  };

  const visible = useMemo(
    () => (items || []).filter((n) => (tab === 'all' || !n.read) && (type === 'ALL' || n.type === type)),
    [items, tab, type],
  );
  const total = items ? items.length : 0;

  const inbox = (
    <Card
      icon="fa-inbox"
      title="Inbox"
      flush
      actions={
        <>
          <button className="btn btn-ghost btn-sm" onClick={load}><i className="fa-solid fa-rotate" /> Refresh</button>
          <button className="btn btn-soft btn-sm" onClick={markAll} disabled={!unread}><i className="fa-solid fa-check-double" /> Mark all read</button>
        </>
      }
    >
      <div className="toolbar bar">
        {isAdmin && (
          <form className="grow" onSubmit={(e) => { e.preventDefault(); setStudentId(draftId.trim() || user.id); }}>
            <Autocomplete type="student" label="Search student inbox" value={draftId} onText={setDraftId} selectValue={r=>r.id} onPick={r=>setStudentId(r.id)} placeholder="Find a student by name or ID" />
          </form>
        )}
        <select value={type} onChange={(e) => setType(e.target.value)} aria-label="Filter by type">
          <option value="ALL">All types</option>
          {Object.keys(TYPES).map((t) => <option key={t} value={t}>{label(t)}</option>)}
        </select>
      </div>
      <div className="card-pad" style={{ paddingBottom: 0 }}>
        <Tabs
          value={tab}
          onChange={setTab}
          items={[{ key: 'all', label: `All (${total})` }, { key: 'unread', label: `Unread (${unread})` }]}
        />
      </div>
      {error && <div className="card-pad" style={{ paddingBottom: 0 }}><Alert kind="error">{error}</Alert></div>}
      {items === null ? <Loading text="Loading notifications…" /> : visible.length === 0 ? (
        <Empty icon={tab === 'unread' ? 'fa-circle-check' : 'fa-bell-slash'} title={tab === 'unread' ? 'You are all caught up' : 'No notifications'}>
          {tab === 'unread' ? 'There is nothing new to read.' : 'Exam, grade, attendance and opportunity notices will show up here.'}
        </Empty>
      ) : (
        <ul className="feed">
          {visible.map((n) => <NotificationItem key={n.id} n={n} onRead={markRead} />)}
        </ul>
      )}
    </Card>
  );

  const composer = (
    <Card icon="fa-paper-plane" title="Send notification" sub="Links in the message are shortened automatically.">
      {sent && <Alert kind="success">{sent}</Alert>}
      <form onSubmit={send}>
        <Field label="To" hint="One student ID, or several separated by commas.">
          <Autocomplete type="student" label="Notification recipients" value={form.to} onText={to=>setForm({...form,to})} multiple selectValue={r=>r.id} placeholder="Search students or enter IDs" required />
        </Field>
        <Field label="Type">
          <select value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value })}>
            {Object.keys(TYPES).map((t) => <option key={t} value={t}>{label(t)}</option>)}
          </select>
        </Field>
        <Field label="Title">
          <input value={form.title} maxLength={120} onChange={(e) => setForm({ ...form, title: e.target.value })} required />
        </Field>
        <Field label="Message" hint={`${form.message.length} characters`}>
          <textarea rows="4" value={form.message} onChange={(e) => setForm({ ...form, message: e.target.value })} required />
        </Field>
        <button type="submit" className="btn btn-block" disabled={sending}>
          {sending ? <><span className="spinner" /> Sending…</> : <>Send <i className="fa-solid fa-paper-plane" /></>}
        </button>
      </form>
    </Card>
  );

  return (
    <>
      <PageHead icon="fa-bell" title="Notifications" sub="Exams, results, attendance alerts and opportunities in one place." />
      {canSend ? <div className="layout-aside">{composer}{inbox}</div> : inbox}
    </>
  );
}
