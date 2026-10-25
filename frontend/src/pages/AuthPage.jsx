import { useState } from 'react';
import { api } from '../api.js';
import { setUser } from '../session.js';
import { Alert, Brand, Field, IconInput } from '../components/ui.jsx';

const PERKS = [
  ['fa-book-open', 'Register for courses with clash and seat checks'],
  ['fa-clipboard-user', 'Track attendance and know how many classes you can miss'],
  ['fa-award', 'Follow marks, grades and GPA as they are published'],
];

export default function AuthPage({ mode = 'login', onMode, onBack }) {
  const register = mode === 'register';
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [show, setShow] = useState(false);
  const [form, setForm] = useState({ name: '', email: '', password: '', department: 'CSE', year: 1 });
  const set = (k, v) => setForm((f) => ({ ...f, [k]: v }));

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      if (register) await api.post('/api/auth/register', form);
      const result = await api.post('/api/auth/login', { email: form.email, password: form.password });
      sessionStorage.setItem('sis.token', result.token);
      setUser(result.user);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth">
      <aside className="auth-aside">
        <Brand light large />
        <div>
          <h2>{register ? 'Join your campus on CampusCore.' : 'Everything campus life needs, in one place.'}</h2>
          <p>{register ? 'Create a student account to register for courses and follow your progress.' : 'Sign in to manage your courses, timetable, attendance, grades and notifications.'}</p>
          <ul className="auth-list">
            {PERKS.map(([icon, text]) => <li key={text}><i className={`fa-solid ${icon}`} />{text}</li>)}
          </ul>
        </div>
        <div className="auth-foot">© 2026 CampusCore</div>
      </aside>

      <main className="auth-main">
        <button className="btn btn-ghost btn-sm auth-back" onClick={onBack}><i className="fa-solid fa-arrow-left" /> Back</button>
        <div className="auth-card">
          <h1>{register ? 'Create your account' : 'Welcome back'}</h1>
          <p>{register ? 'Join CampusCore as a student in under a minute.' : 'Sign in to continue to CampusCore.'}</p>

          {error && <Alert kind="error">{error}</Alert>}

          <form onSubmit={submit}>
            {register && (
              <Field label="Full name">
                <IconInput icon="fa-user">
                  <input value={form.name} maxLength={100} required placeholder="Your full name" autoComplete="name" onChange={(e) => set('name', e.target.value)} />
                </IconInput>
              </Field>
            )}

            <Field label="Email address">
              <IconInput icon="fa-envelope">
                <input type="email" value={form.email} required placeholder="you@college.edu" autoComplete="email" onChange={(e) => set('email', e.target.value)} />
              </IconInput>
            </Field>

            <Field label="Password" hint={register ? 'At least 10 characters.' : undefined}>
              <IconInput icon="fa-lock" toggle>
                <input
                  type={show ? 'text' : 'password'}
                  value={form.password}
                  required
                  minLength={register ? 10 : 1}
                  maxLength={128}
                  placeholder={register ? 'Create a password' : 'Enter your password'}
                  autoComplete={register ? 'new-password' : 'current-password'}
                  onChange={(e) => set('password', e.target.value)}
                />
                <button type="button" className="input-toggle" onClick={() => setShow(!show)} aria-label={show ? 'Hide password' : 'Show password'}>
                  <i className={`fa-solid ${show ? 'fa-eye-slash' : 'fa-eye'}`} />
                </button>
              </IconInput>
            </Field>

            {register && (
              <div className="two">
                <Field label="Department">
                  <IconInput icon="fa-building-columns">
                    <input value={form.department} maxLength={60} required onChange={(e) => set('department', e.target.value)} />
                  </IconInput>
                </Field>
                <Field label="Year">
                  <IconInput icon="fa-layer-group">
                    <input type="number" min={1} max={8} value={form.year} required onChange={(e) => set('year', Number(e.target.value))} />
                  </IconInput>
                </Field>
              </div>
            )}

            <button className="btn btn-lg btn-block" disabled={busy}>
              {busy
                ? <><span className="spinner" /> Please wait…</>
                : register ? <>Create account <i className="fa-solid fa-arrow-right" /></> : <>Sign in <i className="fa-solid fa-right-to-bracket" /></>}
            </button>
          </form>

          <p className="auth-switch">
            {register ? 'Already have an account? ' : 'New student? '}
            <button type="button" className="link" onClick={() => { setError(''); onMode(register ? 'login' : 'register'); }}>
              {register ? 'Sign in' : 'Create an account'}
            </button>
          </p>
        </div>
      </main>
    </div>
  );
}
